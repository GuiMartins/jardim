package com.jardim.avisos

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jardim.JardimApp
import com.jardim.motor.agenda.ItemPreparo
import com.jardim.motor.agenda.ModoAviso
import com.jardim.motor.agenda.avisarEpoca
import com.jardim.motor.agenda.paraAviso
import com.jardim.motor.tempo.noCentroSul
import com.jardim.ui.nomeDoItem
import kotlinx.coroutines.flow.first
import java.time.LocalDate

/**
 * Lê o banco, calcula as pendências de hoje e publica o aviso. Como o estado
 * vem sempre do banco, um aviso atrasado ou perdido não deixa nada
 * inconsistente: o próximo mostra a situação real.
 */
class AvisoWorker(contexto: Context, parametros: WorkerParameters) : CoroutineWorker(contexto, parametros) {

    override suspend fun doWork(): Result {
        val app = applicationContext as JardimApp
        val modo = inputData.getString(MODO)?.let(ModoAviso::valueOf) ?: return Result.failure()
        val avulso = inputData.getBoolean(AVULSO, false)
        try {
            // Geada e calor dependem da previsão de hoje; sem rede, vale a última baixada.
            app.previsoes.atualizar()
            val hoje = LocalDate.now()
            val itens = app.repositorio.plantiosAtivos.first().flatMap { plantio ->
                paraAviso(modo, plantio.cronograma().pendenciasEm(hoje)).map { ItemAviso(plantio.id, plantio.apelido, it) }
            }
            app.avisos.publicar(modo, itens, mesmoVazio = avulso)
            // Depois do resumo, que começa limpando as notificações de ontem.
            // O "Testar agora" não gasta o aviso de época.
            if (modo == ModoAviso.RESUMO && !avulso) avisarEpocas(app, hoje)
        } finally {
            // Mesmo se publicar falhar, a corrente diária continua.
            if (!avulso) app.agendador.agendarProximo(modo)
        }
        return Result.success()
    }

    private suspend fun avisarEpocas(app: JardimApp, hoje: LocalDate) {
        // Os meses do catálogo são do Centro-Sul; sem cidade, vale o padrão.
        val cidade = app.preferencias.cidade.first()
        if (cidade != null && !noCentroSul(cidade)) return
        app.repositorio.planejados.first()
            .filter { avisarEpoca(it.mesesPlantio, it.planejadoEm, it.epocaAvisadaEm, hoje) }
            .forEach { p ->
                val falta = ItemPreparo.entries.filter { it in p.faltam }.map { nomeDoItem(it, p.formaInicio) }
                if (app.avisos.publicarEpoca(p.id, p.nome, falta.joinToString(", ").ifEmpty { null })) {
                    app.repositorio.marcarEpocaAvisada(p.id, hoje)
                }
            }
    }

    companion object {
        const val MODO = "modo"
        const val AVULSO = "avulso"
    }
}
