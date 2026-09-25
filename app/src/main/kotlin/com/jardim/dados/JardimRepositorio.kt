package com.jardim.dados

import com.jardim.motor.agenda.Cronograma
import com.jardim.motor.agenda.Pendencia
import com.jardim.motor.agenda.RegistroAcao
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.leitura.LeitorCultivo
import com.jardim.motor.leitura.ResultadoLeitura
import com.jardim.motor.modelo.PlantaCultivo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.LocalDateTime

/** Um plantio ativo com o cultivo já lido da cópia e o histórico no formato do motor. */
data class PlantioAtivo(
    val id: Long,
    val apelido: String,
    val dataPlantio: LocalDate,
    val cultivo: PlantaCultivo,
    val registros: List<RegistroAcao>,
) {
    fun cronograma() = Cronograma(cultivo, dataPlantio, registros)
}

class JardimRepositorio(private val dao: JardimDao) {

    val plantiosAtivos: Flow<List<PlantioAtivo>> =
        combine(dao.plantiosAtivos(), dao.registrosDosAtivos()) { plantios, registros ->
            val porPlantio = registros.groupBy { it.plantioId }
            plantios.map { p ->
                PlantioAtivo(
                    id = p.id,
                    apelido = p.apelido,
                    dataPlantio = p.dataPlantio,
                    cultivo = lerCopia(p.cultivoJson),
                    registros = porPlantio[p.id].orEmpty().map {
                        RegistroAcao(it.chave, it.venceEm, it.realizadoEm, it.status)
                    },
                )
            }
        }

    suspend fun plantar(especie: Especie, apelido: String, dataPlantio: LocalDate): Long =
        dao.inserirPlantio(
            PlantioEntity(
                especie = especie.arquivo,
                apelido = apelido,
                dataPlantio = dataPlantio,
                cultivoJson = especie.json,
            ),
        )

    suspend fun registrar(plantioId: Long, pendencia: Pendencia, status: StatusAcao): Long =
        dao.inserirRegistro(
            RegistroAcaoEntity(
                plantioId = plantioId,
                chave = pendencia.chave,
                venceEm = pendencia.venceEm,
                realizadoEm = LocalDateTime.now(),
                status = status,
            ),
        )

    suspend fun desfazer(registroId: Long) = dao.apagarRegistro(registroId)

    /** "Ressemeei": hoje vira o dia 1. O histórico fica no banco; o Cronograma ignora o de antes. */
    suspend fun recomecar(plantioId: Long, hoje: LocalDate) = dao.mudarDataPlantio(plantioId, hoje)

    suspend fun encerrar(plantioId: Long, hoje: LocalDate) = dao.encerrar(plantioId, hoje)

    // A cópia passou no LeitorCultivo no dia do plantio. Se falhar agora, o
    // validador ficou mais rígido numa versão nova do app: melhor quebrar alto
    // e ajustar do que esconder a planta.
    private fun lerCopia(json: String): PlantaCultivo =
        when (val resultado = LeitorCultivo.ler(json)) {
            is ResultadoLeitura.Ok -> resultado.cultivo
            is ResultadoLeitura.Invalido -> error("cópia do JSON de um plantio ficou inválida: ${resultado.problemas}")
        }
}
