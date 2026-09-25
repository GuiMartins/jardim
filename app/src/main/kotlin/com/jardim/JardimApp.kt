package com.jardim

import android.app.Application
import com.jardim.dados.Catalogo
import com.jardim.dados.JardimDatabase
import com.jardim.dados.JardimRepositorio

/** Injeção de dependência manual: as poucas peças do app moram aqui. */
class JardimApp : Application() {
    val catalogo by lazy { Catalogo(assets) }
    val repositorio by lazy { JardimRepositorio(JardimDatabase.criar(this).dao()) }
}
