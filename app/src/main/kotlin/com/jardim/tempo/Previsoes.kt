package com.jardim.tempo

import android.annotation.SuppressLint
import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import com.jardim.avisos.Preferencias
import com.jardim.motor.tempo.Cidade
import com.jardim.motor.tempo.OpenMeteo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.math.round

/**
 * A parte do app que sai para a rede: previsão e busca de cidade na Open-Meteo,
 * e a localização aproximada do celular. A previsão baixada fica nas
 * Preferencias; sem rede, vale a última.
 */
class Previsoes(private val context: Context, private val preferencias: Preferencias) {

    private val trava = Mutex()

    /**
     * Baixa a previsão da cidade escolhida se a guardada tem mais de 3 horas:
     * cada abertura do app e cada aviso chamam isto, e a previsão de um dia não
     * muda tanto assim. Sem cidade ou sem rede, não faz nada.
     */
    suspend fun atualizar() = trava.withLock {
        val cidade = preferencias.cidade.first() ?: return@withLock
        val ultima = preferencias.previsaoBaixadaEm()
        if (ultima != null && Duration.between(ultima, Instant.now()) < VALIDADE) return@withLock
        val resposta = runCatching { baixar(OpenMeteo.urlPrevisao(cidade)) }.getOrNull() ?: return@withLock
        if (OpenMeteo.lerPrevisao(resposta).isNotEmpty()) preferencias.guardarPrevisao(resposta, Instant.now())
    }

    suspend fun escolher(cidade: Cidade) {
        preferencias.mudarCidade(cidade)
        atualizar()
    }

    /** Lança IOException sem rede: a tela diz isso em vez de "nenhuma cidade". */
    suspend fun buscar(nome: String): List<Cidade> = OpenMeteo.lerBusca(baixar(OpenMeteo.urlBusca(nome)))

    /**
     * A cidade onde o celular está, pela localização aproximada (a permissão
     * ACCESS_COARSE_LOCATION já foi dada pela tela). null se a localização do
     * aparelho está desligada ou não respondeu.
     */
    @SuppressLint("MissingPermission")
    suspend fun cidadeAqui(): Cidade? {
        val gerente = context.getSystemService(LocationManager::class.java)
        val provedor = listOfNotNull(
            if (Build.VERSION.SDK_INT >= 31) LocationManager.FUSED_PROVIDER else null,
            LocationManager.NETWORK_PROVIDER,
        ).firstOrNull { gerente.isProviderEnabled(it) } ?: return null
        val atual = if (Build.VERSION.SDK_INT >= 30) {
            withTimeoutOrNull(15_000) {
                suspendCancellableCoroutine<Location?> { continuacao ->
                    val cancelar = CancellationSignal()
                    gerente.getCurrentLocation(provedor, cancelar, context.mainExecutor) { continuacao.resume(it) }
                    continuacao.invokeOnCancellation { cancelar.cancel() }
                }
            }
        } else {
            null
        }
        val local = atual ?: gerente.getLastKnownLocation(provedor)
            ?: gerente.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            ?: return null
        // Duas casas decimais (~1 km): o bastante para a previsão, e o app não guarda onde a pessoa está.
        val latitude = round(local.latitude * 100) / 100
        val longitude = round(local.longitude * 100) / 100
        val endereco = withTimeoutOrNull(10_000) { runCatching { endereco(latitude, longitude) }.getOrNull() }
        return Cidade(
            nome = endereco?.locality ?: endereco?.subAdminArea ?: "Sua localização",
            estado = endereco?.adminArea,
            pais = endereco?.countryCode,
            latitude = latitude,
            longitude = longitude,
        )
    }

    private suspend fun endereco(latitude: Double, longitude: Double): Address? {
        if (!Geocoder.isPresent()) return null
        val geocoder = Geocoder(context, Locale("pt", "BR"))
        return if (Build.VERSION.SDK_INT >= 33) {
            suspendCancellableCoroutine { continuacao ->
                geocoder.getFromLocation(latitude, longitude, 1, object : Geocoder.GeocodeListener {
                    override fun onGeocode(enderecos: MutableList<Address>) = continuacao.resume(enderecos.firstOrNull())

                    override fun onError(mensagem: String?) = continuacao.resume(null)
                })
            }
        } else {
            @Suppress("DEPRECATION")
            withContext(Dispatchers.IO) { geocoder.getFromLocation(latitude, longitude, 1)?.firstOrNull() }
        }
    }

    private suspend fun baixar(url: String): String = withContext(Dispatchers.IO) {
        val conexao = URL(url).openConnection() as HttpURLConnection
        try {
            conexao.connectTimeout = 10_000
            conexao.readTimeout = 15_000
            if (conexao.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${conexao.responseCode}")
            conexao.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conexao.disconnect()
        }
    }

    private companion object {
        val VALIDADE: Duration = Duration.ofHours(3)
    }
}
