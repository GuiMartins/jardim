"""Gera JSONs do catálogo em lote, a partir da fila de plantas-populares.json.

Cada planta roda numa sessão nova do Claude Code em modo headless
(`claude -p "/planta-cultivo-json Nome (Nome científico)"`), para o contexto
não crescer de uma planta para outra. A resposta é conferida com o validar.py
da skill e só então gravada em app/src/main/assets/catalogo/<arquivo_catalogo>.
Na primeira vez, faça login no CLI (`claude`, depois `/login`): ele não usa o
login do app desktop.
Plantas que já têm arquivo no catálogo são puladas, então dá para parar e
retomar. Resposta bruta e custo de cada chamada ficam em dados/cache/geracao/.

    python dados/gerar_catalogo.py --limite 10
    python dados/gerar_catalogo.py --limite 50 --sem ARVORE,PALMEIRA --paralelo 4

Depois rode ./gradlew :motor:test, que valida o catálogo inteiro com o port
Kotlin do validador.
"""
import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

PASTA = Path(__file__).parent
FILA = PASTA / "plantas-populares.json"
CATALOGO = PASTA.parent / "app/src/main/assets/catalogo"
GERACAO = PASTA / "cache" / "geracao"
SKILL = Path.home() / ".claude/skills/planta-cultivo-json"
TENTATIVAS = 2
TEMPO_LIMITE = 15 * 60

sys.path.insert(0, str(SKILL / "scripts"))
import validar  # noqa: E402

BLOCO = re.compile(r"```json\s*(.*?)\s*```", re.S)


class SemLogin(Exception):
    """O CLI avulso não usa o login do app desktop: sem isso, nenhuma planta sai."""


def achar_claude():
    """O app desktop instala o CLI fora do PATH, numa pasta por versão.

    O app é da Microsoft Store: o Windows redireciona o AppData\\Roaming dele para
    LocalCache dentro do pacote. Dentro do app vale o primeiro caminho; num
    terminal comum, só o segundo existe.
    """
    if os.environ.get("CLAUDE_CLI"):
        return os.environ["CLAUDE_CLI"]
    if shutil.which("claude"):
        return shutil.which("claude")
    roaming = Path(os.environ.get("APPDATA", ""))
    pacotes = Path(os.environ.get("LOCALAPPDATA", "")) / "Packages"
    candidatos = [*roaming.glob("Claude/claude-code/*/claude.exe"),
                  *pacotes.glob("Claude_*/LocalCache/Roaming/Claude/claude-code/*/claude.exe")]
    versoes = sorted(candidatos,
                     key=lambda p: [int(n) if n.isdigit() else n for n in re.split(r"(\d+)", p.parent.name)])
    if not versoes:
        sys.exit("claude não encontrado: instale o Claude Code ou defina CLAUDE_CLI")
    return str(versoes[-1])


def chamar(claude, planta, pasta):
    """Roda a skill uma vez; devolve (dados ou None, problemas, metadados)."""
    prompt = f"/planta-cultivo-json {planta['nome_comum']} ({planta['nome_cientifico']})"
    inicio = time.monotonic()
    # Sem Bash e Write, a skill não grava rascunho nem roda o validar.py (ela
    # prevê o caso "sem Python" e revisa as regras sozinha). Com eles, ela
    # escrevia o JSON duas vezes, no arquivo e na resposta, e custava ~40% a
    # mais. Quem valida é este script, logo depois.
    proc = subprocess.run(
        [claude, "-p", prompt, "--output-format", "json",
         "--disallowedTools", "Bash", "PowerShell", "Write", "Edit", "NotebookEdit"],
        cwd=pasta, capture_output=True, timeout=TEMPO_LIMITE,
    )
    saida = proc.stdout.decode("utf-8", "replace")
    meta = {"prompt": prompt, "segundos": round(time.monotonic() - inicio, 1), "codigo_saida": proc.returncode}
    try:
        resultado = json.loads(saida)
    except json.JSONDecodeError:
        (pasta / "erro.txt").write_text(saida + proc.stderr.decode("utf-8", "replace"), encoding="utf-8")
        return None, ["o claude não devolveu JSON (ver erro.txt)"], meta

    uso = resultado.get("usage") or {}
    meta.update({"custo_usd": resultado.get("total_cost_usd"), "turnos": resultado.get("num_turns"),
                 "tokens_saida": uso.get("output_tokens"),
                 "tokens_entrada": sum(uso.get(k) or 0 for k in
                                       ("input_tokens", "cache_creation_input_tokens", "cache_read_input_tokens"))})
    texto = resultado.get("result") or ""
    (pasta / "resposta.md").write_text(texto, encoding="utf-8")
    if resultado.get("is_error"):
        if "login" in texto.lower():
            raise SemLogin(texto.strip())
        return None, [f"erro do claude: {texto.strip()[:150]}"], meta

    blocos = BLOCO.findall(texto)
    if len(blocos) != 1:
        return None, [f"esperava 1 bloco ```json, veio {len(blocos)}"], meta
    try:
        dados = json.loads(blocos[0])
    except json.JSONDecodeError as erro:
        return None, [f"JSON inválido: {erro}"], meta
    return dados, validar.validar(dados), meta


def gerar(claude, planta):
    pasta = GERACAO / Path(planta["arquivo_catalogo"]).stem
    pasta.mkdir(parents=True, exist_ok=True)
    tentativas = []
    for _ in range(TENTATIVAS):
        try:
            dados, problemas, meta = chamar(claude, planta, pasta)
        except subprocess.TimeoutExpired:
            dados, problemas, meta = None, [f"passou de {TEMPO_LIMITE // 60} min"], {}
        meta["problemas"] = problemas
        tentativas.append(meta)
        if dados is not None and not problemas:
            destino = CATALOGO / planta["arquivo_catalogo"]
            destino.write_text(json.dumps(dados, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            break
    (pasta / "tentativas.json").write_text(json.dumps(tentativas, ensure_ascii=False, indent=2), encoding="utf-8")
    return planta, tentativas


def main():
    sys.stdout.reconfigure(encoding="utf-8")
    args = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    args.add_argument("--limite", type=int, required=True, help="quantas plantas gerar nesta rodada")
    args.add_argument("--sem", default="", help="categorias a pular, separadas por vírgula (ex.: ARVORE,PALMEIRA)")
    args.add_argument("--paralelo", type=int, default=3, help="sessões do claude ao mesmo tempo")
    opcoes = args.parse_args()

    pular = {c.strip().upper() for c in opcoes.sem.split(",") if c.strip()}
    fila = json.loads(FILA.read_text(encoding="utf-8-sig"))["plantas"]
    pendentes = [p for p in sorted(fila, key=lambda p: p["posicao"])
                 if p["categoria"] not in pular and not (CATALOGO / p["arquivo_catalogo"]).exists()]
    lote = pendentes[:opcoes.limite]
    if not lote:
        print("Nada a gerar.")
        return 0

    claude = achar_claude()
    print(f"Gerando {len(lote)} planta(s) com {opcoes.paralelo} em paralelo ({claude})")
    inicio = time.monotonic()
    falhas, custo, saida = [], 0.0, 0

    def relatar(planta, tentativas):
        nonlocal custo, saida
        custo += sum(t.get("custo_usd") or 0 for t in tentativas)
        saida += sum(t.get("tokens_saida") or 0 for t in tentativas)
        ultima = tentativas[-1]
        if ultima["problemas"]:
            falhas.append(planta)
            print(f"✗ {planta['posicao']:>4} {planta['nome_comum']}: " + "; ".join(ultima["problemas"][:3]), flush=True)
        else:
            print(f"✓ {planta['posicao']:>4} {planta['nome_comum']} → {planta['arquivo_catalogo']} "
                  f"({len(tentativas)} tentativa(s), {ultima.get('segundos')} s, "
                  f"US$ {sum(t.get('custo_usd') or 0 for t in tentativas):.2f})", flush=True)

    try:
        # A primeira planta vai sozinha: se faltar login, para aqui sem abrir as outras.
        relatar(*gerar(claude, lote[0]))
    except SemLogin as erro:
        print(f"{erro}\nFaça login uma vez rodando no terminal:\n  \"{claude}\"\ne digite /login.")
        return 2
    with ThreadPoolExecutor(opcoes.paralelo) as pool:
        for resultado in pool.map(lambda p: gerar(claude, p), lote[1:]):
            relatar(*resultado)

    minutos = (time.monotonic() - inicio) / 60
    print(f"\n{len(lote) - len(falhas)}/{len(lote)} gerada(s) em {minutos:.1f} min; "
          f"US$ {custo:.2f} e {saida} tokens de saída no total "
          f"(média US$ {custo / len(lote):.2f} por planta)")
    return 1 if falhas else 0


if __name__ == "__main__":
    sys.exit(main())
