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

/**
 * Meses em que uma época de plantio começa: estão em `meses_plantio` e o mês
 * anterior não. O girassol (set a fev) tem um só, setembro; quem planta o ano
 * todo não tem nenhum.
 */
fun mesesDeInicio(mesesPlantio: List<Int>): Set<Int> =
    mesesPlantio.filter { (if (it == 1) 12 else it - 1) !in mesesPlantio }.toSet()

/** O dia 1 do próximo começo de época, depois de [data]; null se a época é o ano todo. */
fun proximoInicioDaEpoca(mesesPlantio: List<Int>, data: LocalDate): LocalDate? {
    val inicios = mesesDeInicio(mesesPlantio)
    return generateSequence(data.withDayOfMonth(1).plusMonths(1)) { it.plusMonths(1) }
        .take(12)
        .firstOrNull { it.monthValue in inicios }
}

/**
 * O começo de época mais recente que caiu depois de [planejadoEm] e até
 * [hoje]. Planejar dentro da época não conta: quem planejou já sabia.
 */
fun inicioDaEpocaDesde(mesesPlantio: List<Int>, planejadoEm: LocalDate, hoje: LocalDate): LocalDate? {
    val inicios = mesesDeInicio(mesesPlantio)
    return generateSequence(hoje.withDayOfMonth(1)) { it.minusMonths(1) }
        .take(12)
        .firstOrNull { it.monthValue in inicios }
        ?.takeIf { it > planejadoEm }
}

/**
 * Uma planta planejada avisa uma vez por começo de época. Se o celular passou o
 * dia 1 desligado, o aviso sai no primeiro resumo depois, e não repete: vale o
 * [avisadaEm] gravado.
 */
fun avisarEpoca(mesesPlantio: List<Int>, planejadoEm: LocalDate, avisadaEm: LocalDate?, hoje: LocalDate): Boolean {
    val inicio = inicioDaEpocaDesde(mesesPlantio, planejadoEm, hoje) ?: return false
    return avisadaEm == null || avisadaEm < inicio
}
