package com.jardim.avisos

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jardim.motor.agenda.ModoAviso
import java.time.Duration
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit

/**
 * Cada aviso é um trabalho único do WorkManager que, ao rodar, agenda o do dia
 * seguinte. Não usa PeriodicWorkRequest de 24 h porque ele escorrega um pouco a
 * cada dia (Doze, janela flexível); recalcular a próxima data sempre a partir
 * do horário escolhido mantém o 8h em 8h. Não precisa de alarme exato nem de
 * receiver de boot: o WorkManager sobrevive a reinícios sozinho.
 */
class Agendador(private val context: Context, private val preferencias: Preferencias) {

    private val workManager get() = WorkManager.getInstance(context)

    /** Na abertura do app: agenda o que estiver faltando e não mexe no que já existe. */
    suspend fun garantirAgendado() = ModoAviso.entries.forEach { agendar(it, ExistingWorkPolicy.KEEP) }

    /** O horário mudou na tela de configurações. */
    suspend fun reagendar(modo: ModoAviso) = agendar(modo, ExistingWorkPolicy.REPLACE)

    /**
     * Chamado pelo próprio worker ao terminar. REPLACE cancelaria o worker que
     * está rodando, e KEEP ignoraria o pedido, porque o atual ainda conta como
     * pendente. APPEND_OR_REPLACE encadeia o próximo depois dele.
     */
    suspend fun agendarProximo(modo: ModoAviso) = agendar(modo, ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** Botão "Testar agora": um resumo avulso, fora da corrente diária. */
    fun testarAgora() {
        val pedido = OneTimeWorkRequestBuilder<AvisoWorker>()
            .setInputData(workDataOf(AvisoWorker.MODO to ModoAviso.RESUMO.name, AvisoWorker.AVULSO to true))
            .build()
        workManager.enqueueUniqueWork("aviso-teste", ExistingWorkPolicy.REPLACE, pedido)
    }

    private suspend fun agendar(modo: ModoAviso, politica: ExistingWorkPolicy) {
        val agora = LocalDateTime.now()
        val hojeNoHorario = agora.toLocalDate().atTime(preferencias.horario(modo))
        val proxima = if (hojeNoHorario.isAfter(agora)) hojeNoHorario else hojeNoHorario.plusDays(1)
        val pedido = OneTimeWorkRequestBuilder<AvisoWorker>()
            .setInitialDelay(Duration.between(agora, proxima).toMillis(), TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(AvisoWorker.MODO to modo.name))
            .build()
        workManager.enqueueUniqueWork("aviso-${modo.name.lowercase()}", politica, pedido)
    }
}
