package com.jardim.motor

import com.jardim.motor.catalogo.BuscaCatalogo
import com.jardim.motor.catalogo.IndiceCatalogo
import com.jardim.motor.catalogo.ItemCatalogo
import com.jardim.motor.catalogo.normalizar
import com.jardim.motor.modelo.Dificuldade
import com.jardim.motor.modelo.TipoCiclo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuscaCatalogoTest {

    private fun item(nome: String, cientifico: String, vararg outros: String, posicao: Int? = null) = ItemCatalogo(
        arquivo = normalizar(nome).replace(' ', '-') + ".json",
        nomeComum = nome,
        nomeCientifico = cientifico,
        outrosNomes = outros.toList(),
        posicao = posicao,
        dificuldade = Dificuldade.FACIL,
        tipoCiclo = TipoCiclo.ANUAL,
        duracaoCicloDias = 90,
        mesesPlantio = (1..12).toList(),
    )

    // Posições e sinônimos no formato de dados/plantas-populares.json.
    private val busca = BuscaCatalogo(
        listOf(
            item("Roseira", "Rosa spp.", "Rosa", "Mini-rosa", posicao = 1),
            item("Espada-de-são-jorge", "Dracaena trifasciata", "Sansevieria", "Língua-de-sogra", posicao = 2),
            item("Girassol", "Helianthus annuus", posicao = 3),
            item("Jiboia", "Epipremnum aureum", posicao = 4),
            item("Manjericão", "Ocimum basilicum", "Alfavaca", posicao = 5),
            item("Costela-de-adão", "Monstera deliciosa", posicao = 6),
            item("Samambaia", "Nephrolepis exaltata", posicao = 8),
            item("Alecrim", "Salvia rosmarinus", posicao = 10),
            item("Alecrim-do-campo", "Baccharis dracunculifolia", posicao = 500),
            item("Pitangueira", "Eugenia uniflora", "Pitanga", posicao = 9),
            item("Rosa-do-deserto", "Adenium obesum", posicao = 20),
            item("Pitangatuba", "Eugenia selloi", posicao = 700),
            item("Alface", "Lactuca sativa", posicao = 12),
            item("Lavanda", "Lavandula angustifolia", "Alfazema", posicao = 30),
            item("Rabanete", "Raphanus sativus"), // sem ranking
        ),
    )

    private fun nomes(consulta: String) = busca.buscar(consulta).map { it.item.nomeComum }

    /** O trecho destacado, recortado do texto original (com acento e hífen). */
    private fun recortes(texto: String, faixas: List<IntRange>) = faixas.map { texto.substring(it) }

    @Test
    fun `normalizar tira acento e pontuação sem mudar o tamanho`() {
        assertEquals("espada de sao jorge", normalizar("Espada-de-São-Jorge"))
        assertEquals("Língua-de-sogra".length, normalizar("Língua-de-sogra").length)
    }

    @Test
    fun `busca vazia mostra todas, das mais populares para as sem ranking`() {
        val todas = nomes("")
        assertEquals(15, todas.size)
        assertEquals(listOf("Roseira", "Espada-de-são-jorge", "Girassol"), todas.take(3))
        assertEquals("Rabanete", todas.last())
        assertEquals(todas, nomes("   "))
    }

    @Test
    fun `ignora acento e maiúscula, e destaca no nome original`() {
        val r = busca.buscar("MANJERICAO").single()
        assertEquals("Manjericão", r.item.nomeComum)
        assertEquals(listOf("Manjericão"), recortes(r.item.nomeComum, r.destaquesNome))
    }

    @Test
    fun `acha pelo começo de qualquer palavra, em qualquer ordem`() {
        for (consulta in listOf("jorge", "sao jorge", "jorge espada", "esp de s")) {
            val r = busca.buscar(consulta).first()
            assertEquals("Espada-de-são-jorge", r.item.nomeComum, consulta)
        }
        val r = busca.buscar("sao jorge").first()
        assertEquals(listOf("são", "jorge"), recortes(r.item.nomeComum, r.destaquesNome))
    }

    @Test
    fun `nome que começa com o digitado vem antes, e o empate vai para a mais popular`() {
        assertEquals(listOf("Alecrim", "Alecrim-do-campo"), nomes("ale"))
        // Pelo nome, na ordem de popularidade, e só depois Alecrim pelo científico "rosmarinus".
        assertEquals(listOf("Roseira", "Rosa-do-deserto", "Alecrim"), nomes("ros"))
    }

    @Test
    fun `nome ou sinônimo igual ao digitado vem antes de nome que só começa igual`() {
        assertEquals("Pitangueira", nomes("pitanga").first()) // e não Pitangatuba
        assertEquals("Roseira", nomes("rosa").first()) // e não Rosa-do-deserto
        assertEquals("Pitanga", busca.buscar("pitanga").first().outroNome)
    }

    @Test
    fun `erro de digitação não entra quando já há resultado exato`() {
        // "alface" está a uma letra de "alfaze(ma)", mas a Alface já responde.
        assertEquals(listOf("Alface"), nomes("alface"))
    }

    @Test
    fun `acha pelos outros nomes e diz qual casou`() {
        val r = busca.buscar("lingua de sogra").single()
        assertEquals("Espada-de-são-jorge", r.item.nomeComum)
        assertEquals("Língua-de-sogra", r.outroNome)
        assertEquals(listOf("Língua", "de", "sogra"), recortes(r.outroNome!!, r.destaquesOutroNome))
        assertEquals(listOf("Manjericão"), nomes("alfavaca"))
    }

    @Test
    fun `acha pelo nome científico`() {
        val r = busca.buscar("helianthus").single()
        assertEquals("Girassol", r.item.nomeComum)
        assertEquals(listOf("Helianthus"), recortes(r.item.nomeCientifico, r.destaquesCientifico))
    }

    @Test
    fun `acha no meio da palavra quando nada começa com o digitado`() {
        val r = busca.buscar("sol").single()
        assertEquals("Girassol", r.item.nomeComum)
        assertEquals(listOf("sol"), recortes(r.item.nomeComum, r.destaquesNome))
    }

    @Test
    fun `tolera um erro de digitação`() {
        assertEquals("Girassol", nomes("girasol").first()) // letra faltando
        assertEquals("Alecrim", nomes("alecrin").first()) // letra trocada
        assertEquals("Samambaia", nomes("samanbaia").first())
        assertEquals("Jiboia", nomes("jiboya").first())
        assertEquals("Manjericão", nomes("manjeircao").first()) // duas invertidas
    }

    @Test
    fun `termo curto não aceita erro, senão casaria com tudo`() {
        assertEquals(emptyList(), nomes("xyz"))
        assertEquals(emptyList(), nomes("gir x"))
    }

    @Test
    fun `lê o índice gerado no build, com os campos do levantamento opcionais`() {
        val itens = IndiceCatalogo.ler(
            """
            [
              {"arquivo":"girassol.json","nome_comum":"Girassol","nome_cientifico":"Helianthus annuus",
               "outros_nomes":[],"categoria":"FLOR","posicao":3,"dificuldade":"FACIL","tipo_ciclo":"ANUAL",
               "duracao_ciclo_dias":105,"meses_plantio":[9,10,11,12,1,2]},
              {"arquivo":"rabanete.json","nome_comum":"Rabanete","nome_cientifico":"Raphanus sativus",
               "dificuldade":"FACIL","tipo_ciclo":"ANUAL","duracao_ciclo_dias":35,"meses_plantio":[3,4]}
            ]
            """,
        )
        assertEquals(listOf(3, null), itens.map { it.posicao })
        assertEquals(listOf("FLOR", null), itens.map { it.categoria })
        assertEquals(emptyList(), itens[1].outrosNomes)
    }

    @Test
    fun `mil plantas - cada letra digitada custa bem menos que um quadro de tela`() {
        val prefixos = listOf("Alface", "Couve", "Pimenta", "Lírio", "Orquídea", "Cacto", "Palmeira", "Hera", "Samambaia", "Bromélia")
        val sufixos = listOf("roxa", "do-campo", "da-praia", "anã", "gigante", "chinesa", "de-jardim", "miúda", "rajada", "branca")
        val mil = (0 until 1000).map { i ->
            item("${prefixos[i % 10]}-${sufixos[(i / 10) % 10]} ${i / 100}", "Genus species$i", "Apelido$i", posicao = i + 1)
        }
        val grande = BuscaCatalogo(mil)
        val digitado = "orquidea-gigant"
        val inicio = System.nanoTime()
        repeat(20) { for (n in 1..digitado.length) grande.buscar(digitado.take(n)) }
        val msPorLetra = (System.nanoTime() - inicio) / 1e6 / (20 * digitado.length)
        // Um quadro a 60 Hz tem 16 ms; a margem cobre PC lento e celular.
        assertTrue(msPorLetra < 16, "levou $msPorLetra ms por letra")
    }
}
