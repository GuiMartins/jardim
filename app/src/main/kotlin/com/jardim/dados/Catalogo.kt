package com.jardim.dados

import android.content.res.AssetManager
import android.os.SystemClock
import android.util.Log
import com.jardim.motor.catalogo.BuscaCatalogo
import com.jardim.motor.catalogo.IndiceCatalogo
import com.jardim.motor.catalogo.ItemCatalogo
import com.jardim.motor.leitura.LeitorCultivo
import com.jardim.motor.leitura.ResultadoLeitura
import com.jardim.motor.modelo.PlantaCultivo
import java.util.concurrent.ConcurrentHashMap

/** Uma espécie do catálogo, com o texto original, que vira a cópia guardada no plantio. */
data class Especie(val arquivo: String, val json: String, val cultivo: PlantaCultivo)

/**
 * O catálogo de app/src/main/assets/catalogo. A lista e a busca usam só o
 * índice (catalogo/indice.json, gerado no build); o JSON inteiro de uma
 * espécie só é lido quando alguém a escolhe. As duas leituras fazem disco:
 * chame fora da main thread.
 */
class Catalogo(private val assets: AssetManager) {

    val itens: List<ItemCatalogo> by lazy {
        val inicio = SystemClock.elapsedRealtime()
        assets.open("$PASTA/indice.json").bufferedReader().use { IndiceCatalogo.ler(it.readText()) }.also {
            Log.d("Catalogo", "índice: ${it.size} plantas em ${SystemClock.elapsedRealtime() - inicio} ms")
        }
    }

    val busca: BuscaCatalogo by lazy { BuscaCatalogo(itens) }

    private val especies = ConcurrentHashMap<String, Especie>()

    fun especie(arquivo: String): Especie? = especies[arquivo] ?: lerEspecie(arquivo)?.also { especies[arquivo] = it }

    private fun lerEspecie(arquivo: String): Especie? {
        val json = assets.open("$PASTA/$arquivo").bufferedReader().use { it.readText() }
        return when (val resultado = LeitorCultivo.ler(json)) {
            is ResultadoLeitura.Ok -> Especie(arquivo, json, resultado.cultivo)
            // O :motor:test já recusa JSON inválido no catálogo; se um passar
            // mesmo assim, a espécie não abre em vez de derrubar o app.
            is ResultadoLeitura.Invalido -> {
                Log.w("Catalogo", "$arquivo ignorado: ${resultado.problemas}")
                null
            }
        }
    }

    private companion object {
        const val PASTA = "catalogo"
    }
}
