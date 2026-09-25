package com.jardim.avisos

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jardim.JardimApp
import com.jardim.motor.agenda.StatusAcao
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Botões "Feito" e "Não precisou" da notificação: registra sem abrir o app. */
class AcaoAvisoReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val plantioId = intent.getLongExtra(PLANTIO, -1)
        val chave = intent.getStringExtra(CHAVE) ?: return
        val status = intent.getStringExtra(STATUS)?.let(StatusAcao::valueOf) ?: return
        val app = context.applicationContext as JardimApp

        // goAsync segura o processo vivo enquanto o Room grava fora da main thread.
        val pendente = goAsync()
        app.escopo.launch {
            try {
                app.repositorio.registrarSePendente(plantioId, chave, status, LocalDate.now())
                app.avisos.cancelar(plantioId, chave)
            } finally {
                pendente.finish()
            }
        }
    }

    companion object {
        const val PLANTIO = "plantio"
        const val CHAVE = "chave"
        const val STATUS = "status"
    }
}
