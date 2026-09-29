package com.jardim.motor.agenda

import com.jardim.motor.modelo.PlantaCultivo

/**
 * O que juntar antes de plantar, tirado de `requisitos_iniciais` e de
 * `forma_inicio`. O nome vai para o banco (os itens que faltam num
 * planejamento): renomear exige migração.
 */
enum class ItemPreparo { INICIO, VASO, SUBSTRATO, DRENAGEM, LUGAR }

/** Drenagem só entra quando a espécie pede a camada no fundo do vaso. */
fun itensDePreparo(cultivo: PlantaCultivo): List<ItemPreparo> =
    ItemPreparo.entries.filter { it != ItemPreparo.DRENAGEM || cultivo.requisitosIniciais.camadaDrenagem }
