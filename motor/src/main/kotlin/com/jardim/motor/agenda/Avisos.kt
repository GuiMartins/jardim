package com.jardim.motor.agenda

/** Os dois avisos do dia (decididos na etapa 1). */
enum class ModoAviso {
    /** De manhã, no horário escolhido: tudo que vence hoje ou está atrasado. */
    RESUMO,

    /** À noite: só as críticas que ainda não foram feitas. */
    NOITE,
}

/** O que entra no aviso, a partir das pendências de um plantio já calculadas pelo Cronograma. */
fun paraAviso(modo: ModoAviso, pendencias: List<Pendencia>): List<Pendencia> = when (modo) {
    ModoAviso.RESUMO -> pendencias
    ModoAviso.NOITE -> pendencias.filter { it.critica }
}
