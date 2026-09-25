package com.jardim.motor.catalogo

import java.text.Normalizer

/** Uma planta encontrada e os trechos que casaram, para destacar na tela. */
data class ResultadoBusca(
    val item: ItemCatalogo,
    /** Faixas de [ItemCatalogo.nomeComum] que casaram com a busca. */
    val destaquesNome: List<IntRange> = emptyList(),
    /** Quando casou por um sinônimo: qual deles e onde. */
    val outroNome: String? = null,
    val destaquesOutroNome: List<IntRange> = emptyList(),
    val destaquesCientifico: List<IntRange> = emptyList(),
)

/**
 * Busca de autocomplete sobre o índice do catálogo, pensada para rodar a cada
 * letra digitada. Com 1000 plantas uma varredura linear leva menos de um
 * milissegundo, então não há árvore de prefixos: o que importa é a ordem.
 *
 * Cada palavra digitada precisa ser o começo de uma palavra do nome, em
 * qualquer ordem, sem ligar para acento ou maiúscula ("sao jorge" acha
 * "Espada-de-são-jorge"). A ordem dos resultados, do melhor para o pior:
 *  0. o nome ou um dos outros nomes é igual ao digitado ("pitanga" → Pitangueira,
 *     que também se chama Pitanga, antes de Pitangatuba);
 *  1. o nome começa com o que foi digitado;
 *  2. a primeira palavra digitada é o começo do nome;
 *  3. casou com palavras do meio do nome;
 *  4. casou com um dos outros nomes;
 *  5. casou com o nome científico;
 *  6. o texto aparece no meio de uma palavra do nome ("sol" em "Girassol");
 *  7. casou com um erro de digitação ("girasol"), só quando nada acima achou.
 * Dentro do mesmo nível, ganha a mais popular; sem ranking, a de nome mais curto.
 */
class BuscaCatalogo(itens: List<ItemCatalogo>) {

    private class Palavra(val inicio: Int, val texto: String)

    private class Campo(val original: String) {
        val normalizado = normalizar(original)
        val palavras = palavrasDe(normalizado)

        /** As palavras separadas por um espaço só, para comparar com o digitado inteiro. */
        val compacto = palavras.joinToString(" ") { it.texto }
    }

    private class Entrada(val item: ItemCatalogo) {
        val nome = Campo(item.nomeComum)
        val cientifico = Campo(item.nomeCientifico)
        val outros = item.outrosNomes.map(::Campo)
    }

    private class Achado(val entrada: Entrada, val nivel: Int, val resultado: ResultadoBusca)

    private val entradas = itens.map(::Entrada)

    private val popularidade: Comparator<Entrada> =
        compareBy<Entrada, Int?>(nullsLast()) { it.item.posicao }
            .thenBy { it.nome.normalizado.length }
            .thenBy { it.nome.normalizado }

    private val todas = entradas.sortedWith(popularidade).map { ResultadoBusca(it.item) }

    val total: Int get() = entradas.size

    fun buscar(consulta: String): List<ResultadoBusca> {
        val termos = normalizar(consulta).split(' ').filter { it.isNotEmpty() }
        if (termos.isEmpty()) return todas
        val junta = termos.joinToString(" ")

        val estritos = entradas.mapNotNull { casarEstrito(it, termos, junta) }
        // Erro de digitação só entra quando nada casou exato: com resultado exato na
        // tela, "alface" trazendo Alfazema por um erro de uma letra só atrapalha.
        val achados = if (estritos.isNotEmpty() || junta.length < MINIMO_PARA_ERRO) {
            estritos
        } else {
            entradas.mapNotNull { casarComErro(it, termos) }
        }
        return achados
            .sortedWith(compareBy<Achado> { it.nivel }.thenBy(popularidade) { it.entrada })
            .map { it.resultado }
    }

    private fun casarEstrito(e: Entrada, termos: List<String>, junta: String): Achado? {
        val faixasNome = comecosDePalavra(e.nome, termos)
        if (faixasNome != null && e.nome.compacto == junta) {
            return Achado(e, IGUAL, ResultadoBusca(e.item, destaquesNome = faixasNome))
        }
        e.outros.firstOrNull { it.compacto == junta }?.let { outro ->
            val faixas = comecosDePalavra(outro, termos).orEmpty()
            return Achado(e, IGUAL, ResultadoBusca(e.item, outroNome = outro.original, destaquesOutroNome = faixas))
        }
        faixasNome?.let { faixas ->
            val nivel = when {
                e.nome.normalizado.startsWith(junta) -> NOME_COMECA
                faixas.first().first == 0 -> PRIMEIRA_PALAVRA
                else -> PALAVRA_DO_MEIO
            }
            return Achado(e, nivel, ResultadoBusca(e.item, destaquesNome = faixas))
        }
        for (outro in e.outros) {
            comecosDePalavra(outro, termos)?.let { faixas ->
                return Achado(e, OUTRO_NOME, ResultadoBusca(e.item, outroNome = outro.original, destaquesOutroNome = faixas))
            }
        }
        comecosDePalavra(e.cientifico, termos)?.let { faixas ->
            return Achado(e, CIENTIFICO, ResultadoBusca(e.item, destaquesCientifico = faixas))
        }
        val meio = e.nome.normalizado.indexOf(junta)
        if (meio >= 0) {
            return Achado(e, NO_MEIO, ResultadoBusca(e.item, destaquesNome = listOf(meio until meio + junta.length)))
        }
        return null
    }

    private fun casarComErro(e: Entrada, termos: List<String>): Achado? {
        comecosAproximados(e.nome, termos)?.let { faixas ->
            return Achado(e, COM_ERRO, ResultadoBusca(e.item, destaquesNome = faixas))
        }
        for (outro in e.outros) {
            comecosAproximados(outro, termos)?.let { faixas ->
                return Achado(e, COM_ERRO, ResultadoBusca(e.item, outroNome = outro.original, destaquesOutroNome = faixas))
            }
        }
        return null
    }

    /** Cada termo é o começo de uma palavra diferente do campo; devolve as faixas ou null. */
    private fun comecosDePalavra(campo: Campo, termos: List<String>): List<IntRange>? {
        val usadas = HashSet<Palavra>()
        val faixas = termos.map { termo ->
            val palavra = campo.palavras.firstOrNull { it !in usadas && it.texto.startsWith(termo) } ?: return null
            usadas += palavra
            palavra.inicio until palavra.inicio + termo.length
        }
        return faixas.sortedBy { it.first }
    }

    /** Como [comecosDePalavra], mas cada termo pode ter um erro de digitação. */
    private fun comecosAproximados(campo: Campo, termos: List<String>): List<IntRange>? {
        val usadas = HashSet<Palavra>()
        val faixas = termos.map { termo ->
            var casou: IntRange? = null
            for (palavra in campo.palavras) {
                if (palavra in usadas) continue
                val tamanho = comecoAproximado(termo, palavra.texto) ?: continue
                usadas += palavra
                casou = palavra.inicio until palavra.inicio + tamanho
                break
            }
            casou ?: return null
        }
        return faixas.sortedBy { it.first }
    }

    private companion object {
        // Níveis, do melhor para o pior (ver a documentação da classe).
        const val IGUAL = 0
        const val NOME_COMECA = 1
        const val PRIMEIRA_PALAVRA = 2
        const val PALAVRA_DO_MEIO = 3
        const val OUTRO_NOME = 4
        const val CIENTIFICO = 5
        const val NO_MEIO = 6
        const val COM_ERRO = 7

        /** Termos curtos demais casariam com tudo se aceitassem um erro. */
        const val MINIMO_PARA_ERRO = 4

        fun palavrasDe(normalizado: String): List<Palavra> = buildList {
            var inicio = -1
            for (i in 0..normalizado.length) {
                val espaco = i == normalizado.length || normalizado[i] == ' '
                if (!espaco && inicio < 0) inicio = i
                if (espaco && inicio >= 0) {
                    add(Palavra(inicio, normalizado.substring(inicio, i)))
                    inicio = -1
                }
            }
        }
    }
}

/**
 * Minúsculas, sem acento, e o que não é letra nem número vira espaço, sem mudar
 * o tamanho do texto: a letra i do normalizado é a letra i do original. É isso
 * que permite destacar, no nome original com acento, o trecho que casou.
 */
fun normalizar(texto: String): String = String(CharArray(texto.length) { normalizarLetra(texto[it]) })

private fun normalizarLetra(c: Char): Char = when {
    c in 'a'..'z' || c in '0'..'9' -> c
    c in 'A'..'Z' -> c.lowercaseChar()
    c.isLetterOrDigit() -> Normalizer.normalize(c.toString(), Normalizer.Form.NFD)[0].lowercaseChar()
    else -> ' '
}

/**
 * Tamanho do começo de [palavra] que casa com [termo] com no máximo um erro
 * (letra trocada, faltando, sobrando ou duas invertidas), ou null. Termos com
 * menos de 4 letras só casam exatos.
 */
internal fun comecoAproximado(termo: String, palavra: String): Int? {
    if (palavra.startsWith(termo)) return termo.length
    if (termo.length < 4) return null
    // Prefere o começo do mesmo tamanho do termo: "alecrin" → "alecrim".
    for (tamanho in listOf(termo.length, termo.length + 1, termo.length - 1)) {
        if (tamanho < 1 || tamanho > palavra.length) continue
        if (distancia(termo, palavra.substring(0, tamanho)) <= 1) return tamanho
    }
    return null
}

/** Distância de edição com transposição (Damerau, versão "optimal string alignment"). */
private fun distancia(a: String, b: String): Int {
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) {
        for (j in 1..b.length) {
            val custo = if (a[i - 1] == b[j - 1]) 0 else 1
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + custo)
            if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + 1)
            }
        }
    }
    return d[a.length][b.length]
}
