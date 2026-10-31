package com.jardim.motor

import com.jardim.motor.atualizacao.Atualizacao
import com.jardim.motor.atualizacao.ReleasesGitHub
import com.jardim.motor.atualizacao.maisNova
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AtualizacaoTest {

    @Test
    fun `compara número por número`() {
        assertTrue(maisNova("0.3.0", "0.2.0"))
        assertTrue(maisNova("0.10.0", "0.9.1"))
        assertTrue(maisNova("v1.0", "0.9.9"))
        assertFalse(maisNova("0.2.0", "0.2.0"))
        assertFalse(maisNova("0.2", "0.2.0"))
        assertFalse(maisNova("0.1.1", "0.2.0"))
    }

    @Test
    fun `tag que não é versão não oferece atualização`() {
        assertFalse(maisNova("teste", "0.2.0"))
        assertFalse(maisNova("0.3.0-beta", "0.2.0"))
    }

    // Recorte da resposta real de /releases/latest (v0.2.0), com os campos que o app lê.
    private val resposta = """
        {"url":"https://api.github.com/repos/GuiMartins/jardim/releases/1","tag_name":"v0.2.0","name":"Jardim 0.2.0",
         "draft":false,"prerelease":false,"assets":[{"name":"jardim-v0.2.0.apk","size":4104972,
         "browser_download_url":"https://github.com/GuiMartins/jardim/releases/download/v0.2.0/jardim-v0.2.0.apk"}]}
    """.trimIndent()

    @Test
    fun `lê a última release e o APK dela`() {
        assertEquals(
            Atualizacao("0.2.0", "https://github.com/GuiMartins/jardim/releases/download/v0.2.0/jardim-v0.2.0.apk", 4104972),
            ReleasesGitHub.ler(resposta),
        )
    }

    @Test
    fun `release sem APK não serve`() {
        assertNull(ReleasesGitHub.ler("""{"tag_name":"v0.4.0","assets":[]}"""))
    }
}
