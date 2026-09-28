package com.jardim.motor.tempo

import com.jardim.motor.agenda.DiaDoTempo
import com.jardim.motor.catalogo.normalizar
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder
import java.time.LocalDate

/** A cidade de onde vem a previsão, escolhida uma vez pelo usuário. */
@Serializable
data class Cidade(
    val nome: String,
    /** Estado, como a busca ou o Android devolvem ("Paraná"); null se não se sabe. */
    val estado: String?,
    /** Código do país (BR); null se não se sabe. */
    val pais: String?,
    val latitude: Double,
    val longitude: Double,
) {
    val descricao: String get() = listOfNotNull(nome, estado?.takeIf { it != nome }).joinToString(", ")
}

/**
 * Previsão e busca de cidade da Open-Meteo (open-meteo.com): de graça para uso
 * não comercial, sem chave e sem conta. O app baixa; aqui só se monta a URL e
 * se lê a resposta, para os testes rodarem sem rede.
 */
object OpenMeteo {

    private val json = Json { ignoreUnknownKeys = true }

    /** Ontem (para a chuva que já caiu), hoje e os 7 dias seguintes da tela da planta. */
    fun urlPrevisao(c: Cidade): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${c.latitude}&longitude=${c.longitude}" +
            "&daily=temperature_2m_min,temperature_2m_max,precipitation_sum,wind_gusts_10m_max" +
            "&timezone=auto&past_days=1&forecast_days=9"

    fun urlBusca(nome: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?name=${URLEncoder.encode(nome.trim(), "UTF-8")}" +
            "&count=8&language=pt&format=json"

    /** Dias sem mínima ou máxima (fim da previsão) ficam de fora; chuva e vento ausentes contam como zero. */
    fun lerPrevisao(texto: String): List<DiaDoTempo> {
        val diario = json.parseToJsonElement(texto).jsonObject["daily"]?.jsonObject ?: return emptyList()
        fun serie(nome: String): List<Double?> =
            (diario[nome] as? JsonArray)?.map { it.jsonPrimitive.doubleOrNull }.orEmpty()
        val datas = diario["time"]?.jsonArray.orEmpty().map { LocalDate.parse(it.jsonPrimitive.content) }
        val minimas = serie("temperature_2m_min")
        val maximas = serie("temperature_2m_max")
        val chuvas = serie("precipitation_sum")
        val rajadas = serie("wind_gusts_10m_max")
        return datas.mapIndexedNotNull { i, data ->
            val minima = minimas.getOrNull(i) ?: return@mapIndexedNotNull null
            val maxima = maximas.getOrNull(i) ?: return@mapIndexedNotNull null
            DiaDoTempo(data, minima, maxima, chuvas.getOrNull(i) ?: 0.0, rajadas.getOrNull(i) ?: 0.0)
        }
    }

    fun lerBusca(texto: String): List<Cidade> {
        val resultados = json.parseToJsonElement(texto).jsonObject["results"] as? JsonArray ?: return emptyList()
        return resultados.mapNotNull { item ->
            val o = item as? JsonObject ?: return@mapNotNull null
            fun texto(campo: String) = o[campo]?.jsonPrimitive?.contentOrNull
            Cidade(
                nome = texto("name") ?: return@mapNotNull null,
                estado = texto("admin1"),
                pais = texto("country_code"),
                latitude = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
                longitude = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: return@mapNotNull null,
            )
        }
    }
}

private val NORTE_NORDESTE = setOf(
    "acre", "amapa", "amazonas", "para", "rondonia", "roraima", "tocantins",
    "alagoas", "bahia", "ceara", "maranhao", "paraiba", "pernambuco", "piaui", "rio grande do norte", "sergipe",
    "ac", "ap", "am", "pa", "ro", "rr", "to", "al", "ba", "ce", "ma", "pb", "pe", "pi", "rn", "se",
)

private val CENTRO_SUL = setOf(
    "parana", "santa catarina", "rio grande do sul", "sao paulo", "rio de janeiro", "minas gerais", "espirito santo",
    "mato grosso", "mato grosso do sul", "goias", "distrito federal",
    "pr", "sc", "rs", "sp", "rj", "mg", "es", "mt", "ms", "go", "df",
)

/**
 * O calendário do catálogo (meses de plantio, avisos do mês) foi feito para o
 * Centro-Sul: Sul, Sudeste e Centro-Oeste, com inverno frio em junho e julho.
 * Decide pelo estado; sem ele, pela latitude, que separa as duas regiões perto
 * de Brasília (15° S).
 */
fun noCentroSul(cidade: Cidade): Boolean {
    if (cidade.pais != null && !cidade.pais.equals("BR", ignoreCase = true)) return false
    val estado = cidade.estado?.let { normalizar(it).replace(Regex("^(estado d[eo] |state of )"), "").trim() }
    return when (estado) {
        in NORTE_NORDESTE -> false
        in CENTRO_SUL -> true
        else -> cidade.latitude < -15.0
    }
}
