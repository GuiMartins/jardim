package com.jardim.avisos

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jardim.MainActivity
import com.jardim.R
import com.jardim.motor.agenda.ModoAviso
import com.jardim.motor.agenda.Pendencia
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.modelo.TipoTarefa
import com.jardim.ui.atraso
import com.jardim.ui.icone

/** Uma pendência de um plantio, pronta para virar notificação. */
data class ItemAviso(val plantioId: Long, val apelido: String, val pendencia: Pendencia)

/**
 * As notificações formam um grupo: o Android mostra tudo recolhido como um
 * resumo só ("3 tarefas para hoje") e, ao expandir, cada pendência vira uma
 * notificação com os próprios botões. Um resumo único não daria: uma
 * notificação tem no máximo 3 botões.
 */
class Avisos(private val context: Context) {

    private val gerenciador = NotificationManagerCompat.from(context)

    fun criarCanais() {
        gerenciador.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CANAL_RESUMO, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                    .setName("Tarefas do dia")
                    .setDescription("O resumo da manhã, com o que fazer em cada planta")
                    .build(),
                NotificationChannelCompat.Builder(CANAL_NOITE, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Lembrete da noite")
                    .setDescription("Tarefas críticas que ainda não foram feitas")
                    .build(),
            ),
        )
    }

    fun ligadas(): Boolean = gerenciador.areNotificationsEnabled()

    @SuppressLint("MissingPermission") // conferido em ligadas()
    fun publicar(modo: ModoAviso, itens: List<ItemAviso>, mesmoVazio: Boolean = false) {
        if (!ligadas()) return
        // O resumo da manhã substitui tudo o que sobrou de ontem. O da noite
        // reposta só as críticas: com o mesmo id, elas trocam as da manhã pela
        // versão do canal mais insistente.
        if (modo == ModoAviso.RESUMO) gerenciador.cancelAll()
        if (itens.isEmpty()) {
            if (mesmoVazio) gerenciador.notify(ID_RESUMO, nadaParaHoje())
            return
        }
        val canal = if (modo == ModoAviso.NOITE) CANAL_NOITE else CANAL_RESUMO
        // O Samsung mostra na face do grupo recolhido a notificação mais nova.
        // Os itens já vêm do mais importante (crítico, mais atrasado) para o
        // menos importante: o primeiro ganha o `when` mais recente e é publicado
        // por último, então é ele que aparece.
        val agora = System.currentTimeMillis()
        itens.withIndex().reversed().forEach { (i, item) ->
            gerenciador.notify(idDe(item.plantioId, item.pendencia.chave), filho(item, canal, quando = agora - i, ordem = i))
        }
        gerenciador.notify(ID_RESUMO, resumo(modo, itens, canal))
    }

    /**
     * "Começou a época" de uma planta em Para plantar. Fica fora do grupo das
     * pendências: não tem "Feito", porque o que resolve é plantar. Devolve se
     * publicou, para o aviso só contar como dado quando chegou a sair.
     */
    @SuppressLint("MissingPermission") // conferido em ligadas()
    fun publicarEpoca(planejadoId: Long, nome: String, falta: String?): Boolean {
        if (!ligadas()) return false
        val texto = falta?.let { "Ainda falta: $it." } ?: "Está tudo pronto para começar."
        gerenciador.notify(
            "planejado|$planejadoId".hashCode(),
            base(CANAL_RESUMO)
                .setContentTitle("🌱 Começou a época: $nome")
                .setContentText(texto)
                .build(),
        )
        return true
    }

    /** A pendência foi marcada (no app ou no botão da notificação): some com a notificação dela. */
    fun cancelar(plantioId: Long, chave: String) {
        val id = idDe(plantioId, chave)
        gerenciador.cancel(id)
        // O resumo do grupo não some sozinho quando o app cancela o último filho.
        val restantes = context.getSystemService(NotificationManager::class.java).activeNotifications
            .count { it.id != id && it.id != ID_RESUMO && it.notification.group == GRUPO }
        if (restantes == 0) gerenciador.cancel(ID_RESUMO)
    }

    private fun filho(item: ItemAviso, canal: String, quando: Long, ordem: Int): Notification {
        val p = item.pendencia
        val id = idDe(item.plantioId, p.chave)
        val linha = "${item.apelido} · ${atraso(p.diasDeAtraso)}"
        val corpo = listOfNotNull(linha, p.contexto, p.instrucoes).joinToString("\n\n")
        return base(canal)
            .setWhen(quando)
            .setSortKey("%03d".format(ordem))
            .setContentTitle("${icone(p)} ${p.titulo}")
            .setContentText(linha)
            .setStyle(NotificationCompat.BigTextStyle().bigText(corpo))
            .setGroup(GRUPO)
            // Só o resumo toca o som: três pendências não viram três apitos.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_SUMMARY)
            .addAction(0, "Feito", acao(item, StatusAcao.FEITA, id))
            .apply {
                if (p.tipo == TipoTarefa.REGA) addAction(0, "Não precisou", acao(item, StatusAcao.NAO_PRECISOU, id))
            }
            .build()
    }

    private fun resumo(modo: ModoAviso, itens: List<ItemAviso>, canal: String): Notification {
        val total = itens.size
        val atrasadas = itens.count { it.pendencia.diasDeAtraso > 0 }
        val titulo = when {
            modo == ModoAviso.NOITE -> "Ainda falta: " + plural(total, "tarefa crítica", "tarefas críticas")
            atrasadas == 0 -> plural(total, "tarefa", "tarefas") + " para hoje"
            atrasadas == total -> plural(total, "tarefa atrasada", "tarefas atrasadas")
            else -> plural(total, "tarefa", "tarefas") + " para hoje · " + plural(atrasadas, "atrasada", "atrasadas")
        }
        val estilo = NotificationCompat.InboxStyle().setBigContentTitle(titulo)
        itens.forEach { estilo.addLine("${icone(it.pendencia)} ${it.pendencia.titulo} · ${it.apelido}") }
        return base(canal)
            .setContentTitle(titulo)
            .setContentText(itens.joinToString(", ") { it.pendencia.titulo })
            .setStyle(estilo)
            .setGroup(GRUPO)
            .setGroupSummary(true)
            .build()
    }

    private fun nadaParaHoje(): Notification = base(CANAL_RESUMO)
        .setContentTitle("Nada para hoje 🌿")
        .setContentText("As notificações estão funcionando; hoje nenhuma planta precisa de você.")
        .build()

    private fun base(canal: String) = NotificationCompat.Builder(context, canal)
        .setSmallIcon(R.drawable.ic_notificacao)
        .setColor(VERDE)
        .setContentIntent(abrirApp())
        .setAutoCancel(true)

    private fun abrirApp(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun acao(item: ItemAviso, status: StatusAcao, id: Int): PendingIntent {
        val intent = Intent(context, AcaoAvisoReceiver::class.java)
            // O data deixa cada intent diferente; sem ele, o PendingIntent de
            // um botão sobrescreveria os extras do outro.
            .setData(Uri.parse("jardim://acao/$id/${status.name}"))
            .putExtra(AcaoAvisoReceiver.PLANTIO, item.plantioId)
            .putExtra(AcaoAvisoReceiver.CHAVE, item.pendencia.chave)
            .putExtra(AcaoAvisoReceiver.STATUS, status.name)
        return PendingIntent.getBroadcast(context, id, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun plural(n: Int, singular: String, plural: String) = if (n == 1) "1 $singular" else "$n $plural"

    private companion object {
        const val CANAL_RESUMO = "resumo"
        const val CANAL_NOITE = "noite"
        const val GRUPO = "pendencias"
        const val ID_RESUMO = 1
        const val VERDE = 0xFF2E6B30.toInt()

        /** Mesmo id de manhã e à noite para a mesma pendência: repostar substitui. */
        fun idDe(plantioId: Long, chave: String): Int = "$plantioId|$chave".hashCode()
    }
}
