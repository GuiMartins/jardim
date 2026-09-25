package com.jardim.motor.agenda

import com.jardim.motor.modelo.TipoTarefa

/** A partir de quantos dias de atraso o app pergunta se a planta ainda está viva. */
const val DIAS_PARA_PERGUNTAR_SE_ESTA_VIVA = 7

/**
 * Decide se, em vez de continuar cobrando, o app pergunta "essa planta ainda está
 * viva?" e oferece encerrar o plantio. Recebe as pendências de hoje de um
 * plantio, já calculadas pelo Cronograma.
 *
 * Só rega conta, crítica ou não: uma semana sem regar é o único atraso que
 * indica planta morta ou abandonada. Inspeção, alerta ou evento sazonal
 * ignorados indicam só um aviso não lido.
 */
fun devePerguntarSeEstaViva(pendencias: List<Pendencia>): Boolean =
    pendencias.any { it.tipo == TipoTarefa.REGA && it.diasDeAtraso >= DIAS_PARA_PERGUNTAR_SE_ESTA_VIVA }
