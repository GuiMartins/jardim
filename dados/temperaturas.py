"""Preenche temperatura_minima_c e temperatura_maxima_c nos JSONs do catálogo.

Os JSONs gerados antes desses campos existirem já falam de temperatura no texto
("com previsão abaixo de 5 °C, abrigue o vaso", "acima de 30 °C, confira todo
dia"). Rodar a skill de novo custaria o mesmo que gerar (~US$ 0,33 e 72 s por
planta) e reescreveria textos já prontos. Este script separa só as frases sobre
temperatura de cada planta e pede os dois números ao claude em lotes.

Onde o texto já traz o número sem ambiguidade, vale o texto: é a regra 9 da
skill (o aviso do app e a instrução que a pessoa lê não podem se contradizer).
A resposta do claude nessas plantas mede o quanto confiar nele nas outras.

Só insere os dois campos logo depois de meses_plantio, sem reformatar o resto.
Plantas que já têm os campos são puladas: dá para parar e rodar de novo.

    python dados/temperaturas.py               # tudo o que falta
    python dados/temperaturas.py --limite 25   # um lote, para conferir
    python dados/temperaturas.py --seco        # só o que o texto já diz, sem claude
"""
import argparse
import json
import re
import subprocess
import sys
import tempfile
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from gerar_catalogo import BLOCO, CATALOGO, LIMITE_PLANO, achar_claude, validar

PASTA = Path(__file__).parent
RESPOSTAS = PASTA / "cache" / "temperaturas"
MODELO = "sonnet"
ESFORCO = "medium"
POR_LOTE = 25
MAX_FRASES = 14

# "Em noites com previsão abaixo de 5 °C, levar o vaso para dentro": o número do aviso.
PREVISAO_FRIO = re.compile(r"previs[ãa]o (?:de )?(?:temperatura )?(?:m[ií]nima )?abaixo de (-?\d+) ?°C", re.I)
# "Acima de 30 °C, confira todo dia", nas instruções de rega.
REGA_CALOR = re.compile(r"acima de (\d+) ?°C", re.I)
SOBRE_TEMPERATURA = re.compile(r"°C|gead|frio|calor", re.I)
FRASE = re.compile(r"(?<=[.;!?])\s+")
MESES = re.compile(r'("meses_plantio":\s*\[[^\]]*\])')


def do_texto(dados):
    """(mínima, máxima) que o próprio texto fixa sem ambiguidade; None onde não fixa."""
    frio = {int(n) for n in PREVISAO_FRIO.findall(json.dumps(dados, ensure_ascii=False))}
    calor = {int(n) for t in dados["tarefas_agendadas"] if t["tipo"] == "REGA"
             for n in REGA_CALOR.findall(t["instrucoes"])}
    return (frio.pop() if len(frio) == 1 else None), (calor.pop() if len(calor) == 1 else None)


def frases(dados):
    """Frases que falam de temperatura, das mais úteis (eventos por época) às menos."""
    textos = [e[c] for e in dados["eventos_sazonais"] for c in ("titulo", "mensagem", "acao_requerida")]
    textos += [t["instrucoes"] for t in dados["tarefas_agendadas"] if t["tipo"] == "REGA"]
    textos += [f["descricao"] for f in dados["fases_ciclo_vida"]]
    textos += [p[c] for p in dados["pragas_comuns"] for c in ("sintomas", "prevencao_tratamento")]
    textos += [t["instrucoes"] for t in dados["tarefas_agendadas"] if t["tipo"] != "REGA"]
    textos += [dados["planta"]["descricao"]]
    vistas = []
    for texto in textos:
        for frase in (f.strip() for f in FRASE.split(texto)):
            if SOBRE_TEMPERATURA.search(frase) and frase not in vistas:
                vistas.append(frase)
    return vistas[:MAX_FRASES]


PROMPT = """Para cada planta abaixo, cultivada em vaso no Centro-Sul do Brasil, dê dois números inteiros em °C:

- minima: a menor temperatura que a planta em vaso, ao ar livre, aguenta sem dano (folha queimada, broto morto, raiz que apodrece no frio). É onde o app vai avisar "abrigue hoje à noite". Planta que aguenta geada leva o valor negativo que aguenta. Não é a temperatura em que ela só para de crescer, nem o frio de que ela precisa para florescer.
- maxima: a partir de quanto o calor pede cuidado extra (o vaso seca em poucas horas, a folha queima, a flor aborta). É onde o app vai avisar "confira a rega também à tarde".

As frases de cada planta vêm do guia de cultivo dela no app. Quando uma frase dá o limite ("com previsão abaixo de 5 °C, abrigue"; "acima de 30 °C, confira todo dia"), use esse número. Quando não dá, use o que você sabe da espécie.

Responda só com um bloco ```json: um objeto com o arquivo de cada planta como chave e [minima, maxima] como valor, ex.: {"girassol.json": [5, 30]}.
"""


def montar_prompt(lote):
    partes = [PROMPT]
    for arquivo, dados in lote:
        p, r = dados["planta"], dados["requisitos_iniciais"]
        partes.append(f"\n## {arquivo}: {p['nome_comum']} ({p['nome_cientifico']}), {p['tipo_ciclo']}, {r['luminosidade']}")
        partes += [f"- {f}" for f in frases(dados)] or ["- (o guia não fala de temperatura)"]
    return "\n".join(partes)


def perguntar(claude, numero, lote):
    """Um lote numa sessão nova do claude; devolve {arquivo: (mínima, máxima)} e o custo."""
    prompt = montar_prompt(lote)
    inicio = time.monotonic()
    # Fora do repositório, para não carregar o CLAUDE.md do projeto em cada lote.
    proc = subprocess.run(
        [claude, "-p", prompt, "--output-format", "json", "--model", MODELO, "--effort", ESFORCO,
         "--disallowedTools", "Bash", "PowerShell", "Write", "Edit", "NotebookEdit", "Read", "Glob", "Grep"],
        cwd=tempfile.gettempdir(), capture_output=True, timeout=10 * 60,
    )
    saida = proc.stdout.decode("utf-8", "replace")
    registro = {"lote": numero, "segundos": round(time.monotonic() - inicio, 1), "prompt": prompt}
    try:
        resultado = json.loads(saida)
    except json.JSONDecodeError:
        registro["erro"] = saida + proc.stderr.decode("utf-8", "replace")
        resultado = {}
    texto = resultado.get("result") or ""
    registro.update({"custo_usd": resultado.get("total_cost_usd"), "resposta": texto})
    (RESPOSTAS / f"lote-{numero:02d}.json").write_text(json.dumps(registro, ensure_ascii=False, indent=2), encoding="utf-8")
    if LIMITE_PLANO.search(texto):
        sys.exit(f"Limite de uso do plano: {texto.strip()}")

    blocos = BLOCO.findall(texto)
    try:
        bruto = json.loads(blocos[0]) if len(blocos) == 1 else {}
    except json.JSONDecodeError:
        bruto = {}
    numeros = {}
    for arquivo, _ in lote:
        par = bruto.get(arquivo)
        if (isinstance(par, list) and len(par) == 2 and all(isinstance(n, int) and -20 <= n <= 50 for n in par)
                and par[0] < par[1]):
            numeros[arquivo] = tuple(par)
    return numeros, resultado.get("total_cost_usd") or 0.0


def inserir(texto, minima, maxima):
    """Põe os dois campos logo depois de meses_plantio, na mesma indentação."""
    casamento = MESES.search(texto)
    inicio_linha = texto.rfind("\n", 0, casamento.start()) + 1
    recuo = texto[inicio_linha:casamento.start()]
    novos = f',\n{recuo}"temperatura_minima_c": {minima},\n{recuo}"temperatura_maxima_c": {maxima}'
    return texto[:casamento.end()] + novos + texto[casamento.end():]


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    args.add_argument("--limite", type=int, help="quantas plantas nesta rodada (padrão: todas as que faltam)")
    args.add_argument("--seco", action="store_true", help="só mostra o que o texto já diz, sem chamar o claude")
    args.add_argument("--paralelo", type=int, default=3, help="sessões do claude ao mesmo tempo")
    opcoes = args.parse_args()

    pendentes = []
    for caminho in sorted(CATALOGO.glob("*.json")):
        dados = json.loads(caminho.read_text(encoding="utf-8-sig"))
        if "temperatura_minima_c" not in dados["planta"]:
            pendentes.append((caminho.name, dados))
    pendentes = pendentes[:opcoes.limite]
    textuais = {arquivo: do_texto(dados) for arquivo, dados in pendentes}
    print(f"{len(pendentes)} planta(s) sem temperatura. O texto já fixa a mínima em "
          f"{sum(m is not None for m, _ in textuais.values())} e a máxima em "
          f"{sum(x is not None for _, x in textuais.values())}.")
    if opcoes.seco or not pendentes:
        return 0

    RESPOSTAS.mkdir(parents=True, exist_ok=True)
    claude = achar_claude()
    lotes = [pendentes[i:i + POR_LOTE] for i in range(0, len(pendentes), POR_LOTE)]
    print(f"{len(lotes)} lote(s) com {MODELO}, esforço {ESFORCO}, {opcoes.paralelo} em paralelo")
    respostas, custo = {}, 0.0
    with ThreadPoolExecutor(opcoes.paralelo) as executor:
        for numeros, gasto in executor.map(lambda par: perguntar(claude, *par), enumerate(lotes, start=1)):
            respostas.update(numeros)
            custo += gasto
            print(f"… {len(respostas)}/{len(pendentes)} respondidas", flush=True)

    concorda = {"minima": [0, 0], "maxima": [0, 0]}
    divergencias, gravadas, sem_resposta = [], 0, []
    for arquivo, dados in pendentes:
        if arquivo not in respostas:
            sem_resposta.append(arquivo)
            continue
        final = list(respostas[arquivo])
        for i, (campo, do_guia) in enumerate(zip(("minima", "maxima"), textuais[arquivo])):
            if do_guia is None:
                continue
            concorda[campo][1] += 1
            if final[i] == do_guia:
                concorda[campo][0] += 1
            else:
                divergencias.append(f"{arquivo} {campo}: claude {final[i]}, texto {do_guia}")
                final[i] = do_guia
        if final[0] >= final[1]:
            sem_resposta.append(f"{arquivo} (mínima {final[0]} >= máxima {final[1]} depois do texto)")
            continue
        caminho = CATALOGO / arquivo
        novo = inserir(caminho.read_text(encoding="utf-8"), *final)
        problemas = validar.validar(json.loads(novo))
        if problemas:
            sem_resposta.append(f"{arquivo}: {problemas}")
            continue
        caminho.write_text(novo, encoding="utf-8")
        gravadas += 1

    print(f"\n{gravadas} gravada(s), custo US$ {custo:.2f}")
    for campo, (iguais, total) in concorda.items():
        if total:
            print(f"{campo}: o claude bateu com o texto em {iguais}/{total} ({100 * iguais // total}%)")
    if divergencias:
        print("\nDivergências (vale o texto):\n" + "\n".join(divergencias))
    if sem_resposta:
        print("\nSem resposta válida (rode de novo):\n" + "\n".join(sem_resposta))
    return 1 if sem_resposta else 0


if __name__ == "__main__":
    sys.exit(main())
