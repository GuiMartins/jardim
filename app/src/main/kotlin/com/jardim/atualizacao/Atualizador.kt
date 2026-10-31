package com.jardim.atualizacao

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.jardim.avisos.Preferencias
import com.jardim.motor.atualizacao.Atualizacao
import com.jardim.motor.atualizacao.ReleasesGitHub
import com.jardim.motor.atualizacao.maisNova
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

/** Onde está a instalação de uma versão nova. */
sealed interface EstadoInstalacao {
    data object Parado : EstadoInstalacao

    /** O Android só deixa instalar depois de a pessoa liberar o Jardim como fonte, uma vez. */
    data object PrecisaPermissao : EstadoInstalacao

    data class Baixando(val fracao: Float) : EstadoInstalacao

    data object Instalando : EstadoInstalacao

    data class Erro(val mensagem: String) : EstadoInstalacao
}

/**
 * Atualiza o app pelas releases do GitHub: consulta a última (no máximo a cada
 * 6 h), e, quando a pessoa toca em "Atualizar", baixa o APK direto para uma
 * sessão do PackageInstaller. A resposta do Android chega no InstalacaoReceiver.
 */
class Atualizador(private val context: Context, private val preferencias: Preferencias) {

    val versaoInstalada: String = context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** A versão nova, se a última consultada for mais nova que a instalada. */
    val disponivel: Flow<Atualizacao?> = preferencias.atualizacao.map { it?.takeIf { a -> maisNova(a.versao, versaoInstalada) } }

    private val _estado = MutableStateFlow<EstadoInstalacao>(EstadoInstalacao.Parado)
    val estado: StateFlow<EstadoInstalacao> = _estado

    private val trava = Mutex()

    /**
     * [forcar] ignora o intervalo de 6 h (a linha da versão, em Configurações).
     * Devolve a versão nova, se houver; sem rede, fica valendo a última consulta.
     */
    suspend fun verificar(forcar: Boolean = false): Atualizacao? = trava.withLock {
        val ultima = preferencias.atualizacaoConsultadaEm()
        if (forcar || ultima == null || Duration.between(ultima, Instant.now()) >= INTERVALO) {
            try {
                val resposta = withContext(Dispatchers.IO) { baixarTexto(ReleasesGitHub.ULTIMA) }
                preferencias.guardarAtualizacao(ReleasesGitHub.ler(resposta), Instant.now())
            } catch (e: Exception) {
                Log.w("Atualizador", "não deu para consultar a release", e)
            }
        }
        disponivel.first()
    }

    suspend fun instalar(atualizacao: Atualizacao) {
        if (_estado.value is EstadoInstalacao.Baixando || _estado.value == EstadoInstalacao.Instalando) return
        if (!context.packageManager.canRequestPackageInstalls()) {
            _estado.value = EstadoInstalacao.PrecisaPermissao
            return
        }
        _estado.value = EstadoInstalacao.Baixando(0f)
        try {
            withContext(Dispatchers.IO) { baixarParaSessao(atualizacao) }
            _estado.value = EstadoInstalacao.Instalando
        } catch (e: IOException) {
            Log.w("Atualizador", "download falhou", e)
            _estado.value = EstadoInstalacao.Erro("Não deu para baixar a versão ${atualizacao.versao}. Confira a internet e tente de novo.")
        }
    }

    /** O que o InstalacaoReceiver ouviu do Android quando a instalação não foi até o fim. */
    fun falhou(status: Int, mensagem: String?) {
        Log.w("Atualizador", "instalação: status $status, $mensagem")
        _estado.value = when (status) {
            // A pessoa tocou em "Cancelar" na confirmação: volta ao botão, sem erro.
            PackageInstaller.STATUS_FAILURE_ABORTED -> EstadoInstalacao.Parado
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                EstadoInstalacao.Erro("A versão nova foi assinada em outro computador e não instala por cima desta.")
            PackageInstaller.STATUS_FAILURE_STORAGE -> EstadoInstalacao.Erro("Falta espaço no celular para instalar a versão nova.")
            else -> EstadoInstalacao.Erro("O Android não instalou a versão nova${mensagem?.let { " ($it)" } ?: ""}.")
        }
    }

    fun limparErro() {
        if (_estado.value is EstadoInstalacao.Erro || _estado.value == EstadoInstalacao.PrecisaPermissao) {
            _estado.value = EstadoInstalacao.Parado
        }
    }

    private fun baixarParaSessao(atualizacao: Atualizacao) {
        val instalador = context.packageManager.packageInstaller
        val parametros = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            // Android 12+: quem instalou a versão atual pode atualizar sem pedir confirmação.
            // Na primeira vez o instalador foi o adb, e o Android pergunta assim mesmo.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = instalador.createSession(parametros)
        try {
            instalador.openSession(id).use { sessao ->
                val conexao = abrir(atualizacao.apk)
                try {
                    val total = conexao.contentLengthLong.takeIf { it > 0 } ?: atualizacao.tamanho
                    sessao.openWrite("jardim.apk", 0, total).use { saida ->
                        conexao.inputStream.use { entrada ->
                            val buffer = ByteArray(64 * 1024)
                            var lidos = 0L
                            while (true) {
                                val n = entrada.read(buffer)
                                if (n < 0) break
                                saida.write(buffer, 0, n)
                                lidos += n
                                _estado.value = EstadoInstalacao.Baixando((lidos.toFloat() / total).coerceAtMost(1f))
                            }
                        }
                        sessao.fsync(saida)
                    }
                } finally {
                    conexao.disconnect()
                }
                // Mutável: o Android põe o status e a tela de confirmação nos extras.
                val resposta = PendingIntent.getBroadcast(
                    context,
                    id,
                    Intent(context, InstalacaoReceiver::class.java).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                sessao.commit(resposta.intentSender)
            }
        } catch (e: IOException) {
            instalador.abandonSession(id)
            throw e
        }
    }

    private fun abrir(url: String, aceita: String? = null): HttpURLConnection {
        // O GitHub redireciona o download para outro domínio, também https: o HttpURLConnection segue.
        val conexao = URL(url).openConnection() as HttpURLConnection
        conexao.connectTimeout = 10_000
        conexao.readTimeout = 30_000
        aceita?.let { conexao.setRequestProperty("Accept", it) }
        if (conexao.responseCode != HttpURLConnection.HTTP_OK) {
            conexao.disconnect()
            throw IOException("HTTP ${conexao.responseCode} em $url")
        }
        return conexao
    }

    private fun baixarTexto(url: String): String {
        val conexao = abrir(url, aceita = "application/vnd.github+json")
        try {
            return conexao.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conexao.disconnect()
        }
    }

    private companion object {
        val INTERVALO: Duration = Duration.ofHours(6)
    }
}
