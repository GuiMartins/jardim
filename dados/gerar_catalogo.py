"""Gera JSONs do catálogo em lote, a partir da fila de plantas-populares.json.

Cada planta roda numa sessão nova do Claude Code em modo headless
(`claude -p "/planta-cultivo-json Nome (Nome científico)"`), para o contexto
não crescer de uma planta para outra. A resposta é conferida com o validar.py
da skill e só então gravada em app/src/main/assets/catalogo/<arquivo_catalogo>.
Na primeira vez, faça login no CLI (`claude`, depois `/login`): ele não usa o
login do app desktop.
Plantas que já têm arquivo no catálogo são puladas, então dá para parar e
retomar. Se o limite de uso do plano acabar, o script para e o resto fica para
a próxima rodada; erro passageiro da API (sobrecarga) espera e tenta de novo.
Resposta bruta e custo de cada chamada ficam em dados/cache/geracao/.

    python dados/gerar_catalogo.py                 # tudo o que falta
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
import threading
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
# Erro passageiro da API: espera 1, 2 e 4 minutos antes de desistir da planta.
ESPERAS = (60, 120, 240)
# Quase toda a saída é raciocínio, não o JSON: o esforço é o que mais pesa no custo.
MODELO = "opus"
ESFORCO = "medium"

sys.path.insert(0, str(SKILL / "scripts"))
import validar  # noqa: E402

BLOCO = re.compile(r"```json\s*(.*?)\s*```", re.S)
LIMITE_PLANO = re.compile(r"usage limit|hit your .*limit|limit reached|limite de uso", re.I)
PASSAGEIRO = re.compile(r"overloaded|rate.?limit|(429|500|502|503|529)|api error|timed? ?out|connection", re.I)


class Parar(Exception):
    """Sem login ou sem limite de uso nenhuma planta sai: parar tudo em vez de marcar falhas."""


class Passageiro(Exception):
    """Sobrecarga ou falha de rede da API: vale esperar e tentar de novo."""


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


def chamar(claude, planta, pasta, modelo=MODELO, esforco=ESFORCO):
    """Roda a skill uma vez; devolve (dados ou None, problemas, metadados)."""
    prompt = f"/planta-cultivo-json {planta['nome_comum']} ({planta['nome_cientifico']})"
    inicio = time.monotonic()
    # Sem Bash e Write, a skill não grava rascunho nem roda o validar.py (ela
    # prevê o caso "sem Python" e revisa as regras sozinha). Com eles, ela
    # escrevia o JSON duas vezes, no arquivo e na resposta, e custava ~40% a
    # mais. Quem valida é este script, logo depois.
    proc = subprocess.run(
        [claude, "-p", prompt, "--output-format", "json", "--model", modelo, "--effort", esforco,
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
        dados = json.loads(blocos[0])
    except json.JSONDecodeError as erro:
        return None, [f"JSON inválido: {erro}"], meta
    return dados, validar.validar(dados), meta


def chamar_com_espera(claude, planta, pasta, modelo, esforco, parar):
    for espera in (*ESPERAS, None):
        try:
            return chamar(claude, planta, pasta, modelo, esforco)
        except Passageiro as erro:
            if espera is None or parar.is_set():
                return None, [f"API indisponível: {str(erro)[:120]}"], {}
            print(f"… {planta['nome_comum']}: {str(erro)[:80]}; nova tentativa em {espera // 60} min", flush=True)
            time.sleep(espera)


def gerar(claude, planta, modelo, esforco, parar):
    """Devolve (planta, tentativas); tentativas é None se a rodada já tinha parado."""
    if parar.is_set():
        return planta, None
    pasta = GERACAO / Path(planta["arquivo_catalogo"]).stem
    pasta.mkdir(parents=True, exist_ok=True)
    tentativas = []
    for _ in range(TENTATIVAS):
        try:
            dados, problemas, meta = chamar_com_espera(claude, planta, pasta, modelo, esforco, parar)
        except subprocess.TimeoutExpired:
            dados, problemas, meta = None, [f"passou de {TEMPO_LIMITE // 60} min"], {}
        except Parar:
            parar.set()
            raise
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
    args.add_argument("--limite", type=int, help="quantas plantas gerar nesta rodada (padrão: todas as que faltam)")
    args.add_argument("--sem", default="", help="categorias a pular, separadas por vírgula (ex.: ARVORE,PALMEIRA)")
    args.add_argument("--modelo", default=MODELO, help=f"modelo do claude (padrão: {MODELO})")
    args.add_argument("--esforco", default=ESFORCO, help=f"low, medium, high, xhigh ou max (padrão: {ESFORCO})")
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
    if sys.platform == "win32":
        # A rodada inteira leva horas: pede ao Windows para não suspender enquanto
        # este processo viver (ES_CONTINUOUS | ES_SYSTEM_REQUIRED). Tela pode apagar.
        import ctypes
        ctypes.windll.kernel32.SetThreadExecutionState(0x80000000 | 0x00000001)
    print(f"Gerando {len(lote)} planta(s) com {opcoes.modelo}, esforço {opcoes.esforco}, "
          f"{opcoes.paralelo} em paralelo ({claude})")
    inicio = time.monotonic()
    falhas, custo, saida, feitas = [], 0.0, 0, 0
    parar, motivo = threading.Event(), []

    def relatar(planta, tentativas):
        nonlocal custo, saida, feitas
        if tentativas is None:
            return
        feitas += 1
        custo += sum(t.get("custo_usd") or 0 for t in tentativas)
        saida += sum(t.get("tokens_saida") or 0 for t in tentativas)
        ultima = tentativas[-1]
        progresso = f"[{feitas}/{len(lote)}]"
        if ultima["problemas"]:
            falhas.append(planta)
            print(f"✗ {progresso} {planta['posicao']:>4} {planta['nome_comum']}: "
                  + "; ".join(ultima["problemas"][:3]), flush=True)
        else:
            print(f"✓ {progresso} {planta['posicao']:>4} {planta['nome_comum']} → {planta['arquivo_catalogo']} "
                  f"({len(tentativas)} tentativa(s), {ultima.get('segundos')} s, "
                  f"US$ {sum(t.get('custo_usd') or 0 for t in tentativas):.2f})", flush=True)

    def tarefa(planta):
        try:
            return gerar(claude, planta, opcoes.modelo, opcoes.esforco, parar)
        except Parar as erro:
            motivo.append(str(erro))
            return planta, None

    # A primeira planta vai sozinha: se faltar login ou limite, para aqui sem abrir as outras.
    relatar(*tarefa(lote[0]))
    if not parar.is_set():
        with ThreadPoolExecutor(opcoes.paralelo) as pool:
            for resultado in pool.map(tarefa, lote[1:]):
                relatar(*resultado)

    minutos = (time.monotonic() - inicio) / 60
    if feitas:
        print(f"\n{feitas - len(falhas)}/{feitas} gerada(s) em {minutos:.1f} min; "
              f"US$ {custo:.2f} e {saida} tokens de saída no total "
              f"(média US$ {custo / feitas:.2f} por planta)")
    if falhas:
        print("Falharam (rodar de novo tenta outra vez): " + ", ".join(p["nome_comum"] for p in falhas))
    if motivo:
        print(f"\nParou com {len(lote) - feitas} planta(s) por fazer. {motivo[0]}")
        return 2
    return 1 if falhas else 0

if __name__ == "__main__":
    sys.exit(main())
