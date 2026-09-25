package com.jardim.motor.agenda

import com.jardim.motor.modelo.TipoTarefa
import java.time.LocalDate
import java.time.LocalDateTime

/** De qual bloco do JSON da planta a pendência veio. */
enum class Origem { TAREFA, ALERTA, SAZONAL }

/**
 * Algo a fazer hoje ou já atrasado: vira uma linha na tela "Hoje" e entra no
 * resumo da notificação.
 */
data class Pendencia(
    /** Identifica a série no histórico; é o que o RegistroAcao guarda. */
    val chave: String,
    val origem: Origem,
    val titulo: String,
    /** O que fazer. */
    val instrucoes: String,
    /** Por que fazer; só alertas e eventos sazonais trazem. */
    val contexto: String?,
    /** Só TAREFA tem tipo. */
    val tipo: TipoTarefa?,
    val critica: Boolean,
    val venceEm: LocalDate,
    val diasDeAtraso: Int,
)

enum class StatusAcao { CONCLUIDA, PULADA }

/**
 * Uma linha do histórico: o usuário fez, ou pulou de propósito ("choveu, não
 * precisa regar"), uma pendência. O motor só lê; quem grava é a camada de dados.
 */
data class RegistroAcao(
    val chave: String,
    val venceEm: LocalDate,
    val realizadoEm: LocalDateTime,
    val status: StatusAcao,
)
