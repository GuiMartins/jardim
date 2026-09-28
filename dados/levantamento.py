"""Levantamento das plantas cultivadas mais populares do Brasil.

Não existe ranking oficial, então a popularidade é medida por dois sinais públicos:

- iNaturalist: observações de plantas marcadas como cultivadas (captive=true) no Brasil.
  Mede o que as pessoas têm em casa, no jardim e na rua, e fotografam.
- Wikipedia em português: visitas ao artigo da planta nos últimos 60 dias.
  Mede o que as pessoas procuram, e cobre a horta, que quase ninguém fotografa.

Os candidatos são as espécies cultivadas do iNaturalist mais a lista de `curadoria.csv`
(hortaliças, temperos, frutíferas e medicinais). A curadoria também corrige nomes,
categorias e exclui o que não é planta de cultivo doméstico (soja, eucalipto de
reflorestamento...).

Cada etapa guarda o que baixou em dados/cache/; rodar de novo só baixa o que falta.

    python dados/levantamento.py            # gera dados/plantas-populares.json
"""
import csv
import json
import math
import re
import sys
import time
import unicodedata
import urllib.error
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from datetime import date
from pathlib import Path

PASTA = Path(__file__).parent
CACHE = PASTA / "cache"
CATALOGO = PASTA.parent / "app/src/main/assets/catalogo"
SAIDA = PASTA / "plantas-populares.json"
TOTAL = 1000
# Prova de cultivo: fora da curadoria, a planta precisa de algumas observações de
# exemplares cultivados no Brasil. Sem isso, entram carvalho e mancenilheira só
# porque o artigo delas é muito lido.
MIN_OBS = 3

BRASIL_INAT = 6878
DIAS_VISITAS = 60  # o máximo que prop=pageviews devolve
# Sem contato no User-Agent a Wikimedia limita a 10 requisições por minuto.
PAUSA_WIKIPEDIA = 6.5
USER_AGENT = "JardimLevantamento/1.0 (levantamento pessoal de plantas; python-urllib)"

CATEGORIAS = {
    "HORTALICA", "ERVA_AROMATICA", "MEDICINAL", "FRUTIFERA",
    "FLOR", "FOLHAGEM", "SUCULENTA", "ORQUIDEA", "BROMELIA", "SAMAMBAIA",
    "PALMEIRA", "ARBUSTO", "ARVORE", "TREPADEIRA", "FORRACAO", "AQUATICA",
}


# --- HTTP e cache --------------------------------------------------------------------

def baixar_json(url, tentativas=5):
    """GET com retentativa em 429/5xx. Devolve None em 404."""
    for tentativa in range(tentativas):
        pedido = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(pedido, timeout=60) as resposta:
                return json.load(resposta)
        except urllib.error.HTTPError as erro:
            if erro.code == 404:
                return None
            if erro.code not in (429, 500, 502, 503, 504) or tentativa == tentativas - 1:
                raise
            espera = erro.headers.get("Retry-After")
            time.sleep(int(espera) if espera and espera.isdigit() else 5 * 2 ** tentativa)
            continue
        except (urllib.error.URLError, TimeoutError):
            if tentativa == tentativas - 1:
                raise
        time.sleep(2 ** tentativa)


def cache(nome):
    """Dicionário persistido em dados/cache/<nome>.json."""
    caminho = CACHE / f"{nome}.json"
    dados = json.loads(caminho.read_text(encoding="utf-8")) if caminho.exists() else {}

    def salvar():
        CACHE.mkdir(exist_ok=True)
        caminho.write_text(json.dumps(dados, ensure_ascii=False, indent=0), encoding="utf-8")

    return dados, salvar


def em_lotes(itens, tamanho):
    for i in range(0, len(itens), tamanho):
        yield itens[i:i + tamanho]


MARCAS_DE_RANK = {"var.", "subsp.", "ssp.", "f.", "cv.", "spp.", "sp.", "x", "×"}


def chave(nome_cientifico):
    """Gênero + espécie + último epíteto infraespecífico, sem sinal de híbrido nem cultivar.

    'Hibiscus × rosa-sinensis' = 'Hibiscus rosa-sinensis'; 'Brassica oleracea var. capitata'
    e 'Brassica oleracea' ficam separados (repolho não é couve).
    """
    nome = re.sub(r"\(.*?\)|'.*?'", " ", nome_cientifico).replace("×", " × ")
    partes = [p for p in nome.lower().split() if p not in MARCAS_DE_RANK]
    return " ".join(partes[:2] + partes[-1:] if len(partes) > 2 else partes)


def sem_hibrido(nome):
    return " ".join(nome.replace("×", " ").split())


def e_genero(nome_cientifico):
    """'Rosa', 'Rosa spp.' ou 'Phalaenopsis spp. (híbridos)'."""
    partes = re.sub(r"\(.*?\)", " ", nome_cientifico).split()
    return len(partes) == 1 or (len(partes) == 2 and partes[1] in ("spp.", "sp."))


# --- Etapa 1: iNaturalist --------------------------------------------------------------

def inat_cultivadas():
    """Todas as espécies de planta com observação cultivada no Brasil, com a contagem."""
    especies, salvar = cache("inat")
    if especies:
        return list(especies.values())
    pagina = 1
    while True:
        url = ("https://api.inaturalist.org/v1/observations/species_counts?"
               f"place_id={BRASIL_INAT}&iconic_taxa=Plantae&captive=true&locale=pt-BR"
               f"&preferred_place_id={BRASIL_INAT}&per_page=500&page={pagina}")
        resultado = baixar_json(url)
        for item in resultado["results"]:
            taxon = item["taxon"]
            especies[str(taxon["id"])] = {
                "inat_id": taxon["id"],
                "nome_cientifico": taxon["name"],
                "rank": taxon["rank"],
                "nome_inat": taxon.get("preferred_common_name"),
                "obs": item["count"],
                "wikipedia_en": taxon.get("wikipedia_url"),
            }
        print(f"  iNaturalist: página {pagina}, {len(especies)}/{resultado['total_results']}")
        if pagina * 500 >= resultado["total_results"]:
            break
        pagina += 1
        time.sleep(1.5)  # a API pede no máximo ~1 requisição por segundo
    salvar()
    return list(especies.values())


def inat_por_nome(nomes):
    """Táxon do iNaturalist de cada nome da curadoria que não veio na lista de espécies.

    A busca do iNaturalist também casa sinônimos ('Rosmarinus officinalis' devolve
    'Salvia rosmarinus'). A contagem inclui os táxons abaixo, então para um gênero
    ('Rosa spp.') são todas as roseiras, identificadas até a espécie ou não.
    """
    achados, salvar = cache("inat_por_nome")
    faltam = [n for n in nomes if n not in achados]
    for n, nome in enumerate(faltam, 1):
        busca = sem_hibrido(re.sub(r"\(.*?\)|\s+spp?\.", " ", nome)).strip()
        url = f"https://api.inaturalist.org/v1/taxa?q={urllib.parse.quote(busca)}&per_page=10&locale=pt-BR"
        taxon = next((t for t in baixar_json(url)["results"]
                      if chave(t.get("matched_term") or "") == chave(busca) or chave(t["name"]) == chave(busca)), None)
        if taxon:
            url = (f"https://api.inaturalist.org/v1/observations?taxon_id={taxon['id']}"
                   f"&place_id={BRASIL_INAT}&captive=true&per_page=0")
            achados[nome] = {
                "inat_id": taxon["id"], "nome_cientifico": taxon["name"], "rank": taxon["rank"],
                "nome_inat": taxon.get("preferred_common_name"), "wikipedia_en": taxon.get("wikipedia_url"),
                "obs": baixar_json(url)["total_results"],
            }
        else:
            achados[nome] = None
        if n % 20 == 0:
            print(f"  iNaturalist por nome: {n}/{len(faltam)}")
            salvar()
        time.sleep(1.2)
    if faltam:
        salvar()
    return achados


def inat_familias(ids):
    """Família botânica de cada táxon do iNaturalist (para sugerir a categoria)."""
    familias, salvar = cache("inat_familias")
    faltam = [i for i in ids if str(i) not in familias]
    for lote in em_lotes(faltam, 30):
        url = f"https://api.inaturalist.org/v1/taxa/{','.join(map(str, lote))}?locale=pt-BR"
        for taxon in baixar_json(url)["results"]:
            ancestrais = {a["rank"]: a["name"] for a in taxon.get("ancestors", [])}
            familias[str(taxon["id"])] = {
                "familia": ancestrais.get("family"),
                "classe": ancestrais.get("class"),
            }
        time.sleep(1.2)
    if faltam:
        salvar()
    return familias


# --- Etapa 2: Wikipedia em português ------------------------------------------------------

def resolver_ptwiki(titulos):
    """Título pedido -> artigo final na ptwiki (seguindo redirecionamentos), ou None.

    Páginas de desambiguação contam como None: não dizem nada sobre a planta.
    """
    resolvidos, salvar = cache("ptwiki_titulos")
    faltam = sorted({t for t in titulos if t and t not in resolvidos})
    for n, lote in enumerate(em_lotes(faltam, 50)):
        parametros = {
            "action": "query", "format": "json", "formatversion": "2", "redirects": "1",
            "prop": "pageprops", "ppprop": "disambiguation|wikibase_item",
            "titles": "|".join(lote),
        }
        resposta = baixar_json("https://pt.wikipedia.org/w/api.php?" + urllib.parse.urlencode(parametros))
        consulta = resposta.get("query", {})
        passo = {n["from"]: n["to"] for n in consulta.get("normalized", [])}
        passo.update({r["from"]: r["to"] for r in consulta.get("redirects", [])})
        paginas = {p["title"]: p for p in consulta.get("pages", [])}
        for titulo in lote:
            atual, vistos = titulo, set()
            while atual in passo and atual not in vistos:
                vistos.add(atual)
                atual = passo[atual]
            pagina = paginas.get(atual)
            valido = (pagina and not pagina.get("missing") and not pagina.get("invalid")
                      and "disambiguation" not in pagina.get("pageprops", {}))
            resolvidos[titulo] = pagina["title"] if valido else None
        if n % 20 == 19:
            salvar()
        time.sleep(PAUSA_WIKIPEDIA)
    if faltam:
        salvar()
    return resolvidos


def ptwiki_pela_enwiki(urls_en):
    """Artigo da enwiki -> artigo da ptwiki sobre o mesmo item do Wikidata, ou None."""
    ligacoes, salvar = cache("enwiki_para_ptwiki")
    titulos = {}
    for url in urls_en:
        if url and "wikipedia.org/wiki/" in url:
            titulo = urllib.parse.unquote(url.split("/wiki/", 1)[1]).replace("_", " ")
            titulos[url] = titulo
    faltam = sorted({t for t in titulos.values() if t not in ligacoes})
    for lote in em_lotes(faltam, 50):
        parametros = {
            "action": "wbgetentities", "format": "json", "sites": "enwiki",
            "titles": "|".join(lote), "props": "sitelinks", "sitefilter": "ptwiki|enwiki",
            "normalize": "1" if len(lote) == 1 else "0",
        }
        resposta = baixar_json("https://www.wikidata.org/w/api.php?" + urllib.parse.urlencode(parametros))
        achados = {}
        for entidade in resposta.get("entities", {}).values():
            if "missing" in entidade:
                continue
            # wbgetentities não diz de qual título veio cada entidade; o sitelink enwiki diz
            en = entidade.get("sitelinks", {}).get("enwiki", {}).get("title")
            pt = entidade.get("sitelinks", {}).get("ptwiki", {}).get("title")
            if en:
                achados[en] = pt
        for titulo in lote:
            ligacoes[titulo] = achados.get(titulo)
        time.sleep(PAUSA_WIKIPEDIA)
    if faltam:
        salvar()
    return {url: ligacoes.get(titulo) for url, titulo in titulos.items()}


def visitas_ptwiki(artigos):
    """Visitas de cada artigo nos últimos 60 dias.

    A API por artigo (12 meses) faria uma requisição por planta, e sem um contato no
    User-Agent a Wikimedia limita a 10 por minuto: seriam horas. prop=pageviews da API
    principal devolve até 60 dias, mas só 5 artigos por resposta; o resto do lote vem
    nas continuações (pvipcontinue).
    """
    visitas, salvar = cache("ptwiki_visitas")
    faltam = sorted({a for a in artigos if a and a not in visitas})
    lotes = -(-len(faltam) // 50)
    for n, lote in enumerate(em_lotes(faltam, 50), 1):
        parametros = {
            "action": "query", "format": "json", "formatversion": "2", "prop": "pageviews",
            "pvipdays": str(DIAS_VISITAS), "titles": "|".join(lote),
        }
        while True:
            resposta = baixar_json("https://pt.wikipedia.org/w/api.php?" + urllib.parse.urlencode(parametros))
            consulta = resposta.get("query", {})
            # os títulos pedidos já são os finais, mas a API ainda pode normalizá-los
            normalizado = {x["to"]: x["from"] for x in consulta.get("normalized", [])}
            for pagina in consulta.get("pages", []):
                if "pageviews" in pagina:  # sem a chave = fica para uma continuação
                    artigo = normalizado.get(pagina["title"], pagina["title"])
                    visitas[artigo] = sum(v or 0 for v in (pagina["pageviews"] or {}).values())
            time.sleep(PAUSA_WIKIPEDIA)
            if "continue" not in resposta:
                break
            parametros.update(resposta["continue"])
        for artigo in lote:
            visitas.setdefault(artigo, 0)
        print(f"  visitas: lote {n}/{lotes}")
        salvar()
    return visitas


# --- Etapa 3: candidatos ---------------------------------------------------------------

def ler_curadoria():
    """curadoria.csv: nome_cientifico;nome_comum;categoria;outros_nomes;acao

    `acao` vazia = candidato normal; EXCLUIR = fora do levantamento;
    ARTIGO=<título> = força o artigo da ptwiki usado nas visitas.
    """
    linhas = {}
    with open(PASTA / "curadoria.csv", encoding="utf-8") as arquivo:
        for linha in csv.DictReader((l for l in arquivo if not l.startswith("#")), delimiter=";"):
            linha = {k: (v or "").strip() for k, v in linha.items()}
            if linha["categoria"] and linha["categoria"] not in CATEGORIAS:
                sys.exit(f"curadoria.csv: categoria desconhecida {linha['categoria']!r} em {linha['nome_cientifico']}")
            linhas[chave(linha["nome_cientifico"])] = linha
    return linhas


def montar_candidatos():
    print("Etapa 1: iNaturalist")
    inat = inat_cultivadas()
    curadoria = ler_curadoria()

    candidatos = {}
    for especie in inat:
        candidatos[chave(especie["nome_cientifico"])] = {**especie, "curadoria": None}
    avulsos = [linha["nome_cientifico"] for k, linha in curadoria.items()
               if k not in candidatos or e_genero(linha["nome_cientifico"])]
    por_nome = inat_por_nome(avulsos)
    for k, linha in curadoria.items():
        achado = por_nome.get(linha["nome_cientifico"])
        sinonimo = achado and chave(achado["nome_cientifico"])
        if k in candidatos and not e_genero(linha["nome_cientifico"]):
            candidatos[k]["curadoria"] = linha
        elif sinonimo in candidatos and sinonimo != k and not e_genero(linha["nome_cientifico"]):
            # a curadoria usa outro nome para uma espécie que já veio do iNaturalist
            candidatos[sinonimo]["curadoria"] = linha
        else:
            base = achado or {"inat_id": None, "rank": None, "nome_inat": None, "obs": 0, "wikipedia_en": None}
            candidatos[k] = {**base, "nome_cientifico": linha["nome_cientifico"], "curadoria": linha}

    print(f"Etapa 2: Wikipedia ({len(candidatos)} candidatos)")
    forcados = {k: c["curadoria"]["acao"][len("ARTIGO="):] for k, c in candidatos.items()
                if c["curadoria"] and c["curadoria"]["acao"].startswith("ARTIGO=")}
    def nomes_cientificos(c):
        nomes = [c["nome_cientifico"]] + ([c["curadoria"]["nome_cientifico"]] if c["curadoria"] else [])
        nomes = [re.sub(r"\s+spp?\.|\(.*?\)", "", n).strip() for n in nomes]
        return list(dict.fromkeys(nomes + [sem_hibrido(n) for n in nomes]))

    pelo_nome = resolver_ptwiki([n for c in candidatos.values() for n in nomes_cientificos(c)]
                                + list(forcados.values()))
    artigo_cientifico = {k: next((pelo_nome[n] for n in nomes_cientificos(c) if pelo_nome.get(n)), None)
                         for k, c in candidatos.items()}
    sem_artigo = [c for k, c in candidatos.items() if k not in forcados and not artigo_cientifico[k]]
    pela_en = ptwiki_pela_enwiki([c["wikipedia_en"] for c in sem_artigo])
    pelo_comum = resolver_ptwiki([c["curadoria"]["nome_comum"] for c in sem_artigo if c["curadoria"]])

    for k, c in candidatos.items():
        if k in forcados:
            c["artigo"], c["via"] = pelo_nome.get(forcados[k]), "curadoria"
        elif artigo_cientifico[k]:
            c["artigo"], c["via"] = artigo_cientifico[k], "nome_cientifico"
        elif pela_en.get(c["wikipedia_en"]):
            c["artigo"], c["via"] = pela_en[c["wikipedia_en"]], "wikidata"
        elif c["curadoria"] and pelo_comum.get(c["curadoria"]["nome_comum"]):
            c["artigo"], c["via"] = pelo_comum[c["curadoria"]["nome_comum"]], "nome_comum"
        else:
            c["artigo"], c["via"] = None, None

    visitas = visitas_ptwiki([c["artigo"] for c in candidatos.values()])
    compartilhados = {}
    for c in candidatos.values():
        if c["artigo"]:
            compartilhados[c["artigo"]] = compartilhados.get(c["artigo"], 0) + 1
    for c in candidatos.values():
        c["visitas"] = visitas.get(c["artigo"], 0) if c["artigo"] else 0
        c["artigo_compartilhado"] = compartilhados.get(c["artigo"], 1) if c["artigo"] else 1
    return list(candidatos.values())


# --- Etapa 4: nota, categoria e saída ----------------------------------------------------

def nota_popularidade(visitas, obs, compartilhado, referencia):
    """Nota de 0 a 100 que decide a ordem do levantamento.

    visitas       visitas ao artigo da ptwiki nos últimos 60 dias (0 se a planta não tem artigo)
    obs           observações de exemplares cultivados no iNaturalist, no Brasil
    compartilhado quantos candidatos caíram no mesmo artigo (1 = artigo só dela)
    referencia    {"visitas": maior valor de visitas, "obs": maior valor de obs}
    """
    # Escala log: os dois sinais têm cauda longa, e no linear meia dúzia de campeãs
    # achataria todo o resto perto de zero.
    parte_obs = math.log1p(obs) / math.log1p(referencia["obs"])
    if not visitas:
        # Sem artigo em português: vale só o iNaturalist, com desconto por faltar um sinal.
        return 100 * 0.8 * parte_obs
    # Artigo de gênero dividido por várias espécies: cada uma leva a sua fração.
    parte_wiki = math.log1p(visitas / compartilhado) / math.log1p(referencia["visitas"])
    return 100 * (0.5 * parte_obs + 0.5 * parte_wiki)


# Categoria pela família botânica, para quem não está na curadoria. O resto é manual.
CATEGORIA_POR_FAMILIA = {
    "Orchidaceae": "ORQUIDEA", "Bromeliaceae": "BROMELIA", "Arecaceae": "PALMEIRA",
    "Cactaceae": "SUCULENTA", "Crassulaceae": "SUCULENTA", "Aizoaceae": "SUCULENTA",
    "Cycadaceae": "PALMEIRA", "Zamiaceae": "PALMEIRA", "Marantaceae": "FOLHAGEM",
    "Araceae": "FOLHAGEM", "Nymphaeaceae": "AQUATICA", "Pontederiaceae": "AQUATICA",
}
CLASSES_SAMAMBAIA = {"Polypodiopsida"}


def sugerir_categoria(taxon):
    if not taxon:
        return None
    if taxon.get("classe") in CLASSES_SAMAMBAIA:
        return "SAMAMBAIA"
    return CATEGORIA_POR_FAMILIA.get(taxon.get("familia"))


def nome_de_exibicao(c):
    """Curadoria > nome do iNaturalist > título do artigo > nome científico."""
    if c["curadoria"] and c["curadoria"]["nome_comum"]:
        return c["curadoria"]["nome_comum"]
    nome = c["nome_inat"] or (c["artigo"] if c["artigo"] and chave(c["artigo"]) != chave(c["nome_cientifico"]) else None)
    if not nome:
        return c["nome_cientifico"]
    nome = nome.split(",")[0].strip()
    return nome[0].upper() + nome[1:].lower()


def slug(nome):
    ascii_ = unicodedata.normalize("NFKD", nome).encode("ascii", "ignore").decode()
    return re.sub(r"[^a-z0-9]+", "-", ascii_.lower()).strip("-")


def ja_no_catalogo():
    """Chaves dos nomes científicos que já têm JSON de cultivo no app."""
    chaves = {}
    for arquivo in CATALOGO.glob("*.json"):
        planta = json.loads(arquivo.read_text(encoding="utf-8"))["planta"]
        chaves[chave(planta["nome_cientifico"])] = arquivo.name
    return chaves


def gerar(candidatos):
    validos = [c for c in candidatos
               if (c["curadoria"] and c["curadoria"]["acao"] != "EXCLUIR") or (not c["curadoria"] and c["obs"] >= MIN_OBS)]
    referencia = {"visitas": max(c["visitas"] for c in validos), "obs": max(c["obs"] for c in validos)}
    for c in validos:
        c["nota"] = nota_popularidade(c["visitas"], c["obs"], c["artigo_compartilhado"], referencia)
    validos.sort(key=lambda c: (-c["nota"], -c["obs"], c["nome_cientifico"]))
    topo = validos[:TOTAL]

    print("Etapa 4: famílias para sugerir a categoria")
    familias = inat_familias([c["inat_id"] for c in topo
                              if c["inat_id"] and not (c["curadoria"] and c["curadoria"]["categoria"])])
    catalogo = ja_no_catalogo()
    plantas, sem_categoria = [], []
    for posicao, c in enumerate(topo, 1):
        curadoria = c["curadoria"] or {}
        categoria = curadoria.get("categoria") or sugerir_categoria(familias.get(str(c["inat_id"])))
        if not categoria:
            sem_categoria.append(c)
        nome = nome_de_exibicao(c)
        nome_cientifico = curadoria.get("nome_cientifico") or c["nome_cientifico"]
        plantas.append({
            "posicao": posicao,
            "nome_comum": nome,
            "outros_nomes": [n for n in curadoria.get("outros_nomes", "").split("|") if n],
            "nome_cientifico": nome_cientifico,
            "categoria": categoria,
            "nota": round(c["nota"], 1),
            "inaturalist_obs_cultivadas": c["obs"],
            "wikipedia_visitas_60d": c["visitas"],
            "wikipedia_artigo": c["artigo"],
            "inaturalist_id": c["inat_id"],
            "arquivo_catalogo": catalogo.get(chave(nome_cientifico), f"{slug(nome)}.json"),
            "no_catalogo": chave(nome_cientifico) in catalogo,
        })

    SAIDA.write_text(json.dumps({
        "gerado_em": date.today().isoformat(),
        "metodo": ("Plantas cultivadas no Brasil ordenadas por popularidade. Sinais: observações de "
                   "exemplares cultivados no iNaturalist (Brasil) e visitas ao artigo na Wikipedia em "
                   f"português nos {DIAS_VISITAS} dias anteriores. "
                   "Candidatos, nomes, categorias e exclusões em dados/curadoria.csv."),
        "total": len(plantas),
        "plantas": plantas,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{len(plantas)} plantas em {SAIDA.relative_to(PASTA.parent)}")
    if sem_categoria:
        print(f"{len(sem_categoria)} sem categoria (complete na curadoria.csv):")
        for c in sem_categoria:
            print(f"  {c['nome_cientifico']};{nome_de_exibicao(c)}")
    return plantas


if __name__ == "__main__":
    candidatos = montar_candidatos()
    com_artigo = sum(1 for c in candidatos if c["artigo"])
    print(f"{len(candidatos)} candidatos, {com_artigo} com artigo na ptwiki")
    gerar(candidatos)
