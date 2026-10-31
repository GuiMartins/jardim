package com.jardim.motor.atualizacao

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Uma versão publicada no GitHub, com o endereço do APK. */
@Serializable
data class Atualizacao(val versao: String, val apk: String, val tamanho: Long)

/**
 * As releases do repositório (público desde 2026-10-30, para o app consultar
 * sem token). O `./dev.sh release` publica uma por versão, com o APK assinado
 * pela chave do PC, a mesma do que está no celular.
 */
object ReleasesGitHub {
    const val ULTIMA = "https://api.github.com/repos/GuiMartins/jardim/releases/latest"

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Release(@SerialName("tag_name") val tag: String, val assets: List<Arquivo> = emptyList())

    @Serializable
    private data class Arquivo(val name: String, @SerialName("browser_download_url") val url: String, val size: Long)

    /** A resposta de /releases/latest; null se a release não tem APK. */
    fun ler(texto: String): Atualizacao? {
        val release = json.decodeFromString(Release.serializer(), texto)
        val apk = release.assets.firstOrNull { it.name.endsWith(".apk") } ?: return null
        return Atualizacao(release.tag.removePrefix("v"), apk.url, apk.size)
    }
}

/**
 * "0.10.0" é mais nova que "0.9.1", número por número. Texto que não é versão
 * nunca é mais novo: uma tag estranha não oferece atualização.
 */
fun maisNova(candidata: String, instalada: String): Boolean {
    val nova = numeros(candidata) ?: return false
    val atual = numeros(instalada) ?: return true
    for (i in 0 until maxOf(nova.size, atual.size)) {
        val n = nova.getOrElse(i) { 0 }
        val a = atual.getOrElse(i) { 0 }
        if (n != a) return n > a
    }
    return false
}

private fun numeros(versao: String): List<Int>? =
    versao.removePrefix("v").split('.').map { it.toIntOrNull() ?: return null }.takeIf { it.isNotEmpty() }
