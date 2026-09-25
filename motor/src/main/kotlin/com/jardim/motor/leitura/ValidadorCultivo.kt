package com.jardim.motor.leitura

import com.jardim.motor.modelo.Luminosidade
import com.jardim.motor.modelo.PlantaCultivo
import com.jardim.motor.modelo.RequisitosIniciais
import com.jardim.motor.modelo.TipoTarefa

/**
 * Regras da linha do tempo da skill planta-cultivo-json (seção 4), portadas do
 * scripts/validar.py dela. Campos, tipos e enums o kotlinx.serialization já
 * garante na leitura; aqui fica a coerência entre campos. Mudou uma regra no
 * validar.py, muda aqui também (as mensagens são as mesmas de propósito).
 */
object ValidadorCultivo {

    private val snakeCase = Regex("^[a-z0-9]+(?:_[a-z0-9]+)*$")

    fun validar(c: PlantaCultivo): List<String> = buildList {
        checarTextos(c)
        checarMeses(c.planta.mesesPlantio, "planta.meses_plantio")
        c.eventosSazonais.forEachIndexed { i, e -> checarMeses(e.meses, "eventos_sazonais[$i].meses") }
        checarRequisitos(c.requisitosIniciais)
        if (c.fasesCicloVida.isEmpty()) add("fases_ciclo_vida: a lista não pode ser vazia")
        if (c.tarefasAgendadas.isEmpty()) add("tarefas_agendadas: a lista não pode ser vazia")
        if (c.pragasComuns.isEmpty()) add("pragas_comuns: a lista não pode ser vazia")
        checarIds(c.fasesCicloVida.map { it.faseId }, "fases_ciclo_vida", "fase_id")
        checarIds(c.tarefasAgendadas.map { it.id }, "tarefas_agendadas", "id")
        if (c.fasesCicloVida.isNotEmpty()) checarFases(c)
        checarTarefas(c)
        checarAlertas(c)
    }

    // Só os textos que aparecem na tela ou na notificação.
    private fun MutableList<String>.checarTextos(c: PlantaCultivo) {
        fun exigir(caminho: String, valor: String) {
            if (valor.isBlank()) add("$caminho: texto vazio")
        }
        exigir("planta.nome_comum", c.planta.nomeComum)
        c.fasesCicloVida.forEachIndexed { i, f -> exigir("fases_ciclo_vida[$i].nome", f.nome) }
        c.tarefasAgendadas.forEachIndexed { i, t ->
            exigir("tarefas_agendadas[$i].titulo", t.titulo)
            exigir("tarefas_agendadas[$i].instrucoes", t.instrucoes)
        }
        c.alertasFase.forEachIndexed { i, a ->
            exigir("alertas_fase[$i].titulo", a.titulo)
            exigir("alertas_fase[$i].acao_requerida", a.acaoRequerida)
        }
        c.eventosSazonais.forEachIndexed { i, e ->
            exigir("eventos_sazonais[$i].titulo", e.titulo)
            exigir("eventos_sazonais[$i].acao_requerida", e.acaoRequerida)
        }
    }

    private fun MutableList<String>.checarMeses(meses: List<Int>, caminho: String) {
        if (meses.isEmpty() || meses.toSet().size != meses.size || meses.any { it !in 1..12 }) {
            add("$caminho: $meses deveria ser lista não vazia de meses de 1 a 12, sem repetição")
        }
    }

    private fun MutableList<String>.checarRequisitos(r: RequisitosIniciais) {
        if (r.volumeMinimoLitros <= 0) {
            add("requisitos_iniciais.volume_minimo_litros: precisa ser maior que 0")
        }
        if (r.horasSolMinimas !in 0.0..24.0) {
            add("requisitos_iniciais.horas_sol_minimas: precisa estar entre 0 e 24")
        }
        if (r.horasSolDiariasRecomendadas !in 0.0..24.0) {
            add("requisitos_iniciais.horas_sol_diarias_recomendadas: precisa estar entre 0 e 24")
        }
        if (r.horasSolDiariasRecomendadas == 0.0 && r.luminosidade != Luminosidade.SOMBRA) {
            add("requisitos_iniciais.horas_sol_diarias_recomendadas: 0 só faz sentido com luminosidade SOMBRA")
        }
        if (r.horasSolMinimas > r.horasSolDiariasRecomendadas) {
            add("requisitos_iniciais.horas_sol_minimas (${r.horasSolMinimas}) não pode passar das recomendadas (${r.horasSolDiariasRecomendadas})")
        }
    }

    private fun MutableList<String>.checarIds(ids: List<String>, lista: String, campo: String) {
        val vistos = mutableSetOf<String>()
        ids.forEachIndexed { i, id ->
            if (!snakeCase.matches(id)) add("$lista[$i].$campo: '$id' deveria estar em snake_case")
            if (!vistos.add(id)) add("$lista[$i].$campo: '$id' repetido")
        }
    }

    private fun MutableList<String>.checarFases(c: PlantaCultivo) {
        val fases = c.fasesCicloVida
        val duracao = c.planta.duracaoCicloDias

        fases.dropLast(1).forEachIndexed { i, f ->
            if (f.diaFim == null) add("fases_ciclo_vida[$i].dia_fim: só a última fase pode ter dia_fim null")
        }
        val fimUltima = fases.last().diaFim
        if (c.perene) {
            if (fimUltima != null) add("fases_ciclo_vida: planta PERENE precisa terminar numa fase com dia_fim null")
            if (duracao != null) add("planta.duracao_ciclo_dias: deve ser null para planta PERENE")
        } else if (fimUltima == null) {
            add("fases_ciclo_vida: dia_fim null só é permitido para planta PERENE")
        } else if (duracao != fimUltima) {
            add("planta.duracao_ciclo_dias ($duracao) deve ser igual ao dia_fim da última fase ($fimUltima)")
        }

        if (fases[0].diaInicio != 1) {
            add("fases_ciclo_vida[0].dia_inicio: a primeira fase começa no dia 1, não no ${fases[0].diaInicio}")
        }
        fases.forEachIndexed { i, f ->
            if (f.diaFim != null && f.diaFim < f.diaInicio) {
                add("fases_ciclo_vida[$i].dia_fim: ${f.diaFim} é anterior ao início (${f.diaInicio})")
            }
        }
        fases.zipWithNext().forEachIndexed { i, (anterior, atual) ->
            val fim = anterior.diaFim ?: return@forEachIndexed // já reportado acima
            val esperado = fim + 1
            if (atual.diaInicio != esperado) {
                val problema = if (atual.diaInicio > esperado) "lacuna" else "sobreposição"
                add("fases_ciclo_vida[${i + 1}].dia_inicio: ${atual.diaInicio} deveria ser $esperado " +
                    "(dia seguinte ao fim da fase anterior); há $problema")
            }
        }
    }

    private fun MutableList<String>.checarTarefas(c: PlantaCultivo) {
        val duracao = c.planta.duracaoCicloDias
        c.tarefasAgendadas.forEachIndexed { i, t ->
            val caminho = "tarefas_agendadas[$i]"
            if (t.frequenciaDias < 1) add("$caminho.frequencia_dias: precisa ser >= 1 (ação única vira alerta)")
            if (t.diaInicioCiclo < 1) add("$caminho.dia_inicio_ciclo: o ciclo começa no dia 1")
            val fim = t.diaFimCiclo
            when {
                fim == null -> if (!c.perene) add("$caminho.dia_fim_ciclo: null só é permitido para planta PERENE")
                fim < t.diaInicioCiclo -> add("$caminho.dia_fim_ciclo: $fim é anterior ao início (${t.diaInicioCiclo})")
                duracao != null && fim > duracao -> add("$caminho.dia_fim_ciclo: $fim passa do fim do ciclo (dia $duracao)")
            }
        }
        if (c.tarefasAgendadas.none { it.tipo == TipoTarefa.REGA && it.critica }) {
            add("tarefas_agendadas: precisa de pelo menos uma tarefa REGA com critica true")
        }
    }

    private fun MutableList<String>.checarAlertas(c: PlantaCultivo) {
        val duracao = c.planta.duracaoCicloDias
        c.alertasFase.forEachIndexed { i, a ->
            if (a.diaDisparo < 1) {
                add("alertas_fase[$i].dia_disparo: o ciclo começa no dia 1")
            } else if (duracao != null && a.diaDisparo > duracao) {
                add("alertas_fase[$i].dia_disparo: ${a.diaDisparo} passa do fim do ciclo (dia $duracao)")
            }
        }
    }
}
