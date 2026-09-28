package com.jardim.motor.modelo

import kotlinx.serialization.Serializable

// Espelho exato do JSON da skill planta-cultivo-json. Os nomes em camelCase viram
// snake_case na leitura (ver LeitorCultivo): `diaFimCiclo` lê `dia_fim_ciclo`.
// Campo anulável sem valor padrão continua obrigatório: o JSON precisa trazer a
// chave, nem que seja com null, igual ao validar.py da skill.

@Serializable
data class PlantaCultivo(
    val planta: Planta,
    val requisitosIniciais: RequisitosIniciais,
    val fasesCicloVida: List<Fase>,
    val tarefasAgendadas: List<Tarefa>,
    val alertasFase: List<Alerta>,
    val pragasComuns: List<Praga>,
    val eventosSazonais: List<EventoSazonal>,
) {
    val perene: Boolean get() = planta.tipoCiclo == TipoCiclo.PERENE
}

@Serializable
data class Planta(
    val nomeComum: String,
    val nomeCientifico: String,
    val descricao: String,
    val tipoCiclo: TipoCiclo,
    val dificuldade: Dificuldade,
    val formaInicio: FormaInicio,
    /** Último dia do ciclo; null para PERENE. */
    val duracaoCicloDias: Int?,
    val mesesPlantio: List<Int>,
    /** Abaixo disso, em vaso ao ar livre, a planta sofre dano: avisar para abrigar. */
    val temperaturaMinimaC: Int,
    /** Acima disso o vaso seca rápido e a folha queima: avisar para conferir a rega à tarde. */
    val temperaturaMaximaC: Int,
)

@Serializable
data class RequisitosIniciais(
    val tipoVasoOuSolo: String,
    val volumeMinimoLitros: Double,
    val luminosidade: Luminosidade,
    val horasSolMinimas: Double,
    val horasSolDiariasRecomendadas: Double,
    val substratoRecomendado: String,
    val camadaDrenagem: Boolean,
)

@Serializable
data class Fase(
    val faseId: String,
    val nome: String,
    val diaInicio: Int,
    /** null só na última fase de uma PERENE (a vida adulta não termina). */
    val diaFim: Int?,
    val descricao: String,
)

/** Algo recorrente, por idade da planta. */
@Serializable
data class Tarefa(
    val id: String,
    val titulo: String,
    val tipo: TipoTarefa,
    val frequenciaDias: Int,
    val diaInicioCiclo: Int,
    /** null só em PERENE: a tarefa continua para sempre. */
    val diaFimCiclo: Int?,
    val instrucoes: String,
    /** Falhar uma única vez já causa dano difícil de reverter; o app insiste mais. */
    val critica: Boolean,
)

/** Aviso único, por idade da planta. */
@Serializable
data class Alerta(
    val diaDisparo: Int,
    val titulo: String,
    val mensagem: String,
    val acaoRequerida: String,
)

@Serializable
data class Praga(
    val nome: String,
    val tipo: TipoPraga,
    val sintomas: String,
    val prevencaoTratamento: String,
)

/** Aviso por época do ano, não importa quando a planta começou. */
@Serializable
data class EventoSazonal(
    val meses: List<Int>,
    val titulo: String,
    val mensagem: String,
    val acaoRequerida: String,
)

enum class TipoCiclo { ANUAL, BIENAL, PERENE }

enum class Dificuldade { FACIL, MEDIA, DIFICIL }

enum class FormaInicio { SEMENTE, MUDA, ESTACA, BULBO, PLANTA_ADULTA }

enum class Luminosidade { SOL_DIRETO, MEIA_SOMBRA, SOMBRA }

enum class TipoTarefa { REGA, ADUBACAO, DESBASTE, TUTORAGEM, PODA, TRANSPLANTE, PRAGA_CHECK, COLHEITA }

enum class TipoPraga { PRAGA, DOENCA, DISTURBIO }
