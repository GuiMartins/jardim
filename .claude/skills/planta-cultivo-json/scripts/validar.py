#!/usr/bin/env python3
"""Valida o JSON gerado pela skill planta-cultivo-json.

Uso:
    python validar.py caminho/do/arquivo.json
    python validar.py tomate.json manjericao.json alecrim.json
    python validar.py - < arquivo.json

Imprime "OK" e sai com código 0 quando o JSON segue o esquema e as regras da
linha do tempo; caso contrário lista cada problema e sai com código 1. Com
vários arquivos, mostra o resultado de cada um e sai com 1 se algum falhar.
"""

import json
import re
import sys

TIPOS_CICLO = {"ANUAL", "BIENAL", "PERENE"}
DIFICULDADES = {"FACIL", "MEDIA", "DIFICIL"}
FORMAS_INICIO = {"SEMENTE", "MUDA", "ESTACA", "BULBO", "PLANTA_ADULTA"}
LUMINOSIDADES = {"SOL_DIRETO", "MEIA_SOMBRA", "SOMBRA"}
TIPOS_TAREFA = {"REGA", "ADUBACAO", "DESBASTE", "TUTORAGEM", "PODA",
                "TRANSPLANTE", "PRAGA_CHECK", "COLHEITA"}
TIPOS_PRAGA = {"PRAGA", "DOENCA", "DISTURBIO"}

# Regra de cada campo: "texto", "inteiro", "inteiro?" (aceita null), "numero",
# "bool", "meses" (lista de 1 a 12) ou um conjunto de valores permitidos (enum).
ESQUEMA_PLANTA = {
    "nome_comum": "texto",
    "nome_cientifico": "texto",
    "descricao": "texto",
    "tipo_ciclo": TIPOS_CICLO,
    "dificuldade": DIFICULDADES,
    "forma_inicio": FORMAS_INICIO,
    "duracao_ciclo_dias": "inteiro?",
    "meses_plantio": "meses",
    "temperatura_minima_c": "inteiro",
    "temperatura_maxima_c": "inteiro",
}
ESQUEMA_REQUISITOS = {
    "tipo_vaso_ou_solo": "texto",
    "volume_minimo_litros": "numero",
    "luminosidade": LUMINOSIDADES,
    "horas_sol_minimas": "numero",
    "horas_sol_diarias_recomendadas": "numero",
    "substrato_recomendado": "texto",
    "camada_drenagem": "bool",
}
ESQUEMA_FASE = {
    "fase_id": "texto",
    "nome": "texto",
    "dia_inicio": "inteiro",
    "dia_fim": "inteiro?",
    "descricao": "texto",
}
ESQUEMA_TAREFA = {
    "id": "texto",
    "titulo": "texto",
    "tipo": TIPOS_TAREFA,
    "frequencia_dias": "inteiro",
    "dia_inicio_ciclo": "inteiro",
    "dia_fim_ciclo": "inteiro?",
    "instrucoes": "texto",
    "critica": "bool",
}
ESQUEMA_ALERTA = {
    "dia_disparo": "inteiro",
    "titulo": "texto",
    "mensagem": "texto",
    "acao_requerida": "texto",
}
ESQUEMA_PRAGA = {
    "nome": "texto",
    "tipo": TIPOS_PRAGA,
    "sintomas": "texto",
    "prevencao_tratamento": "texto",
}
ESQUEMA_EVENTO = {
    "meses": "meses",
    "titulo": "texto",
    "mensagem": "texto",
    "acao_requerida": "texto",
}
LISTAS = {
    "fases_ciclo_vida": ESQUEMA_FASE,
    "tarefas_agendadas": ESQUEMA_TAREFA,
    "alertas_fase": ESQUEMA_ALERTA,
    "pragas_comuns": ESQUEMA_PRAGA,
    "eventos_sazonais": ESQUEMA_EVENTO,
}
CHAVES_TOPO = {"planta", "requisitos_iniciais", *LISTAS}
LISTAS_NAO_VAZIAS = ("fases_ciclo_vida", "tarefas_agendadas", "pragas_comuns")

SNAKE_CASE = re.compile(r"^[a-z0-9]+(?:_[a-z0-9]+)*$")


def eh_inteiro(valor):
    # bool é subclasse de int em Python; true/false não podem passar por dia.
    return isinstance(valor, int) and not isinstance(valor, bool)


def valor_ok(valor, regra):
    if isinstance(regra, set):
        return valor in regra
    if regra == "texto":
        return isinstance(valor, str) and valor.strip() != ""
    if regra == "inteiro":
        return eh_inteiro(valor)
    if regra == "inteiro?":
        return valor is None or eh_inteiro(valor)
    if regra == "numero":
        return eh_inteiro(valor) or isinstance(valor, float)
    if regra == "bool":
        return isinstance(valor, bool)
    if regra == "meses":
        return (isinstance(valor, list) and len(valor) > 0 and len(set(valor)) == len(valor)
                and all(eh_inteiro(m) and 1 <= m <= 12 for m in valor))
    raise ValueError(f"regra desconhecida: {regra}")


def descrever(regra):
    if isinstance(regra, set):
        return "um de " + ", ".join(sorted(regra))
    return {"texto": "texto não vazio", "inteiro": "inteiro",
            "inteiro?": "inteiro ou null", "numero": "número",
            "bool": "true/false",
            "meses": "lista não vazia de meses de 1 a 12, sem repetição"}[regra]


def checar_objeto(obj, esquema, caminho, erros):
    """Confere campos ausentes, campos extras e o tipo de cada valor."""
    if not isinstance(obj, dict):
        erros.append(f"{caminho}: deveria ser um objeto")
        return
    for campo in sorted(esquema.keys() - obj.keys()):
        erros.append(f"{caminho}.{campo}: campo obrigatório ausente")
    for campo in sorted(obj.keys() - esquema.keys()):
        erros.append(f"{caminho}.{campo}: campo fora do esquema")
    for campo, regra in esquema.items():
        if campo in obj and not valor_ok(obj[campo], regra):
            erros.append(f"{caminho}.{campo}: {obj[campo]!r} deveria ser {descrever(regra)}")


def checar_requisitos(req, erros):
    volume = req.get("volume_minimo_litros")
    if valor_ok(volume, "numero") and volume <= 0:
        erros.append("requisitos_iniciais.volume_minimo_litros: precisa ser maior que 0")
    minimas = req.get("horas_sol_minimas")
    horas = req.get("horas_sol_diarias_recomendadas")
    for campo, valor in (("horas_sol_minimas", minimas), ("horas_sol_diarias_recomendadas", horas)):
        if valor_ok(valor, "numero") and not 0 <= valor <= 24:
            erros.append(f"requisitos_iniciais.{campo}: precisa estar entre 0 e 24")
    if valor_ok(horas, "numero"):
        if horas == 0 and req.get("luminosidade") != "SOMBRA":
            erros.append("requisitos_iniciais.horas_sol_diarias_recomendadas: 0 só faz sentido com luminosidade SOMBRA")
        if valor_ok(minimas, "numero") and minimas > horas:
            erros.append(f"requisitos_iniciais.horas_sol_minimas ({minimas}) não pode passar das recomendadas ({horas})")


def checar_temperaturas(planta, erros):
    minima, maxima = planta.get("temperatura_minima_c"), planta.get("temperatura_maxima_c")
    for campo, valor in (("temperatura_minima_c", minima), ("temperatura_maxima_c", maxima)):
        if eh_inteiro(valor) and not -20 <= valor <= 50:
            erros.append(f"planta.{campo}: precisa estar entre -20 e 50")
    if eh_inteiro(minima) and eh_inteiro(maxima) and minima >= maxima:
        erros.append(f"planta.temperatura_minima_c ({minima}) precisa ser menor que a máxima ({maxima})")


def checar_ids(itens, campo, caminho, erros):
    vistos = set()
    for i, item in enumerate(itens):
        valor = item.get(campo) if isinstance(item, dict) else None
        if not isinstance(valor, str):
            continue
        if not SNAKE_CASE.match(valor):
            erros.append(f"{caminho}[{i}].{campo}: '{valor}' deveria estar em snake_case")
        if valor in vistos:
            erros.append(f"{caminho}[{i}].{campo}: '{valor}' repetido")
        vistos.add(valor)


def checar_sequencia_fases(fases, erros):
    """Confere o encadeamento das fases na linha do tempo.

    `fases` já passou pela checagem de tipos: `dia_inicio` é sempre inteiro e
    `dia_fim` também, exceto na última fase de uma planta PERENE, onde é None.
    Acrescente em `erros` uma mensagem para cada problema encontrado, no mesmo
    estilo das outras checagens (ex.: "fases_ciclo_vida[2].dia_inicio: ...").
    """
    if fases[0]["dia_inicio"] != 1:
        erros.append(f"fases_ciclo_vida[0].dia_inicio: a primeira fase começa no dia 1, não no {fases[0]['dia_inicio']}")
    for i, fase in enumerate(fases):
        if fase["dia_fim"] is not None and fase["dia_fim"] < fase["dia_inicio"]:
            erros.append(f"fases_ciclo_vida[{i}].dia_fim: {fase['dia_fim']} é anterior ao início ({fase['dia_inicio']})")
    for i, (anterior, atual) in enumerate(zip(fases, fases[1:]), start=1):
        if anterior["dia_fim"] is None:
            continue  # fase aberta no meio da lista já foi reportada em checar_fases
        esperado = anterior["dia_fim"] + 1
        if atual["dia_inicio"] != esperado:
            problema = "lacuna" if atual["dia_inicio"] > esperado else "sobreposição"
            erros.append(f"fases_ciclo_vida[{i}].dia_inicio: {atual['dia_inicio']} deveria ser {esperado} "
                         f"(dia seguinte ao fim da fase anterior); há {problema}")


def checar_fases(fases, perene, duracao, erros):
    tipadas = all(isinstance(f, dict) and eh_inteiro(f.get("dia_inicio"))
                  and valor_ok(f.get("dia_fim"), "inteiro?") for f in fases)
    if not tipadas:
        return  # os erros de tipo já foram reportados por checar_objeto

    for i, fase in enumerate(fases[:-1]):
        if fase["dia_fim"] is None:
            erros.append(f"fases_ciclo_vida[{i}].dia_fim: só a última fase pode ter dia_fim null")

    fim_ultima = fases[-1]["dia_fim"]
    if perene:
        if fim_ultima is not None:
            erros.append("fases_ciclo_vida: planta PERENE precisa terminar numa fase com dia_fim null")
        if duracao is not None:
            erros.append("planta.duracao_ciclo_dias: deve ser null para planta PERENE")
    else:
        if fim_ultima is None:
            erros.append("fases_ciclo_vida: dia_fim null só é permitido para planta PERENE")
        elif duracao != fim_ultima:
            erros.append(f"planta.duracao_ciclo_dias ({duracao}) deve ser igual ao dia_fim da última fase ({fim_ultima})")

    checar_sequencia_fases(fases, erros)


def checar_tarefas(tarefas, perene, duracao, erros):
    for i, tarefa in enumerate(tarefas):
        if not isinstance(tarefa, dict):
            continue
        caminho = f"tarefas_agendadas[{i}]"
        freq = tarefa.get("frequencia_dias")
        if eh_inteiro(freq) and freq < 1:
            erros.append(f"{caminho}.frequencia_dias: precisa ser >= 1 (ação única vira alerta)")
        inicio, fim = tarefa.get("dia_inicio_ciclo"), tarefa.get("dia_fim_ciclo")
        if not eh_inteiro(inicio) or not valor_ok(fim, "inteiro?"):
            continue
        if inicio < 1:
            erros.append(f"{caminho}.dia_inicio_ciclo: o ciclo começa no dia 1")
        if fim is None:
            if not perene:
                erros.append(f"{caminho}.dia_fim_ciclo: null só é permitido para planta PERENE")
        elif fim < inicio:
            erros.append(f"{caminho}.dia_fim_ciclo: {fim} é anterior ao início ({inicio})")
        elif duracao is not None and fim > duracao:
            erros.append(f"{caminho}.dia_fim_ciclo: {fim} passa do fim do ciclo (dia {duracao})")

    if not any(isinstance(t, dict) and t.get("tipo") == "REGA" and t.get("critica") is True
               for t in tarefas):
        erros.append("tarefas_agendadas: precisa de pelo menos uma tarefa REGA com critica true")


def checar_alertas(alertas, duracao, erros):
    for i, alerta in enumerate(alertas):
        dia = alerta.get("dia_disparo") if isinstance(alerta, dict) else None
        if not eh_inteiro(dia):
            continue
        if dia < 1:
            erros.append(f"alertas_fase[{i}].dia_disparo: o ciclo começa no dia 1")
        elif duracao is not None and dia > duracao:
            erros.append(f"alertas_fase[{i}].dia_disparo: {dia} passa do fim do ciclo (dia {duracao})")


def validar(dados):
    """Devolve a lista de problemas encontrados; lista vazia = JSON válido."""
    if not isinstance(dados, dict):
        return ["o topo do JSON deveria ser um objeto"]
    erros = []
    for chave in sorted(CHAVES_TOPO - dados.keys()):
        erros.append(f"{chave}: seção obrigatória ausente")
    for chave in sorted(dados.keys() - CHAVES_TOPO):
        erros.append(f"{chave}: seção fora do esquema")

    planta = dados.get("planta", {})
    checar_objeto(planta, ESQUEMA_PLANTA, "planta", erros)
    if isinstance(planta, dict):
        checar_temperaturas(planta, erros)
    requisitos = dados.get("requisitos_iniciais", {})
    checar_objeto(requisitos, ESQUEMA_REQUISITOS, "requisitos_iniciais", erros)
    if isinstance(requisitos, dict):
        checar_requisitos(requisitos, erros)

    listas = {}
    for chave, esquema in LISTAS.items():
        itens = dados.get(chave, [])
        if not isinstance(itens, list):
            erros.append(f"{chave}: deveria ser uma lista")
            itens = []
        for i, item in enumerate(itens):
            checar_objeto(item, esquema, f"{chave}[{i}]", erros)
        listas[chave] = itens
    for chave in LISTAS_NAO_VAZIAS:
        if chave in dados and not listas[chave]:
            erros.append(f"{chave}: a lista não pode ser vazia")

    checar_ids(listas["fases_ciclo_vida"], "fase_id", "fases_ciclo_vida", erros)
    checar_ids(listas["tarefas_agendadas"], "id", "tarefas_agendadas", erros)

    # Com tipo_ciclo ilegível, deduz se é perene pela duração para não esconder
    # os erros de linha do tempo enquanto o enum não for corrigido.
    planta = planta if isinstance(planta, dict) else {}
    tipo_ciclo, duracao = planta.get("tipo_ciclo"), planta.get("duracao_ciclo_dias")
    if not valor_ok(duracao, "inteiro?"):
        return erros
    perene = tipo_ciclo == "PERENE" if tipo_ciclo in TIPOS_CICLO else duracao is None
    if listas["fases_ciclo_vida"]:
        checar_fases(listas["fases_ciclo_vida"], perene, duracao, erros)
    checar_tarefas(listas["tarefas_agendadas"], perene, duracao, erros)
    checar_alertas(listas["alertas_fase"], duracao, erros)
    return erros


CERCA = re.compile(r"^```(?:json)?\s*(.*?)\s*```$", re.S)


def extrair_json(texto):
    """Aceita o JSON puro ou envolto num único bloco ```json."""
    texto = texto.strip()
    cercado = CERCA.match(texto)
    return json.loads(cercado.group(1) if cercado else texto)


def ler(caminho):
    if caminho == "-":
        sys.stdin.reconfigure(encoding="utf-8-sig")
        return sys.stdin.read()
    with open(caminho, encoding="utf-8-sig") as arquivo:
        return arquivo.read()


def relatorio(texto):
    """Devolve as linhas a imprimir para um arquivo e se ele passou."""
    try:
        dados = extrair_json(texto)
    except json.JSONDecodeError as erro:
        return [f"JSON inválido: {erro}"], False
    erros = validar(dados)
    if erros:
        return [f"{len(erros)} problema(s):"] + [f"- {erro}" for erro in erros], False
    return ["OK"], True


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    caminhos = sys.argv[1:]
    if not caminhos or (len(caminhos) > 1 and "-" in caminhos):
        print(__doc__)
        return 2

    falhas = 0
    for caminho in caminhos:
        linhas, passou = relatorio(ler(caminho))
        falhas += not passou
        if len(caminhos) == 1:
            print("\n".join(linhas))
        else:
            print(f"{caminho}: " + "\n".join(linhas))
    if len(caminhos) > 1:
        print(f"\n{len(caminhos) - falhas}/{len(caminhos)} arquivo(s) OK")
    return 1 if falhas else 0


if __name__ == "__main__":
    sys.exit(main())
