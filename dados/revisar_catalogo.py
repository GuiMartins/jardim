"""Revisa o conteúdo dos JSONs do catálogo com a skill revisar-planta.

O validar.py confere a forma do JSON; esta revisão confere o conteúdo contra
fontes na web (nome científico, temperaturas, rega, época, doses). Cada planta
roda numa sessão nova do claude em dados/cache/revisao/<planta>/, com uma cópia
do JSON na própria pasta: a skill lê a cópia, nunca o catálogo. Os achados vão
para achados.json ao lado, com o hash da cópia; a planta só é revista de novo
se o JSON mudar (ou com --refazer).

    python dados/revisar_catalogo.py --calibrar           # erros plantados: quantos a skill acha?
    python dados/revisar_catalogo.py --amostra 30         # amostra estratificada por categoria
    python dados/revisar_catalogo.py girassol.json alecrim.json
    python dados/revisar_catalogo.py --todas --esperar     # tudo, retomando a cada limite do plano
    python dados/revisar_catalogo.py --relatorio          # taxa de erro da amostra e lista de achados

A calibração copia os JSONs de .claude/skills/revisar-planta/evals/erros_plantados.json
com um erro conhecido em cada um e mede quantos a skill acha. Sem ela, "a
revisão não achou nada" não quer dizer nada. As cópias ficam em
dados/cache/revisao-b/, e não "calibracao", para o nome da pasta, que o claude
vê, não avisar que ali tem erro de propósito.

A amostra sorteia plantas de cada categoria (ver alocar) e mede quantas têm
algum achado ALTA. O relatório vai para dados/cache/revisao/relatorio.md, com
os achados em checklist para aprovar um por um.
"""
import argparse
import hashlib
import json
import math
import os
import random
import re
import subprocess
import sys
import threading
import time
from collections import Counter, defaultdict
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from gerar_catalogo import (BLOCO, CATALOGO, FILA, LIMITE_PLANO, PASSAGEIRO, Parar, Passageiro,
                            achar_claude, validar)

PASTA = Path(__file__).parent
REVISAO = PASTA / "cache" / "revisao"
CALIBRACAO = PASTA / "cache" / "revisao-b"
AMOSTRA = REVISAO / "amostra.json"
RELATORIO = REVISAO / "relatorio.md"
LISTA = REVISAO / "achados.md"
ERROS = PASTA.parent / ".claude/skills/revisar-planta/evals/erros_plantados.json"
# Outro modelo que não o da geração (opus): quem revisa não herda os mesmos enganos.
# A calibração diz se ele dá conta; se não der, --modelo opus.
MODELO = "sonnet"
ESFORCO = "medium"
TENTATIVAS = 2
TEMPO_LIMITE = 20 * 60
ESPERAS = (60, 120, 240)
GRAVIDADES = ("ALTA", "MEDIA", "BAIXA")

PASSO = re.compile(r"\.?([^.\[\]]+)|\[([^\]]+)\]")
# "- [x] ... <!-- roseira.json#3 -->": a marca que o usuário põe e o achado a que ela se refere.
MARCA = re.compile(r"^- \[([ xX-])\] .*<!-- (\S+\.json)#(\d+) -->\s*$")


def resolver(dados, caminho):
    """(recipiente, chave, canônico, genérico) do campo, ou None se o caminho não existe.

    O canônico escreve o item de lista pelo id, quando ele tem um:
    "tarefas_agendadas[2]" e "tarefas_agendadas[rega_padrao]" dão no mesmo.
    O genérico junta o mesmo campo de plantas diferentes ("tarefas_agendadas[REGA].frequencia_dias",
    "pragas_comuns[].prevencao_tratamento"), para achar o erro que se repete.
    """
    passos = list(PASSO.finditer(caminho or ""))
    if not passos or "".join(p.group(0) for p in passos) != caminho:
        return None
    atual, recipiente, chave, canonico, generico = dados, None, None, "", ""
    for passo in passos:
        nome, seletor = passo.groups()
        if nome is not None:
            if not isinstance(atual, dict) or nome not in atual:
                return None
            recipiente, chave, atual = atual, nome, atual[nome]
            canonico += ("." if canonico else "") + nome
            generico += ("." if generico else "") + nome
            continue
        if not isinstance(atual, list):
            return None
        if seletor.isdigit():
            indice = int(seletor)
        else:
            indice = next((i for i, item in enumerate(atual) if isinstance(item, dict)
                           and seletor in (item.get("id"), item.get("fase_id"))), None)
        if indice is None or indice >= len(atual):
            return None
        item = atual[indice]
        ident = (item.get("id") or item.get("fase_id")) if isinstance(item, dict) else None
        recipiente, chave, atual = atual, indice, item
        canonico += f"[{ident or indice}]"
        generico += f"[{item['tipo']}]" if isinstance(item, dict) and item.get("id") and item.get("tipo") else "[]"
    return recipiente, chave, canonico, generico


def categorias():
    """{arquivo do catálogo: categoria} de todas as espécies que já têm JSON."""
    fila = json.loads(FILA.read_text(encoding="utf-8-sig"))["plantas"]
    da_fila = {p["arquivo_catalogo"]: p.get("categoria") for p in fila if p.get("arquivo_catalogo")}
    return {c.name: da_fila.get(c.name) or "SEM_CATEGORIA" for c in sorted(CATALOGO.glob("*.json"))}


def por_popularidade():
    """Os arquivos do catálogo, da planta mais popular à menos: se a rodada parar, o que importa já foi."""
    fila = json.loads(FILA.read_text(encoding="utf-8-sig"))["plantas"]
    posicao = {}
    for p in fila:
        if p.get("arquivo_catalogo"):
            posicao[p["arquivo_catalogo"]] = min(p["posicao"], posicao.get(p["arquivo_catalogo"], math.inf))
    return sorted(categorias(), key=lambda a: (posicao.get(a, math.inf), a))


def alocar(tamanhos, n):
    """Quantas plantas sortear em cada categoria, somando n.

    tamanhos é {categoria: espécies no catálogo}; hoje são 16 categorias, de
    ARVORE (115) a SAMAMBAIA (4), e n costuma ser ~30.

    Uma por categoria primeiro, se couber: o erro que se repete costuma ser de
    um tipo de planta (orquídea, aquática, árvore em vaso), e uma categoria sem
    sorteio nunca aparece. O resto vai proporcional ao tamanho, arredondado pelo
    maior resto para somar n. O relatório pondera cada categoria pelo tamanho
    dela, então a taxa do catálogo não fica puxada para as categorias pequenas.
    """
    n = min(n, sum(tamanhos.values()))
    base = {c: int(n >= len(tamanhos) and t > 0) for c, t in tamanhos.items()}
    livres = {c: t - base[c] for c, t in tamanhos.items()}
    resto = n - sum(base.values())
    cotas = {c: resto * t / sum(livres.values()) for c, t in livres.items()}
    quantos = {c: base[c] + int(cotas[c]) for c in tamanhos}
    for c in sorted(cotas, key=lambda c: cotas[c] - int(cotas[c]), reverse=True)[:n - sum(quantos.values())]:
        quantos[c] += 1
    return quantos


def sortear(n, semente):
    por_categoria = defaultdict(list)
    for arquivo, categoria in categorias().items():
        por_categoria[categoria].append(arquivo)
    tamanhos = {c: len(a) for c, a in por_categoria.items()}
    quantos = alocar(tamanhos, n)
    sorteio = random.Random(semente)
    escolhidos = {}
    for categoria in sorted(por_categoria):
        for arquivo in sorteio.sample(por_categoria[categoria], quantos.get(categoria, 0)):
            escolhidos[arquivo] = categoria
    REVISAO.mkdir(parents=True, exist_ok=True)
    AMOSTRA.write_text(json.dumps({"n": n, "semente": semente, "tamanhos": tamanhos, "arquivos": escolhidos},
                                  ensure_ascii=False, indent=2), encoding="utf-8")
    return escolhidos


def plantar_erros():
    """[(arquivo, dados com o erro, {canônico do campo errado})] a partir de erros_plantados.json."""
    lote = []
    for erro in json.loads(ERROS.read_text(encoding="utf-8")):
        dados = json.loads((CATALOGO / erro["arquivo"]).read_text(encoding="utf-8-sig"))
        campos = set()
        for caminho, valor in erro["trocas"].items():
            achado = resolver(dados, caminho)
            if achado is None:
                sys.exit(f"{erro['arquivo']}: o campo {caminho} não existe")
            recipiente, chave, canonico, _ = achado
            if isinstance(valor, dict):
                if valor["de"] not in recipiente[chave]:
                    sys.exit(f"{erro['arquivo']}: {caminho} não contém {valor['de']!r}")
                recipiente[chave] = recipiente[chave].replace(valor["de"], valor["para"], 1)
            else:
                recipiente[chave] = valor
            campos.add(canonico)
        problemas = validar.validar(dados)
        if problemas:
            # Um erro que o validador pega entrega a resposta: a skill nem precisaria de fonte.
            sys.exit(f"{erro['arquivo']}: o erro plantado quebra o validar.py: {problemas}")
        lote.append((erro["arquivo"], dados, campos))
    return lote


def copia(dados):
    return json.dumps(dados, ensure_ascii=False, indent=2) + "\n"


def hash_de(dados):
    return hashlib.sha1(copia(dados).encode("utf-8")).hexdigest()


def conferir_resposta(bruto, dados):
    """Problemas de formato na resposta da skill (lista vazia se ela serve)."""
    if not isinstance(bruto, dict) or not isinstance(bruto.get("achados"), list):
        return ["a resposta não tem a lista 'achados'"]
    problemas = []
    for i, achado in enumerate(bruto["achados"]):
        if not isinstance(achado, dict):
            problemas.append(f"achado {i} não é objeto")
            continue
        if resolver(dados, achado.get("campo")) is None:
            problemas.append(f"achado {i}: o campo {achado.get('campo')!r} não existe no JSON")
        if achado.get("gravidade") not in GRAVIDADES:
            problemas.append(f"achado {i}: gravidade {achado.get('gravidade')!r}")
        fontes = achado.get("fontes")
        if not (isinstance(fontes, list) and fontes and all(str(f).startswith("http") for f in fontes)):
            problemas.append(f"achado {i} sem fonte")
        if "proposto" not in achado:
            problemas.append(f"achado {i} sem 'proposto'")
    return problemas


def chamar(claude, arquivo, pasta, modelo, esforco):
    """Roda a skill uma vez sobre a cópia em pasta/arquivo; devolve (resposta ou None, problemas, metadados)."""
    prompt = f"/revisar-planta {arquivo}"
    inicio = time.monotonic()
    # Read fora da pasta é para a skill ler as seções do formato na outra skill.
    proc = subprocess.run(
        [claude, "-p", prompt, "--output-format", "json", "--model", modelo, "--effort", esforco,
         "--allowedTools", "Read", "WebSearch", "WebFetch",
         "--disallowedTools", "Bash", "PowerShell", "Write", "Edit", "NotebookEdit"],
        cwd=pasta, capture_output=True, timeout=TEMPO_LIMITE,
    )
    saida = proc.stdout.decode("utf-8", "replace")
    meta = {"prompt": prompt, "modelo": modelo, "segundos": round(time.monotonic() - inicio, 1),
            "codigo_saida": proc.returncode}
    try:
        resultado = json.loads(saida)
    except json.JSONDecodeError:
        (pasta / "erro.txt").write_text(saida + proc.stderr.decode("utf-8", "replace"), encoding="utf-8")
        return None, ["o claude não devolveu JSON (ver erro.txt)"], meta

    meta.update({"custo_usd": resultado.get("total_cost_usd"), "turnos": resultado.get("num_turns")})
    texto = resultado.get("result") or ""
    (pasta / "resposta.md").write_text(texto, encoding="utf-8")
    if resultado.get("is_error") or resultado.get("terminal_reason") == "api_error":
        if "login" in texto.lower():
            raise Parar(f"{texto.strip()}\nFaça login uma vez rodando o claude no terminal e digitando /login.")
        if LIMITE_PLANO.search(texto):
            raise Parar(f"Limite de uso do plano: {texto.strip()}\nRode de novo depois que ele renovar; "
                        "o script continua de onde parou.")
        if PASSAGEIRO.search(texto) or not texto.strip():
            raise Passageiro(texto.strip() or "erro da API sem mensagem")
        return None, [f"erro do claude: {texto.strip()[:150]}"], meta

    blocos = BLOCO.findall(texto)
    if len(blocos) != 1:
        return None, [f"esperava 1 bloco ```json, veio {len(blocos)}"], meta
    try:
        bruto = json.loads(blocos[0])
    except json.JSONDecodeError as erro:
        return None, [f"JSON inválido: {erro}"], meta
    return bruto, conferir_resposta(bruto, json.loads((pasta / arquivo).read_text(encoding="utf-8"))), meta


def revisar(claude, arquivo, dados, base, opcoes, parar):
    """Devolve (arquivo, tentativas); tentativas é None se já estava revisada ou a rodada parou.

    dados None: lê o catálogo agora, e não na partida. Uma rodada longa não pode
    revisar a versão velha de uma planta que o --aplicar corrigiu no meio dela.
    """
    if dados is None:
        dados = json.loads((CATALOGO / arquivo).read_text(encoding="utf-8-sig"))
    pasta = base / Path(arquivo).stem
    pasta.mkdir(parents=True, exist_ok=True)
    hash_ = hash_de(dados)
    salvo = pasta / "achados.json"
    if not opcoes.refazer and salvo.exists() and json.loads(salvo.read_text(encoding="utf-8")).get("sha1") == hash_:
        return arquivo, None
    if parar.is_set():
        return arquivo, None
    (pasta / arquivo).write_text(copia(dados), encoding="utf-8")
    tentativas = []
    for _ in range(TENTATIVAS):
        for espera in (*ESPERAS, None):
            try:
                bruto, problemas, meta = chamar(claude, arquivo, pasta, opcoes.modelo, opcoes.esforco)
                break
            except subprocess.TimeoutExpired:
                bruto, problemas, meta = None, [f"passou de {TEMPO_LIMITE // 60} min"], {}
                break
            except Passageiro as erro:
                if espera is None or parar.is_set():
                    bruto, problemas, meta = None, [f"API indisponível: {str(erro)[:120]}"], {}
                    break
                print(f"… {arquivo}: {str(erro)[:80]}; nova tentativa em {espera // 60} min", flush=True)
                time.sleep(espera)
            except Parar:
                parar.set()
                raise
        meta["problemas"] = problemas
        tentativas.append(meta)
        if bruto is not None and not problemas:
            salvo.write_text(json.dumps({"sha1": hash_, "modelo": opcoes.modelo, **bruto},
                                        ensure_ascii=False, indent=2), encoding="utf-8")
            break
    (pasta / "tentativas.json").write_text(json.dumps(tentativas, ensure_ascii=False, indent=2), encoding="utf-8")
    return arquivo, tentativas


def rodar(lote, base, opcoes):
    """Revisa [(arquivo, dados)] em paralelo; devolve o motivo da parada, se houve."""
    if not lote:
        return None
    claude = achar_claude()
    if sys.platform == "win32":
        # Cada planta leva minutos navegando: pede ao Windows para não suspender (ES_CONTINUOUS | ES_SYSTEM_REQUIRED).
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)
    print(f"Revisando até {len(lote)} planta(s) com {opcoes.modelo}, esforço {opcoes.esforco}, "
          f"{opcoes.paralelo} em paralelo")
    parar, motivo, custo, feitas = threading.Event(), [], 0.0, 0

    def tarefa(item):
        try:
            return revisar(claude, *item, base, opcoes, parar)
        except Parar as erro:
            motivo.append(str(erro))
            return item[0], None

    def relatar(arquivo, tentativas):
        nonlocal custo, feitas
        if tentativas is None:
            return
        feitas += 1
        custo += sum(t.get("custo_usd") or 0 for t in tentativas)
        ultima = tentativas[-1]
        if ultima["problemas"]:
            print(f"✗ [{feitas}] {arquivo}: " + "; ".join(ultima["problemas"][:3]), flush=True)
            return
        achados = json.loads((base / Path(arquivo).stem / "achados.json").read_text(encoding="utf-8"))["achados"]
        por_gravidade = Counter(a["gravidade"] for a in achados)
        resumo = ", ".join(f"{por_gravidade[g]} {g}" for g in GRAVIDADES if por_gravidade[g]) or "nenhum achado"
        print(f"✓ [{feitas}] {arquivo}: {resumo} ({ultima.get('segundos')} s, "
              f"US$ {sum(t.get('custo_usd') or 0 for t in tentativas):.2f})", flush=True)

    # A primeira vai sozinha: se faltar login ou limite, para aqui sem abrir as outras.
    relatar(*tarefa(lote[0]))
    if not parar.is_set():
        with ThreadPoolExecutor(opcoes.paralelo) as pool:
            for resultado in pool.map(tarefa, lote[1:]):
                relatar(*resultado)
    if feitas:
        print(f"{feitas} revisada(s) nesta rodada, US$ {custo:.2f} (média US$ {custo / feitas:.2f})")
    return motivo[0] if motivo else None


def ler_achados(base, arquivo):
    salvo = base / Path(arquivo).stem / "achados.json"
    return json.loads(salvo.read_text(encoding="utf-8")) if salvo.exists() else None


def pontuar(lote):
    """Quantos erros plantados a skill achou, e quanto mais ela apontou nas mesmas plantas."""
    esperados = {e["arquivo"]: e for e in json.loads(ERROS.read_text(encoding="utf-8"))}
    pegos, revisadas = 0, 0
    print("\nCalibração (erro plantado → achado):")
    for arquivo, dados, campos in lote:
        salvo = ler_achados(CALIBRACAO, arquivo)
        if salvo is None:
            print(f"  ? {arquivo}: sem revisão")
            continue
        revisadas += 1
        certeiros, outros = [], []
        for achado in salvo["achados"]:
            canonico = (resolver(dados, achado["campo"]) or (0, 0, achado["campo"]))[2]
            alvo = any(canonico == c or canonico.startswith((c + ".", c + "[")) for c in campos)
            (certeiros if alvo else outros).append(achado)
        erro = esperados[arquivo]
        if certeiros:
            pegos += 1
            gravidades = "/".join(sorted({a["gravidade"] for a in certeiros}))
            print(f"  ✓ {arquivo}: {erro['erro']} (achou como {gravidades}, esperado {erro['gravidade']}; "
                  f"+{len(outros)} outro(s))")
        else:
            print(f"  ✗ {arquivo}: {erro['erro']} (não achou; {len(outros)} outro(s) achado(s))")
    if revisadas:
        print(f"\nAchou {pegos} de {revisadas} erros plantados ({100 * pegos // revisadas}%).")


def mudanca(atual, proposto):
    """O que o achado muda: o valor inteiro, ou, num texto, só o trecho que muda, com um pouco de contexto."""
    if not (isinstance(atual, str) and isinstance(proposto, str)):
        return f"{json.dumps(atual, ensure_ascii=False)} → {json.dumps(proposto, ensure_ascii=False)}"
    ini = len(os.path.commonprefix([atual, proposto]))
    fim = len(os.path.commonprefix([atual[ini:][::-1], proposto[ini:][::-1]]))
    tirado, posto = atual[ini:len(atual) - fim], proposto[ini:len(proposto) - fim]
    antes = ("…" if ini > 40 else "") + atual[max(0, ini - 40):ini]
    depois = atual[len(atual) - fim:][:40] + ("…" if fim > 40 else "")
    return f"«{antes}{f'~~{tirado}~~' if tirado else ''}{f'**{posto}**' if posto else ''}{depois}»"


def linha_achado(arquivo, i, achado):
    fontes = " ".join(f"[{n + 1}]({f})" for n, f in enumerate(achado["fontes"]))
    return (f"- [ ] **{achado['gravidade']}** `{achado['campo']}`: {mudanca(achado.get('atual'), achado['proposto'])}. "
            f"{achado.get('motivo', '')} {fontes} <!-- {arquivo}#{i} -->")


def lista():
    """Todos os achados ainda sem decisão, por gravidade e, dentro dela, da planta mais popular à menos."""
    nomes, desatualizadas = {}, []
    por_gravidade = {g: [] for g in GRAVIDADES}
    for arquivo in por_popularidade():
        salvo = ler_achados(REVISAO, arquivo)
        if not salvo:
            continue
        dados = json.loads((CATALOGO / arquivo).read_text(encoding="utf-8-sig"))
        if hash_de(dados) != salvo["sha1"]:
            desatualizadas.append(arquivo)
            continue
        nomes[arquivo] = dados["planta"]["nome_comum"]
        decididos = salvo.get("decisoes", {})
        for i, a in enumerate(salvo["achados"]):
            if str(i) not in decididos:
                por_gravidade[a["gravidade"]].append((arquivo, i, a))
    linhas = ["# Achados da revisão do catálogo", "",
              "Marque `[x]` para aplicar ou `[-]` para recusar e rode `python dados/revisar_catalogo.py --aplicar`. "
              "O que ficar `[ ]` volta na próxima lista. Um limite de temperatura e o texto que repete o número "
              "costumam vir em achados separados: aprove os dois juntos.", ""]
    for gravidade, itens in por_gravidade.items():
        linhas += [f"## {gravidade} ({len(itens)})", ""]
        anterior = None
        for arquivo, i, a in itens:
            if arquivo != anterior:
                linhas.append(f"### {nomes[arquivo]} (`{arquivo}`)")
                anterior = arquivo
            linhas.append(linha_achado(arquivo, i, a))
        linhas.append("")
    if desatualizadas:
        linhas += ["## JSON mudou depois da revisão (rode de novo para revisar)", "",
                   ", ".join(f"`{a}`" for a in desatualizadas), ""]
    LISTA.write_text("\n".join(linhas), encoding="utf-8")
    print(", ".join(f"{len(v)} {g}" for g, v in por_gravidade.items()) + f" sem decisão → {LISTA}")


def aplicar(caminho):
    """Aplica os achados marcados [x] e registra os [-] como recusados, em achados.json.

    Os JSONs gerados são reescritos com json.dumps, que é o formato em que o
    gerar_catalogo.py os gravou. Os feitos à mão (girassol, rabanete) só têm o
    valor trocado no texto, para o diff não virar o arquivo inteiro.
    """
    marcas = defaultdict(dict)
    for linha in Path(caminho).read_text(encoding="utf-8").splitlines():
        casamento = MARCA.match(linha)
        if casamento and casamento.group(1) != " ":
            marcas[casamento.group(2)][casamento.group(3)] = "aplicado" if casamento.group(1) in "xX" else "recusado"
    aplicados, recusados, falhas = 0, 0, []
    for arquivo, decisoes in marcas.items():
        salvo = ler_achados(REVISAO, arquivo)
        destino = CATALOGO / arquivo
        texto = destino.read_text(encoding="utf-8-sig")
        dados = json.loads(texto)
        if salvo is None or hash_de(dados) != salvo["sha1"]:
            falhas.append(f"{arquivo}: o JSON mudou depois da revisão; revise de novo")
            continue
        a_mao = copia(dados) != texto
        feitas = {}
        for i, decisao in decisoes.items():
            if decisao == "recusado":
                feitas[i] = decisao
                continue
            achado = salvo["achados"][int(i)]
            recipiente, chave = resolver(dados, achado["campo"])[:2]
            if recipiente[chave] != achado.get("atual"):
                falhas.append(f"{arquivo} {achado['campo']}: o valor citado no achado não é o do JSON; corrija à mão")
                continue
            if a_mao:
                rotulo = f'"{chave}": ' if isinstance(chave, str) else ""
                velho = rotulo + json.dumps(recipiente[chave], ensure_ascii=False)
                if texto.count(velho) != 1:
                    falhas.append(f"{arquivo} {achado['campo']}: arquivo feito à mão e valor ambíguo; corrija à mão")
                    continue
                texto = texto.replace(velho, rotulo + json.dumps(achado["proposto"], ensure_ascii=False))
            recipiente[chave] = achado["proposto"]
            feitas[i] = decisao
        problemas = validar.validar(dados)
        if problemas:
            falhas.append(f"{arquivo}: as correções quebram o validar.py ({'; '.join(problemas[:2])}); nada gravado")
            continue
        destino.write_text(texto if a_mao else copia(dados), encoding="utf-8")
        # O hash novo marca a planta como revisada: a rodada não a revisa de novo por causa das próprias correções.
        salvo["sha1"] = hash_de(dados)
        salvo.setdefault("decisoes", {}).update(feitas)
        (REVISAO / Path(arquivo).stem / "achados.json").write_text(
            json.dumps(salvo, ensure_ascii=False, indent=2), encoding="utf-8")
        aplicados += sum(d == "aplicado" for d in feitas.values())
        recusados += sum(d == "recusado" for d in feitas.values())
    print(f"{aplicados} aplicado(s) e {recusados} recusado(s) em {len(marcas)} planta(s).")
    for falha in falhas:
        print(f"✗ {falha}")
    if aplicados:
        print("Rode ./gradlew :motor:test, que valida o catálogo inteiro.")
    return 1 if falhas else 0


def wilson(p, n, z=1.96):
    """Intervalo de 95% para uma proporção; com amostra pequena e p perto de 0, o normal daria negativo."""
    if n == 0:
        return 0.0, 1.0
    centro = (p + z * z / (2 * n)) / (1 + z * z / n)
    margem = z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n)) / (1 + z * z / n)
    return max(0.0, centro - margem), min(1.0, centro + margem)


def relatorio():
    if not AMOSTRA.exists():
        sys.exit("Sem amostra: rode antes com --amostra N.")
    amostra = json.loads(AMOSTRA.read_text(encoding="utf-8"))
    tamanhos, escolhidos = amostra["tamanhos"], amostra["arquivos"]
    total = sum(tamanhos.values())
    nomes = {c.name: json.loads(c.read_text(encoding="utf-8-sig"))["planta"]["nome_comum"]
             for c in CATALOGO.glob("*.json") if c.name in escolhidos}

    revisadas = {a: s for a in escolhidos if (s := ler_achados(REVISAO, a))}
    faltam = [a for a in escolhidos if a not in revisadas]
    if not revisadas:
        sys.exit("Nenhuma planta da amostra foi revisada ainda.")

    def taxa(gravidades):
        """Fração do catálogo com achado dessas gravidades: cada categoria pesa o tamanho dela no catálogo."""
        por_categoria = defaultdict(lambda: [0, 0])
        for arquivo, salvo in revisadas.items():
            conta = por_categoria[escolhidos[arquivo]]
            conta[0] += any(a["gravidade"] in gravidades for a in salvo["achados"])
            conta[1] += 1
        coberto = sum(tamanhos[c] for c in por_categoria)
        p = sum(tamanhos[c] * k / n for c, (k, n) in por_categoria.items()) / coberto
        baixo, alto = wilson(p, len(revisadas))
        k = sum(k for k, _ in por_categoria.values())
        return (f"{k} de {len(revisadas)}; no catálogo, {100 * p:.0f}% "
                f"(IC 95%: {100 * baixo:.0f}% a {100 * alto:.0f}%, ~{round(p * total)} de {total} espécies)")

    repetidos = Counter()
    for arquivo, salvo in revisadas.items():
        dados = json.loads((REVISAO / Path(arquivo).stem / arquivo).read_text(encoding="utf-8"))
        repetidos.update({(resolver(dados, a["campo"]) or (0, 0, 0, a["campo"]))[3]
                          for a in salvo["achados"] if a["gravidade"] != "BAIXA"})
    sem_amostra = sorted(c for c in tamanhos if c not in set(escolhidos.values()))

    linhas = [f"# Revisão do catálogo: amostra de {amostra['n']} (semente {amostra['semente']})", "",
              f"- Revisadas: {len(revisadas)} de {len(escolhidos)}" + (f" (faltam {', '.join(faltam)})" if faltam else ""),
              f"- Com achado ALTA: {taxa({'ALTA'})}",
              f"- Com achado ALTA ou MEDIA: {taxa({'ALTA', 'MEDIA'})}"]
    if sem_amostra:
        linhas.append(f"- Categorias fora da amostra (não entram na taxa): {', '.join(sem_amostra)}")
    linhas += ["", "## Campos que mais erram (ALTA ou MEDIA, em quantas plantas)", ""]
    linhas += [f"- `{campo}`: {n}" for campo, n in repetidos.most_common(10)] or ["- nenhum"]
    linhas += ["", "## Achados", ""]
    ordem = {g: i for i, g in enumerate(GRAVIDADES)}
    for arquivo, salvo in sorted(revisadas.items(), key=lambda par: (escolhidos[par[0]], par[0])):
        linhas.append(f"### {nomes.get(arquivo, arquivo)} (`{arquivo}`, {escolhidos[arquivo]})")
        linhas.append("")
        for i, a in sorted(enumerate(salvo["achados"]), key=lambda par: ordem[par[1]["gravidade"]]):
            linhas.append(linha_achado(arquivo, i, a))
        conferidos = ", ".join(f"`{c.get('campo')}`" for c in salvo.get("conferidos", []))
        linhas += [f"- Conferido e certo: {conferidos or 'nada informado'}", ""]
    RELATORIO.write_text("\n".join(linhas), encoding="utf-8")
    print("\n".join(linhas[:linhas.index("", 2)]))
    print(f"\nRelatório completo: {RELATORIO}")


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    args.add_argument("arquivos", nargs="*", help="JSONs do catálogo a revisar (ex.: girassol.json)")
    args.add_argument("--amostra", type=int, help="sorteia N plantas, estratificadas por categoria")
    args.add_argument("--semente", type=int, default=1, help="semente do sorteio (padrão: 1)")
    args.add_argument("--todas", action="store_true", help="o catálogo inteiro, das mais populares às menos")
    args.add_argument("--esperar", action="store_true",
                      help="no limite do plano, espera 30 min e continua, em vez de parar")
    args.add_argument("--calibrar", action="store_true", help="revisa cópias com erros plantados e pontua")
    args.add_argument("--relatorio", action="store_true", help="só refaz o relatório da amostra")
    args.add_argument("--lista", action="store_true", help="escreve achados.md com tudo o que falta decidir")
    args.add_argument("--aplicar", nargs="?", const=str(LISTA), metavar="ARQUIVO.md",
                      help=f"aplica os achados marcados [x] (padrão: {LISTA.name})")
    args.add_argument("--seco", action="store_true", help="sorteia ou planta os erros, sem chamar o claude")
    args.add_argument("--refazer", action="store_true", help="revisa de novo mesmo quem já tem achados")
    args.add_argument("--modelo", default=MODELO, help=f"modelo do claude (padrão: {MODELO})")
    args.add_argument("--esforco", default=ESFORCO, help=f"low, medium, high, xhigh ou max (padrão: {ESFORCO})")
    args.add_argument("--paralelo", type=int, default=3, help="sessões do claude ao mesmo tempo")
    opcoes = args.parse_args()

    if opcoes.aplicar:
        return aplicar(opcoes.aplicar)
    if opcoes.lista:
        lista()
        return 0
    if opcoes.calibrar:
        plantados = plantar_erros()
        print(f"{len(plantados)} erro(s) plantado(s); o validar.py passa em todos.")
        motivo = None if opcoes.seco else rodar([(a, d) for a, d, _ in plantados], CALIBRACAO, opcoes)
        pontuar(plantados)
    elif opcoes.amostra or opcoes.arquivos or opcoes.todas:
        if opcoes.amostra:
            escolhidos = sortear(opcoes.amostra, opcoes.semente)
            print(f"Amostra de {len(escolhidos)}: " + ", ".join(f"{a} ({c})" for a, c in escolhidos.items()))
        elif opcoes.todas:
            escolhidos = por_popularidade()
        else:
            escolhidos = opcoes.arquivos
        motivo = None
        if not opcoes.seco:
            lote = [(a, None) for a in escolhidos]
            motivo = rodar(lote, REVISAO, opcoes)
            while motivo and opcoes.esperar and motivo.startswith("Limite de uso"):
                print(f"{time.strftime('%H:%M')} limite do plano; nova tentativa em 30 min", flush=True)
                time.sleep(30 * 60)
                motivo = rodar(lote, REVISAO, opcoes)
            if opcoes.amostra:
                relatorio()
    elif opcoes.relatorio:
        relatorio()
        return 0
    else:
        args.print_help()
        return 1
    if motivo:
        print(f"\nParou antes do fim. {motivo}")
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
