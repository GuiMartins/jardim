package com.jardim.avisos

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jardim.JardimApp
import com.jardim.motor.agenda.ModoAviso
import com.jardim.motor.agenda.paraAviso
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
            val hoje = LocalDate.now()
            val itens = app.repositorio.plantiosAtivos.first().flatMap { plantio ->
                paraAviso(modo, plantio.cronograma().pendenciasEm(hoje)).map { ItemAviso(plantio.id, plantio.apelido, it) }
            }
            app.avisos.publicar(modo, itens, mesmoVazio = avulso)
        } finally {
            // Mesmo se publicar falhar, a corrente diária continua.
            if (!avulso) app.agendador.agendarProximo(modo)
        }
        return Result.success()
    }

    companion object {
        const val MODO = "modo"
        const val AVULSO = "avulso"
    }
}
