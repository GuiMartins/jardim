package com.jardim.motor.agenda

/** A partir de quantos dias de atraso o app pergunta se a planta ainda está viva. */
const val DIAS_PARA_PERGUNTAR_SE_ESTA_VIVA = 7

/**
 * Decide se, em vez de continuar cobrando, o app pergunta "essa planta ainda está
 * viva?" e oferece encerrar o plantio. Recebe as pendências de hoje de um
 * plantio, já calculadas e ordenadas pelo Cronograma.
 */
fun devePerguntarSeEstaViva(pendencias: List<Pendencia>): Boolean {
    // TODO(human)
    return false
}
