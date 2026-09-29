package com.jardim.dados

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * As fotos da busca do catálogo. Não vêm no APK: cada uma é baixada do
 * iNaturalist (240 px, ~33 KB) na primeira vez que a linha aparece e fica
 * guardada em files/miniaturas, recortada em 160 px (~5 KB). Sem internet, só
 * aparecem as que já foram vistas.
 */
class Miniaturas(private val pasta: File) {

    private val memoria = LruCache<String, ImageBitmap>(NA_MEMORIA)

    // Poucas de cada vez: digitando rápido, as linhas que já saíram da tela
    // desistem enquanto esperam (a espera é cancelável) e não atrasam as novas.
    private val rede = Semaphore(4)

    // Sem rede, cada linha falharia de novo; depois de uma falha de conexão,
    // ninguém tenta por um tempo e as linhas mostram o emoji na hora.
    @Volatile private var semRedeDesde = 0L

    /** Na memória: a linha já nasce com a foto, sem piscar, ao voltar a rolagem. */
    fun naMemoria(url: String): ImageBitmap? = memoria.get(url)

    /** A foto de [url], do disco ou da rede. null = não deu (sem rede ou foto sumiu). */
    suspend fun carregar(url: String): ImageBitmap? = memoria.get(url) ?: withContext(Dispatchers.IO) {
        val arquivo = File(pasta, nomeDoArquivo(url))
        val bitmap = lerDoDisco(arquivo) ?: baixar(url, arquivo)
        bitmap?.asImageBitmap()?.also { memoria.put(url, it) }
    }

    private fun lerDoDisco(arquivo: File): Bitmap? = if (arquivo.exists()) BitmapFactory.decodeFile(arquivo.path) else null

    private suspend fun baixar(url: String, arquivo: File): Bitmap? {
        if (SystemClock.elapsedRealtime() - semRedeDesde < ESPERA_SEM_REDE) return null
        return rede.withPermit {
            // Outra linha com a mesma foto pode ter baixado enquanto esta esperava.
            lerDoDisco(arquivo) ?: runCatching { ler(url) }
                .onFailure { if (it is IOException && it !is ErroHttp) semRedeDesde = SystemClock.elapsedRealtime() }
                .getOrNull()
                ?.let(::quadrado)
                ?.also { gravar(it, arquivo) }
        }
    }

    private fun ler(url: String): Bitmap? {
        val conexao = URL(url).openConnection() as HttpURLConnection
        try {
            conexao.connectTimeout = 5_000
            conexao.readTimeout = 10_000
            if (conexao.responseCode != HttpURLConnection.HTTP_OK) throw ErroHttp(conexao.responseCode)
            return conexao.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conexao.disconnect()
        }
    }

    /** O centro da foto, em LADO × LADO: é o que cabe no quadrado da lista. */
    private fun quadrado(foto: Bitmap): Bitmap {
        val menor = minOf(foto.width, foto.height)
        val centro = Bitmap.createBitmap(foto, (foto.width - menor) / 2, (foto.height - menor) / 2, menor, menor)
        return Bitmap.createScaledBitmap(centro, LADO, LADO, true)
    }

    private fun gravar(bitmap: Bitmap, arquivo: File) {
        pasta.mkdirs()
        // Grava ao lado e renomeia: um arquivo pela metade nunca é lido como foto.
        val parcial = File(pasta, arquivo.name + ".parcial")
        val ok = runCatching { parcial.outputStream().use { bitmap.compress(WEBP, QUALIDADE, it) } }.getOrDefault(false)
        if (ok) parcial.renameTo(arquivo) else parcial.delete()
    }

    private class ErroHttp(codigo: Int) : IOException("HTTP $codigo")

    private companion object {
        const val LADO = 160 // ~56 dp na densidade do Fold
        const val QUALIDADE = 75
        const val NA_MEMORIA = 150 // 100 KB cada: ~15 MB, umas três telas de busca
        const val ESPERA_SEM_REDE = 30_000L

        @Suppress("DEPRECATION")
        val WEBP = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

        /** .../photos/62849647/small.jpeg -> 62849647.webp */
        fun nomeDoArquivo(url: String): String {
            val id = url.substringBeforeLast('/').substringAfterLast('/')
            return (if (id.all(Char::isDigit) && id.isNotEmpty()) id else url.hashCode().toUInt().toString()) + ".webp"
        }
    }
}
