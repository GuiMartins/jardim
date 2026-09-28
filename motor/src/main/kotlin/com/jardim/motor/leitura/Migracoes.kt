package com.jardim.motor.leitura

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Cada plantio guarda a cópia do JSON da espécie do dia em que foi plantado, e
 * o LeitorCultivo valida essa cópia a cada leitura. Quando o formato ganha um
 * campo obrigatório, a migração do banco completa as cópias antigas por aqui.
 */
object Migracoes {

    /** Para a espécie que saiu do catálogo depois do plantio: a mediana das 627 do catálogo. */
    const val MINIMA_PADRAO = 5
    const val MAXIMA_PADRAO = 30

    /**
     * Acrescenta temperatura_minima_c e temperatura_maxima_c (2026-09-27),
     * tirados do JSON atual da espécie no catálogo ([doCatalogo], null se ela
     * saiu). São dados da espécie, não da agenda que a cópia existe para
     * congelar. Cópia que já tem os campos volta igual.
     */
    fun comTemperaturas(copia: String, doCatalogo: String?): String {
        val raiz = Json.parseToJsonElement(copia.removePrefix("﻿")).jsonObject
        val planta = raiz.getValue("planta").jsonObject
        if ("temperatura_minima_c" in planta && "temperatura_maxima_c" in planta) return copia
        val fonte = doCatalogo?.let {
            runCatching { Json.parseToJsonElement(it.removePrefix("﻿")).jsonObject.getValue("planta").jsonObject }.getOrNull()
        }
        val completa = JsonObject(
            planta + mapOf(
                "temperatura_minima_c" to (fonte?.get("temperatura_minima_c") ?: JsonPrimitive(MINIMA_PADRAO)),
                "temperatura_maxima_c" to (fonte?.get("temperatura_maxima_c") ?: JsonPrimitive(MAXIMA_PADRAO)),
            ),
        )
        return JsonObject(raiz + ("planta" to completa)).toString()
    }
}
