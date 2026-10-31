package com.jardim

import android.app.Application
import com.jardim.avisos.Agendador
import com.jardim.avisos.Avisos
import com.jardim.avisos.Preferencias
import com.jardim.dados.Catalogo
import com.jardim.dados.Despensa
import com.jardim.dados.JardimDatabase
import com.jardim.dados.JardimRepositorio
import com.jardim.dados.Miniaturas
import com.jardim.tempo.Previsoes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/** Injeção de dependência manual: as poucas peças do app moram aqui. */
class JardimApp : Application() {
    /** Para trabalho que não pertence a uma tela (receiver da notificação, agendamento). */
    val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val catalogo by lazy { Catalogo(assets) }
    val miniaturas by lazy { Miniaturas(File(filesDir, "miniaturas")) }
    val preferencias by lazy { Preferencias(this) }
    val repositorio by lazy { JardimRepositorio(JardimDatabase.criar(this).dao(), preferencias.previsao) }
    val previsoes by lazy { Previsoes(this, preferencias) }
    val avisos by lazy { Avisos(this) }
    val agendador by lazy { Agendador(this, preferencias) }
    val despensa by lazy { Despensa(preferencias, repositorio, catalogo) }

    override fun onCreate() {
        super.onCreate()
        avisos.criarCanais()
        // Toda abertura confere o agendamento: se a corrente de avisos quebrou
        // (app forçado a parar, erro no worker), ela volta aqui.
        escopo.launch { agendador.garantirAgendado() }
        // Deixa o índice do catálogo pronto antes de alguém tocar em "Nova planta":
        // com 1000 plantas ele leva uma fração de segundo, e a busca abre na hora.
        escopo.launch(Dispatchers.IO) { catalogo.busca }
        escopo.launch { previsoes.atualizar() }
    }
}
