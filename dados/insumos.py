"""Extrai os insumos do substrato de cada espécie e grava dados/insumos.json.

O `substrato_recomendado` é texto livre, mas quase sempre na mesma forma:
"40% terra vegetal, 30% composto orgânico ou húmus de minhoca, 20% areia
grossa, 10% perlita ou casca de pinus fina; misture 2 colheres de farinha de
ossos a cada 10 L". Cada trecho entre vírgulas vira um grupo de alternativas
("composto ou húmus": qualquer um serve), e ", ou" separa receitas inteiras
("Substrato para cactos, ou 40% terra vegetal, ..."). O app usa isso no
preparo ("Falta: perlita") e na lista de compras.

Sem claude: as frases seguem tão de perto o padrão da skill que um parser
resolve, e roda de novo de graça quando a revisão mudar um texto. O que ele
não entender aparece em --conferir.

    python3 dados/insumos.py             # grava dados/insumos.json
    python3 dados/insumos.py --conferir  # trechos com porcentagem que não casaram com nada
"""
import argparse
import json
import re
from pathlib import Path

PASTA = Path(__file__).parent
CATALOGO = PASTA.parent / "app/src/main/assets/catalogo"
SAIDA = PASTA / "insumos.json"

# "Substrato para cactos" é um produto; "substrato levemente ácido" é descrição.
PRODUTO = r"substrato (?:comercial |pronto |próprio )?(?:para|própri|pront|comercial|de)"

# Código (o mesmo do enum Insumo no motor) e os jeitos de escrever. A ordem
# importa: o primeiro que casar num trecho ganha ("substrato comercial com fibra
# de coco" é um produto só, o substrato).
INSUMOS = [
    ("SUBSTRATO_CACTOS", rf"{PRODUTO}[^,;]{{0,25}}(?:cact|suculent)"),
    ("SUBSTRATO_ORQUIDEAS", rf"{PRODUTO}[^,;]{{0,25}}orqu[ií]d"),
    ("SUBSTRATO_ACIDO", rf"{PRODUTO}[^,;]{{0,25}}(?:azal[eé]i|violet|[aá]cid|mirtil|camél|gardêni)"),
    ("SUBSTRATO_COMERCIAL", r"substrato (?:comercial|para|pr[oó]prio|pronto|universal|de folhagem|à base|leve\b|org[aâ]nico comercial)"),
    ("TERRA_VEGETAL", r"terra (?:vegetal|de jardim|preta|adubada|comum|de boa qualidade)|terra\b(?! úmida)"),
    ("HUMUS", r"h[uú]mus"),
    ("COMPOSTO", r"composto"),
    ("ESTERCO", r"esterco"),
    ("TURFA", r"turfa|sphagnum peat"),
    ("MUSGO_ESFAGNO", r"esfagno|sphagnum|musgo"),
    ("FIBRA_DE_COCO", r"coco"),
    ("CASCA_DE_PINUS", r"pinus|casca de pinheiro|casca de [aá]rvore"),
    ("CASCA_DE_ARROZ", r"casca de arroz"),
    ("CARVAO", r"carv[aã]o"),
    ("PERLITA", r"perlita"),
    ("VERMICULITA", r"vermiculita"),
    ("AREIA", r"areia"),
    ("ARGILA_EXPANDIDA", r"argila expandida"),
    ("PEDRISCO", r"pedrisco|brita|cascalho|pedra"),
    ("CALCARIO", r"calc[aá]rio"),
    ("FARINHA_DE_OSSO", r"farinha de oss"),
    ("TORTA_DE_MAMONA", r"torta de mamona"),
    ("BOKASHI", r"bokashi"),
    ("CINZA", r"cinza"),
    ("ADUBO_LIBERACAO_LENTA", r"libera[cç][aã]o (?:lenta|controlada)|osmocote"),
]
PADROES = [(codigo, re.compile(padrao)) for codigo, padrao in INSUMOS]

# Daqui até o fim da frase, nada entra: "nada de terra de jardim, que encharca".
# "Opcional" também fica de fora: a lista de compras não deve cobrar o que é extra.
NEGACAO = re.compile(r"\b(?:nada de|sem|nunca|n[aã]o|evite|nenhum|jamais|dispensa|opcional)\b")
FRASE = re.compile(r"[;.:](?:\s+|$)")
TRECHO = re.compile(r",\s+")
# ", ou 40% terra vegetal": começa outra receita. "ou húmus" sem vírgula é alternativa.
OUTRA_RECEITA = re.compile(r"[,;.]\s+ou\s+(?=\d|substrato|mistura)")
PORCENTAGEM = re.compile(r"\d+\s*%")


def primeiro(texto):
    """O insumo que aparece primeiro no texto; None se nenhum."""
    # No empate (três padrões de substrato começam no mesmo lugar), vale a ordem da lista.
    achados = [(m.start(), ordem, codigo) for ordem, (codigo, padrao) in enumerate(PADROES) for m in [padrao.search(texto)] if m]
    return min(achados)[2] if achados else None


def receita(texto):
    """Lista de grupos; cada grupo, os insumos que servem um no lugar do outro."""
    grupos = []
    for frase in FRASE.split(texto):
        for trecho in TRECHO.split(frase):
            # "porque a raiz apodrece em terra pesada" explica, não pede terra.
            trecho = re.split(r"\b(?:porque|prefere|gosta)\b", trecho)[0]
            negado = NEGACAO.search(trecho)
            if negado:
                # O que vem antes da negação ainda vale: "terra vegetal sem adubo".
                trecho = trecho[:negado.start()]
            # "200 g de calcário e 150 g de farinha de ossos": dois grupos num trecho só.
            for parte in re.split(r"\s+e\s+", trecho):
                grupo = []
                for alternativa in re.split(r"[\s(]+ou\s+|\s+e/ou\s+", parte):
                    codigo = primeiro(alternativa)
                    if codigo and codigo not in grupo:
                        grupo.append(codigo)
                if grupo and grupo not in grupos:
                    grupos.append(grupo)
            if negado:
                break
    return grupos


def extrair(substrato):
    texto = substrato.lower()
    if texto.startswith("nenhum substrato"):
        return [[]]
    return [receita(parte) for parte in OUTRA_RECEITA.split(texto)]


def nao_casados(substrato):
    """Trechos com porcentagem sem insumo conhecido: o vocabulário precisa crescer."""
    return [t for t in re.split(r"[,;.]\s+|\s+ou\s+", substrato.lower())
            if PORCENTAGEM.search(t) and not primeiro(t) and not NEGACAO.search(t)]


def main():
    args = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    args.add_argument("--conferir", action="store_true", help="mostra os trechos que não casaram, sem gravar")
    opcoes = args.parse_args()

    especies = {}
    for caminho in sorted(CATALOGO.glob("*.json")):
        substrato = json.loads(caminho.read_text(encoding="utf-8-sig"))["requisitos_iniciais"]["substrato_recomendado"]
        if opcoes.conferir:
            for trecho in nao_casados(substrato):
                print(f"{caminho.name}: {trecho!r}")
            continue
        # O texto vai junto: o build ignora a espécie se a revisão mudar o substrato
        # e ninguém rodar este script de novo.
        especies[caminho.name] = {"substrato": substrato, "receitas": extrair(substrato)}

    if not opcoes.conferir:
        dados = {"insumos": [codigo for codigo, _ in INSUMOS], "especies": especies}
        SAIDA.write_text(json.dumps(dados, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
        print(f"{len(especies)} espécies em {SAIDA.name}")


if __name__ == "__main__":
    main()
