package com.jardim.dados

import android.content.res.AssetManager
import android.util.Log
import com.jardim.motor.leitura.LeitorCultivo
import com.jardim.motor.leitura.ResultadoLeitura
import com.jardim.motor.modelo.PlantaCultivo

/** Uma espécie do catálogo, com o texto original, que vira a cópia guardada no plantio. */
data class Especie(val arquivo: String, val json: String, val cultivo: PlantaCultivo)

/** As espécies de app/src/main/assets/catalogo. */
class Catalogo(private val assets: AssetManager) {

    val especies: List<Especie> by lazy {
        assets.list(PASTA).orEmpty().mapNotNull { arquivo ->
            val json = assets.open("$PASTA/$arquivo").bufferedReader().use { it.readText() }
            when (val resultado = LeitorCultivo.ler(json)) {
                is ResultadoLeitura.Ok -> Especie(arquivo, json, resultado.cultivo)
                // O :motor:test já recusa JSON inválido no catálogo; se um passar
                // mesmo assim, ele some da lista em vez de derrubar o app.
                is ResultadoLeitura.Invalido -> {
                    Log.w("Catalogo", "$arquivo ignorado: ${resultado.problemas}")
                    null
                }
            }
        }.sortedBy { it.cultivo.planta.nomeComum }
    }

    fun especie(arquivo: String): Especie? = especies.firstOrNull { it.arquivo == arquivo }

    private companion object {
        const val PASTA = "catalogo"
    }
}
