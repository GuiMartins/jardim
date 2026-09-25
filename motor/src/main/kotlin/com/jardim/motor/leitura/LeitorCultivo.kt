package com.jardim.motor.leitura

import com.jardim.motor.modelo.PlantaCultivo
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNamingStrategy

sealed interface ResultadoLeitura {
    data class Ok(val cultivo: PlantaCultivo) : ResultadoLeitura
    data class Invalido(val problemas: List<String>) : ResultadoLeitura
}

/** O JSON do projeto: snake_case, como a skill escreve, e campo desconhecido é erro. */
@OptIn(ExperimentalSerializationApi::class)
internal val jsonJardim = Json {
    namingStrategy = JsonNamingStrategy.SnakeCase
    // Já é o padrão, mas fica explícito porque é o que faz "campo fora do
    // esquema" virar erro em vez de ser ignorado em silêncio.
    ignoreUnknownKeys = false
}

/**
 * Porta de entrada do JSON da planta: lê, confere a estrutura e as regras da
 * linha do tempo. Nada chega no Cronograma sem passar por aqui.
 */
object LeitorCultivo {

    // A skill responde com o JSON dentro de um bloco ```json; colar a resposta
    // inteira tem que funcionar.
    private val cerca = Regex("^```(?:json)?\\s*(.*?)\\s*```$", RegexOption.DOT_MATCHES_ALL)

    fun ler(texto: String): ResultadoLeitura {
        val limpo = texto.removePrefix("﻿").trim()
        val corpo = cerca.matchEntire(limpo)?.groupValues?.get(1) ?: limpo
        val cultivo = try {
            jsonJardim.decodeFromString<PlantaCultivo>(corpo)
        } catch (e: IllegalArgumentException) {
            // SerializationException é filha de IllegalArgumentException: pega
            // JSON malformado, campo ausente/sobrando e enum fora da lista.
            return ResultadoLeitura.Invalido(listOf(e.message ?: "JSON ilegível"))
        }
        val problemas = ValidadorCultivo.validar(cultivo)
        return if (problemas.isEmpty()) ResultadoLeitura.Ok(cultivo) else ResultadoLeitura.Invalido(problemas)
    }
}
