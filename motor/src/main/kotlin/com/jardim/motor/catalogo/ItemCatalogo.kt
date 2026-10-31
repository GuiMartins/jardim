package com.jardim.motor.catalogo

import com.jardim.motor.agenda.Receita
import com.jardim.motor.leitura.jsonJardim
import com.jardim.motor.modelo.Dificuldade
import com.jardim.motor.modelo.Luminosidade
import com.jardim.motor.modelo.TipoCiclo
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * Uma planta no índice do catálogo (catalogo/indice.json, gerado no build do
 * app a partir dos JSONs de espécie). Tem só o que a lista e a busca precisam:
 * com 1000 plantas, ler os JSONs inteiros para montar a lista custaria
 * segundos e dezenas de MB.
 */
@Serializable
data class ItemCatalogo(
    val arquivo: String,
    val nomeComum: String,
    val nomeCientifico: String,
    /** Sinônimos populares ("Língua-de-sogra"), vindos do levantamento de plantas. */
    val outrosNomes: List<String> = emptyList(),
    /** HORTALICA, FLOR, SUCULENTA… do levantamento; texto livre, pode faltar. */
    val categoria: String? = null,
    /** Ranking de popularidade no levantamento (1 = mais popular); pode faltar. */
    val posicao: Int? = null,
    val dificuldade: Dificuldade,
    val tipoCiclo: TipoCiclo,
    val duracaoCicloDias: Int?,
    val mesesPlantio: List<Int>,
    // Os requisitos que a descoberta (Descoberta.kt) cruza com o lugar da pessoa.
    // Null só nos testes que não precisam deles: a planta passa em qualquer filtro.
    val luminosidade: Luminosidade? = null,
    val horasSolMinimas: Double? = null,
    val horasSolDiariasRecomendadas: Double? = null,
    val volumeMinimoLitros: Double? = null,
    /** As receitas de substrato de dados/insumos.json; null se a espécie não tem (ou o texto mudou). */
    val substrato: List<Receita>? = null,
    /**
     * Miniatura da espécie no iNaturalist (240 px), de dados/fotos.json. O app
     * baixa quando a linha aparece; null = sem foto boa, a lista mostra o emoji.
     */
    val foto: String? = null,
)

object IndiceCatalogo {
    fun ler(texto: String): List<ItemCatalogo> = jsonJardim.decodeFromString(ListSerializer(ItemCatalogo.serializer()), texto)
}
