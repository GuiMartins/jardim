package com.jardim.motor.agenda

import com.jardim.motor.modelo.PlantaCultivo
import java.time.LocalDate

/**
 * Começar fora de `meses_plantio` é permitido; o app só avisa (decisão da
 * etapa 3). Girassol em maio chega ao frio sem ter florescido, mas a escolha é
 * de quem planta.
 */
fun foraDeEpoca(cultivo: PlantaCultivo, data: LocalDate): Boolean =
    data.monthValue !in cultivo.planta.mesesPlantio
