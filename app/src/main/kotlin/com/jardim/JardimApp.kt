package com.jardim

import android.app.Application
import com.jardim.avisos.Agendador
import com.jardim.avisos.Avisos
import com.jardim.avisos.Preferencias
import com.jardim.dados.Catalogo
import com.jardim.dados.JardimDatabase
import com.jardim.dados.JardimRepositorio
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Injeção de dependência manual: as poucas peças do app moram aqui. */
class JardimApp : Application() {
    /** Para trabalho que não pertence a uma tela (receiver da notificação, agendamento). */
    val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val catalogo by lazy { Catalogo(assets) }
    val repositorio by lazy { JardimRepositorio(JardimDatabase.criar(this).dao()) }
    val preferencias by lazy { Preferencias(this) }
    val avisos by lazy { Avisos(this) }
    val agendador by lazy { Agendador(this, preferencias) }

    override fun onCreate() {
        super.onCreate()
        avisos.criarCanais()
        // Toda abertura confere o agendamento: se a corrente de avisos quebrou
        // (app forçado a parar, erro no worker), ela volta aqui.
        escopo.launch { agendador.garantirAgendado() }
    }
}
