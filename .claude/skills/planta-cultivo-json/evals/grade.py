"""Avalia as respostas das execuções de teste e grava grading.json em cada run.

Uso: python grade.py <pasta-da-iteracao>

Estrutura esperada (uma resposta por execução, exatamente como foi dada no chat):

    <pasta-da-iteracao>/
      eval-1-tomate-cherry/
        with_skill/run-1/outputs/resposta.md
        without_skill/run-1/outputs/resposta.md
      eval-2-orquidea-phalaenopsis/...
      eval-3-manjericao/...
      eval-4-lista-tres-plantas/...

As checagens de esquema usam o validar.py da skill. As demais funcionam também
com o formato antigo do prompt original, para a comparação ser justa. As
checagens específicas de cada planta são escolhidas pelo final do nome da
pasta do eval (ver ESPECIFICAS); os evals de lista, que esperam vários blocos
```json na mesma resposta, são escolhidos do mesmo jeito (ver LISTAS). A lista
de checagens fica espelhada em evals.json; se mudar uma, mude a outra.
"""

import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "scripts"))
import validar  # noqa: E402

ACAO_FLOR = re.compile(r"retir|remov|cort|belisc|despont|pod|elimin|arranc|tir[ae]", re.I)


def textos(dados, *secoes):
    for secao in secoes:
        for item in dados.get(secao, []) or []:
            if isinstance(item, dict):
                yield " ".join(str(v) for v in item.values())


def tarefas(dados, tipo=None):
    return [t for t in dados.get("tarefas_agendadas", []) or []
            if isinstance(t, dict) and (tipo is None or t.get("tipo") == tipo)]


def fim_do_ciclo(dados):
    planta = dados.get("planta", {}) or {}
    duracao = planta.get("duracao_ciclo_dias")
    if validar.eh_inteiro(duracao):
        return duracao
    fins = [f.get("dia_fim") for f in dados.get("fases_ciclo_vida", []) if isinstance(f, dict)]
    if planta.get("tipo_ciclo") == "PERENE" and None in fins:
        return None
    fins = [f for f in fins if validar.eh_inteiro(f)]
    return max(fins) if fins else None


def checagens_comuns(texto, dados):
    r = []
    limpo = texto.strip()
    so_json = bool(validar.CERCA.fullmatch(limpo)) or (limpo.startswith("{") and limpo.endswith("}"))
    r.append(("Resposta é só o JSON, sem texto antes ou depois", so_json,
              "começa com " + repr(limpo[:30]) + " e termina com " + repr(limpo[-30:])))
    if dados is None:
        r.append(("O JSON é válido (faz parse)", False, "json.loads falhou"))
        return r
    r.append(("O JSON é válido (faz parse)", True, "parse ok"))

    erros = validar.validar(dados)
    r.append(("Segue o esquema da skill: campos, tipos, enums e regras de dias", not erros,
              "validar.py: OK" if not erros else f"{len(erros)} erro(s): " + " | ".join(erros[:4])))

    fases = [f for f in dados.get("fases_ciclo_vida", []) if isinstance(f, dict)]
    tipadas = fases and all(validar.eh_inteiro(f.get("dia_inicio"))
                            and validar.valor_ok(f.get("dia_fim"), "inteiro?") for f in fases)
    erros_seq = []
    if tipadas:
        validar.checar_sequencia_fases(fases, erros_seq)
    r.append(("Fases encadeadas desde o dia 1, sem lacunas nem sobreposição", bool(tipadas) and not erros_seq,
              "; ".join(erros_seq) or ("fases: " + ", ".join(f"{f['dia_inicio']}-{f['dia_fim']}" for f in fases)
                                       if tipadas else "fases ausentes ou com dias não inteiros")))

    fim = fim_do_ciclo(dados)
    fora = []
    if fim is not None:
        fora += [f"tarefa {t.get('id')} até {t.get('dia_fim_ciclo')}" for t in tarefas(dados)
                 if validar.eh_inteiro(t.get("dia_fim_ciclo")) and t["dia_fim_ciclo"] > fim]
        fora += [f"alerta no dia {a.get('dia_disparo')}" for a in dados.get("alertas_fase", [])
                 if isinstance(a, dict) and validar.eh_inteiro(a.get("dia_disparo")) and a["dia_disparo"] > fim]
    r.append(("Tarefas e alertas não passam do fim do ciclo", not fora,
              f"fim do ciclo: {fim}; " + ("; ".join(fora) or "nada fora")))

    req = dados.get("requisitos_iniciais", {}) or {}
    problemas = []
    for campo in ("volume_minimo_litros", "horas_sol_diarias_recomendadas"):
        v = req.get(campo)
        if not isinstance(v, (int, float)) or v <= 0:
            if not (campo.startswith("horas") and v == 0 and req.get("luminosidade") == "SOMBRA"):
                problemas.append(f"{campo}={v!r}")
    for secao, campo in (("planta", "tipo_ciclo"), ("planta", "dificuldade"), ("requisitos_iniciais", "luminosidade")):
        v = (dados.get(secao) or {}).get(campo)
        if isinstance(v, str) and "|" in v:
            problemas.append(f"{campo}={v!r}")
    problemas += [f"tarefa {t.get('id')} tipo={t.get('tipo')!r}" for t in tarefas(dados) if "|" in str(t.get("tipo"))]
    r.append(("Sem valores de exemplo esquecidos (números > 0, nenhum enum com '|')", not problemas,
              "; ".join(problemas) or "ok"))

    invalidos = sorted({str(t.get("tipo")) for t in tarefas(dados)} - validar.TIPOS_TAREFA)
    r.append(("Todos os tipos de tarefa pertencem à lista corrigida (sem TUTRAGEM)", not invalidos,
              "inválidos: " + ", ".join(invalidos) if invalidos else "tipos usados: "
              + ", ".join(sorted({str(t.get('tipo')) for t in tarefas(dados)}))))

    criticas = [t.get("id") for t in tarefas(dados, "REGA") if t.get("critica") is True]
    r.append(("Há tarefa de REGA marcada como crítica", bool(criticas), ", ".join(map(str, criticas)) or "nenhuma"))

    so_conferir = [f"{t.get('id')}: {t.get('titulo')!r}" for t in tarefas(dados)
                   if t.get("tipo") != "PRAGA_CHECK" and SO_CONFERIR.search(str(t.get("titulo", "")))
                   and not COM_ACAO.search(str(t.get("titulo", "")))]
    r.append(("Nenhuma tarefa de só conferir, sem ação (exceto PRAGA_CHECK)", not so_conferir,
              "; ".join(so_conferir) or "títulos: " + "; ".join(str(t.get("titulo")) for t in tarefas(dados))))
    return r


SO_CONFERIR = re.compile(r"^\s*(conferir|verificar|checar|observar|acompanhar|monitorar|avaliar)", re.I)
COM_ACAO = re.compile(r"\b(e|para)\s+\w+ar\b", re.I)


def eventos(dados):
    return [e for e in dados.get("eventos_sazonais", []) or [] if isinstance(e, dict)]


def checagens_tomate(dados):
    tutor = [t.get("id") for t in tarefas(dados) if t.get("tipo") in ("TUTORAGEM", "TUTRAGEM")]
    tutor += [a.get("titulo") for a in dados.get("alertas_fase", [])
              if isinstance(a, dict) and re.search(r"tutor|estaca", json.dumps(a, ensure_ascii=False), re.I)]
    colheitas = [t for t in tarefas(dados, "COLHEITA") if validar.eh_inteiro(t.get("frequencia_dias"))
                 and t["frequencia_dias"] <= 7 and validar.eh_inteiro(t.get("dia_inicio_ciclo"))
                 and validar.eh_inteiro(t.get("dia_fim_ciclo"))
                 and t["dia_fim_ciclo"] - t["dia_inicio_ciclo"] >= 14]
    return [
        ("Inclui tutoragem (tarefa ou alerta)", bool(tutor), ", ".join(map(str, tutor)) or "nenhuma"),
        ("Colheita recorrente (a cada ≤ 7 dias, janela ≥ 14 dias)", bool(colheitas),
         ", ".join(f"{t['id']}: a cada {t['frequencia_dias']}d, dias {t['dia_inicio_ciclo']}-{t['dia_fim_ciclo']}"
                   for t in colheitas) or "nenhuma"),
    ]


def checagens_orquidea(dados):
    fases = [f for f in dados.get("fases_ciclo_vida", []) if isinstance(f, dict)]
    primeira = (fases[0].get("fase_id", "") + " " + fases[0].get("nome", "")) if fases else ""
    forma = (dados.get("planta") or {}).get("forma_inicio")
    sem_semente = bool(fases) and not re.search(r"semea|germin|semente", primeira, re.I) and forma != "SEMENTE"
    rega_aberta = [t.get("id") for t in tarefas(dados, "REGA") if t.get("dia_fim_ciclo") is None]
    perene_ok = ((dados.get("planta") or {}).get("duracao_ciclo_dias", "ausente") is None
                 and bool(fases) and fases[-1].get("dia_fim") is None and bool(rega_aberta))
    replantio = [t.get("id") for t in tarefas(dados) if t.get("tipo") == "TRANSPLANTE"]
    replantio += [s[:60] for s in textos(dados, "tarefas_agendadas", "alertas_fase") if re.search(r"replant|troca de vaso|trocar o vaso|transplant", s, re.I)]
    floracao = [f"meses {e.get('meses')}: {e.get('titulo')}" for e in eventos(dados)
                if re.search(r"flor|haste", json.dumps(e, ensure_ascii=False), re.I)]
    return [
        ("Floração tratada como evento sazonal por mês (não por dia)", bool(floracao), "; ".join(floracao) or "nenhum evento sazonal de floração"),
        ("Não começa por semeadura (planta adulta ou muda)", sem_semente, f"primeira fase: {primeira!r}; forma_inicio: {forma!r}"),
        ("Perene sem data de fim inventada (duração null, última fase e rega sem fim)", perene_ok,
         f"duracao_ciclo_dias={(dados.get('planta') or {}).get('duracao_ciclo_dias', 'ausente')!r}; "
         f"última fase dia_fim={fases[-1].get('dia_fim') if fases else None!r}; regas sem fim: {rega_aberta}"),
        ("Inclui replantio periódico", bool(replantio), "; ".join(replantio[:3]) or "nenhum"),
    ]


def checagens_manjericao(dados):
    flores = [s[:80] for s in textos(dados, "tarefas_agendadas", "alertas_fase")
              if re.search(r"flor|inflorescên|pendão|pendoa", s, re.I) and ACAO_FLOR.search(s)]
    colheita = [t.get("id") for t in tarefas(dados, "COLHEITA")
                if validar.eh_inteiro(t.get("frequencia_dias")) and t["frequencia_dias"] <= 14]
    frio = [f"meses {e.get('meses')}: {e.get('titulo')}" for e in eventos(dados)
            if re.search(r"frio|inverno|geada", json.dumps(e, ensure_ascii=False), re.I)]
    return [
        ("Aviso de frio como evento sazonal por mês", bool(frio), "; ".join(frio) or "nenhum evento sazonal de frio"),
        ("Orienta a remover as flores para prolongar a produção", bool(flores), "; ".join(flores[:2]) or "nenhuma menção"),
        ("Colheita de folhas recorrente (a cada ≤ 14 dias)", bool(colheita), ", ".join(map(str, colheita)) or "nenhuma"),
    ]


ESPECIFICAS = {"tomate-cherry": checagens_tomate, "orquidea-phalaenopsis": checagens_orquidea,
               "manjericao": checagens_manjericao}

BLOCO = re.compile(r"```json\s*(.*?)\s*```", re.S)

# Evals de lista: regex de cada planta esperada, na ordem do pedido (testada no
# nome_comum de cada bloco), e dos itens que não são planta e devem aparecer
# citados numa frase depois do último bloco.
LISTAS = {
    "lista-tres-plantas": {"plantas": [r"tomate", r"manjeric", r"alecrim"], "invalidos": []},
    "lista-com-item-invalido": {"plantas": [r"hortel", r"alecrim"], "invalidos": [r"cadeira"]},
    "planta-com-contexto": {"plantas": [r"manjeric"], "invalidos": []},
}


def checagens_lista(texto, esperado):
    blocos = list(BLOCO.finditer(texto))
    dados = []
    for bloco in blocos:
        try:
            dados.append(json.loads(bloco.group(1)))
        except json.JSONDecodeError:
            dados.append(None)
    nomes = [str(((d.get("planta") or {}) if isinstance(d, dict) else {}).get("nome_comum", "?")) for d in dados]
    plantas = esperado["plantas"]

    r = [(f"Um bloco ```json por planta pedida ({len(plantas)})", len(blocos) == len(plantas),
          f"{len(blocos)} bloco(s): " + ", ".join(nomes))]
    ordem = len(nomes) == len(plantas) and all(re.search(p, n, re.I) for p, n in zip(plantas, nomes))
    r.append(("Blocos na ordem do pedido", ordem, " → ".join(nomes) or "nenhum bloco"))

    reprovados = []
    for i, d in enumerate(dados):
        erros = ["não faz parse"] if d is None else validar.validar(d)
        if erros:
            reprovados.append(f"bloco {i + 1} ({nomes[i]}): {len(erros)} erro(s), ex.: {erros[0]}")
    r.append(("Todo bloco é JSON válido e passa no validar.py", bool(blocos) and not reprovados,
              "; ".join(reprovados) or f"{len(blocos)} bloco(s) OK"))

    if not blocos:
        return r
    antes = texto[:blocos[0].start()].strip()
    entre = [texto[a.end():b.start()].strip() for a, b in zip(blocos, blocos[1:])]
    sobra = [repr(t[:60]) for t in [antes, *entre] if t]
    r.append(("Nenhum texto antes ou entre os blocos", not sobra, "; ".join(sobra) or "limpo"))
    depois = texto[blocos[-1].end():].strip()
    if esperado["invalidos"]:
        citados = bool(depois) and all(re.search(p, depois, re.I) for p in esperado["invalidos"])
        r.append(("Itens que não são planta citados numa frase depois do último bloco", citados,
                  repr(depois[:120]) if depois else "nada depois do último bloco"))
    else:
        r.append(("Nenhum texto depois do último bloco", not depois, repr(depois[:120]) if depois else "limpo"))
    return r


def resumir(resultados):
    expectations = [{"text": t, "passed": bool(p), "evidence": e} for t, p, e in resultados]
    passou = sum(x["passed"] for x in expectations)
    return {"expectations": expectations,
            "summary": {"passed": passou, "failed": len(expectations) - passou, "total": len(expectations),
                        "pass_rate": round(passou / len(expectations), 4)}}


def avaliar(run_dir, nome_eval):
    texto = (run_dir / "outputs" / "resposta.md").read_text(encoding="utf-8-sig")
    lista = next((k for k in LISTAS if nome_eval.endswith(k)), None)
    if lista:
        return resumir(checagens_lista(texto, LISTAS[lista]))
    try:
        dados = validar.extrair_json(texto)
    except json.JSONDecodeError:
        inicio, fim = texto.find("{"), texto.rfind("}")
        try:
            dados = json.loads(texto[inicio:fim + 1]) if inicio >= 0 else None
        except json.JSONDecodeError:
            dados = None
    resultados = checagens_comuns(texto, dados)
    chave = next((k for k in ESPECIFICAS if nome_eval.endswith(k)), None)
    if dados is not None and chave:
        resultados += ESPECIFICAS[chave](dados)
    return resumir(resultados)


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    iteracao = Path(sys.argv[1])
    for run_dir in sorted(iteracao.glob("eval-*/*/run-*")):
        # O timing fica só no timing.json: o aggregate_benchmark lê os tokens de lá.
        grading = avaliar(run_dir, run_dir.parent.parent.name)
        (run_dir / "grading.json").write_text(json.dumps(grading, ensure_ascii=False, indent=2), encoding="utf-8")
        s = grading["summary"]
        print(f"{run_dir.relative_to(iteracao)}: {s['passed']}/{s['total']}")
        for x in grading["expectations"]:
            if not x["passed"]:
                print(f"   ✗ {x['text']} — {x['evidence'][:160]}")


if __name__ == "__main__":
    main()
