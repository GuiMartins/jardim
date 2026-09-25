package com.jardim.ui

import com.jardim.motor.agenda.Origem
import com.jardim.motor.agenda.Pendencia
import com.jardim.motor.modelo.Dificuldade
import com.jardim.motor.modelo.Luminosidade
import com.jardim.motor.modelo.Planta
import com.jardim.motor.modelo.TipoPraga
import com.jardim.motor.modelo.TipoTarefa
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Month
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val ptBr: Locale = Locale.forLanguageTag("pt-BR")

/** "Quinta-feira, 24 de setembro" */
fun dataLonga(data: LocalDate): String =
    data.format(DateTimeFormatter.ofPattern("EEEE, d 'de' MMMM", ptBr)).replaceFirstChar { it.uppercase(ptBr) }

/** "24 de setembro de 2026" */
fun dataCompleta(data: LocalDate): String = data.format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ptBr))

/** "25/09" */
fun dataCurta(data: LocalDate): String = data.format(DateTimeFormatter.ofPattern("dd/MM", ptBr))

/** "Amanhã", ou "Sábado, 26/09" */
fun diaDaPrevisao(data: LocalDate, hoje: LocalDate): String =
    if (data == hoje.plusDays(1)) {
        "Amanhã"
    } else {
        data.format(DateTimeFormatter.ofPattern("EEEE, dd/MM", ptBr)).replaceFirstChar { it.uppercase(ptBr) }
    }

/** "24/09 às 22:34" */
fun dataHora(dataHora: LocalDateTime): String = dataHora.format(DateTimeFormatter.ofPattern("dd/MM 'às' HH:mm", ptBr))

fun tipoPraga(tipo: TipoPraga): String = when (tipo) {
    TipoPraga.PRAGA -> "Praga"
    TipoPraga.DOENCA -> "Doença"
    TipoPraga.DISTURBIO -> "Distúrbio"
}

/** [9, 10, 11] → "set, out, nov" */
fun meses(meses: List<Int>): String =
    meses.joinToString(", ") { Month.of(it).getDisplayName(TextStyle.SHORT, ptBr).removeSuffix(".") }

fun atraso(dias: Int): String = when (dias) {
    0 -> "Para hoje"
    1 -> "Atrasada há 1 dia"
    else -> "Atrasada há $dias dias"
}

fun resumo(planta: Planta): String {
    val dificuldade = when (planta.dificuldade) {
        Dificuldade.FACIL -> "Fácil"
        Dificuldade.MEDIA -> "Média"
        Dificuldade.DIFICIL -> "Difícil"
    }
    val ciclo = planta.duracaoCicloDias?.let { "ciclo de $it dias" } ?: "perene"
    return "$dificuldade · $ciclo"
}

fun luminosidade(l: Luminosidade): String = when (l) {
    Luminosidade.SOL_DIRETO -> "Sol direto"
    Luminosidade.MEIA_SOMBRA -> "Meia-sombra"
    Luminosidade.SOMBRA -> "Sombra"
}

/** 6.0 → "6", 4.5 → "4,5" */
fun numero(valor: Double): String =
    if (valor % 1.0 == 0.0) valor.toInt().toString() else valor.toString().replace('.', ',')

fun icone(p: Pendencia): String = icone(p.origem, p.tipo)

fun icone(origem: Origem, tipo: TipoTarefa?): String = when (origem) {
    Origem.ALERTA -> "📣"
    Origem.SAZONAL -> "🗓️"
    Origem.TAREFA -> when (tipo) {
        TipoTarefa.REGA -> "💧"
        TipoTarefa.ADUBACAO -> "🌿"
        TipoTarefa.DESBASTE, TipoTarefa.PODA -> "✂️"
        TipoTarefa.TUTORAGEM -> "🎋"
        TipoTarefa.TRANSPLANTE -> "🪴"
        TipoTarefa.PRAGA_CHECK -> "🔍"
        TipoTarefa.COLHEITA -> "🧺"
        null -> "•"
    }
}

// O DatePicker do Material 3 trabalha em milissegundos UTC à meia-noite.
fun LocalDate.emMillisUtc(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

fun Long.comoDataUtc(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()
