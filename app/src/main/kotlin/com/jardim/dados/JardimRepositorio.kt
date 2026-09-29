package com.jardim.dados

import com.jardim.motor.agenda.Cronograma
import com.jardim.motor.agenda.DiaDoTempo
import com.jardim.motor.agenda.ItemPreparo
import com.jardim.motor.agenda.Local
import com.jardim.motor.agenda.Pendencia
import com.jardim.motor.agenda.RegistroAcao
import com.jardim.motor.agenda.StatusAcao
import com.jardim.motor.agenda.Tempo
import com.jardim.motor.leitura.LeitorCultivo
import com.jardim.motor.leitura.ResultadoLeitura
import com.jardim.motor.modelo.FormaInicio
import com.jardim.motor.modelo.PlantaCultivo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime

/** Um plantio ativo com o cultivo já lido da cópia, o histórico no formato do motor e o tempo. */
data class PlantioAtivo(
    val id: Long,
    val apelido: String,
    val dataPlantio: LocalDate,
    val cultivo: PlantaCultivo,
    val registros: List<RegistroAcao>,
    /** null nos plantios de antes da pergunta "onde ela vai ficar?". */
    val local: Local?,
    /** Local e previsão juntos; null se falta um dos dois, e aí não há aviso do tempo. */
    val tempo: Tempo?,
) {
    fun cronograma() = Cronograma(cultivo, dataPlantio, registros, tempo)
}

/** Uma planta que ainda vai ser plantada, como as telas usam. */
data class Planejado(
    val id: Long,
    val arquivo: String,
    val nome: String,
    val formaInicio: FormaInicio,
    val mesesPlantio: List<Int>,
    val planejadoEm: LocalDate,
    val local: Local?,
    val faltam: Set<ItemPreparo>,
    val epocaAvisadaEm: LocalDate?,
)

/**
 * [previsao] é a última previsão da cidade do usuário: entra no fluxo dos
 * plantios, então a tela Hoje, a da planta e os avisos já recebem os avisos
 * do tempo sem saber que ela existe.
 */
class JardimRepositorio(private val dao: JardimDao, previsao: Flow<List<DiaDoTempo>?>) {

    private val trava = Mutex()

    val plantiosAtivos: Flow<List<PlantioAtivo>> =
        combine(dao.plantiosAtivos(), dao.registrosDosAtivos(), previsao) { plantios, registros, dias ->
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
                    local = p.local,
                    tempo = if (p.local != null && dias != null) Tempo(p.local, dias) else null,
                )
            }
        }

    val planejados: Flow<List<Planejado>> = dao.planejados().map { lista ->
        lista.map { p ->
            Planejado(
                id = p.id,
                arquivo = p.especie,
                nome = p.nome,
                formaInicio = p.formaInicio,
                mesesPlantio = p.mesesPlantio.split(',').filter(String::isNotBlank).map(String::toInt),
                planejadoEm = p.planejadoEm,
                local = p.local,
                // Um item que saiu do enum numa versão nova some sem quebrar a leitura.
                faltam = p.faltam.split(',').mapNotNull { nome -> ItemPreparo.entries.firstOrNull { it.name == nome } }.toSet(),
                epocaAvisadaEm = p.epocaAvisadaEm,
            )
        }
    }

    /** [planejadoId] é o planejamento de onde a planta veio: some junto, na mesma transação. */
    suspend fun plantar(especie: Especie, apelido: String, dataPlantio: LocalDate, local: Local, planejadoId: Long? = null): Long {
        val plantio = PlantioEntity(
            especie = especie.arquivo,
            apelido = apelido,
            dataPlantio = dataPlantio,
            cultivoJson = especie.json,
            local = local,
        )
        return if (planejadoId == null) dao.inserirPlantio(plantio) else dao.plantarPlanejado(plantio, planejadoId)
    }

    suspend fun planejar(especie: Especie, faltam: Set<ItemPreparo>, local: Local?, hoje: LocalDate): Long =
        dao.inserirPlanejado(
            PlanejadoEntity(
                especie = especie.arquivo,
                nome = especie.cultivo.planta.nomeComum,
                formaInicio = especie.cultivo.planta.formaInicio,
                mesesPlantio = especie.cultivo.planta.mesesPlantio.joinToString(","),
                planejadoEm = hoje,
                local = local,
                faltam = comoTexto(faltam),
            ),
        )

    suspend fun mudarPreparo(planejadoId: Long, faltam: Set<ItemPreparo>, local: Local?) =
        dao.mudarPreparo(planejadoId, comoTexto(faltam), local)

    suspend fun marcarEpocaAvisada(planejadoId: Long, hoje: LocalDate) = dao.marcarEpocaAvisada(planejadoId, hoje)

    suspend fun desistir(planejadoId: Long) = dao.apagarPlanejado(planejadoId)

    // Na ordem do enum, para o banco não mudar só porque o Set veio em outra ordem.
    private fun comoTexto(itens: Set<ItemPreparo>) = itens.sortedBy { it.ordinal }.joinToString(",") { it.name }

    suspend fun mudarLocal(plantioId: Long, local: Local) = dao.mudarLocal(plantioId, local)

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

    /**
     * Para o botão da notificação, que pode ser tocado horas depois, quando a
     * pendência já foi marcada no app: só registra se ela ainda existe. A trava
     * evita registro duplo quando o botão é tocado duas vezes seguidas.
     */
    suspend fun registrarSePendente(plantioId: Long, chave: String, status: StatusAcao, hoje: LocalDate): Boolean {
        trava.withLock {
            val plantio = plantiosAtivos.first().firstOrNull { it.id == plantioId } ?: return false
            val pendencia = plantio.cronograma().pendenciasEm(hoje).firstOrNull { it.chave == chave } ?: return false
            registrar(plantioId, pendencia, status)
            return true
        }
    }

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
