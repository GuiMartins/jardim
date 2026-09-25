package com.jardim.motor.agenda

import com.jardim.motor.modelo.PlantaCultivo
import java.time.LocalDate

/**
 * Começar fora de `meses_plantio` é permitido; o app só avisa (decisão da
 * etapa 3). Girassol em maio chega ao frio sem ter florescido, mas a escolha é
 * de quem planta.
 */
fun foraDeEpoca(cultivo: PlantaCultivo, data: LocalDate): Boolean = foraDeEpoca(cultivo.planta.mesesPlantio, data)

/** A mesma regra a partir só dos meses, para a lista do catálogo, que não lê o JSON inteiro. */
fun foraDeEpoca(mesesPlantio: List<Int>, data: LocalDate): Boolean = data.monthValue !in mesesPlantio
