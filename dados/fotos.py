"""Foto de cada espécie do catálogo, do iNaturalist, para a busca do app.

O app não leva as fotos no APK: o índice do catálogo guarda o endereço da miniatura
(small, 240 px) e o celular baixa cada uma quando a linha aparece na busca.

Para cada JSON de app/src/main/assets/catalogo/:

1. Acha o táxon no iNaturalist. Vale o do levantamento (plantas-populares.json) se ele
   bate com o nome científico do JSON; senão, busca o nome do JSON (a busca casa
   sinônimos). O JSON manda: Begônia descreve a begônia-de-cera, não o gênero.
2. Junta as candidatas: as fotos do táxon (as da página da espécie no iNaturalist) e
   fotos de exemplares cultivados no Brasil, que mostram a planta como ela é vendida.
   Só entram licenças que permitem recortar: CC0, CC BY, BY-SA, BY-NC e BY-NC-SA.
3. Escolhe a decisão de dados/fotos.csv; sem decisão, a primeira foto do táxon.
4. Grava dados/fotos.json (lido pelo build) e a página de revisão
   dados/cache/fotos/revisao.html.

A revisão é à mão: a página mostra cada miniatura recortada como no app, as marcadas
primeiro (gênero, cultivar, híbridos, nome que não bate). Clicar numa mostra as
candidatas. No fim, a página gera as linhas para colar em dados/fotos.csv. Espécie sem
linha no CSV ainda não foi revisada.

    python dados/fotos.py
"""
import csv
import html
import json
import re
import sys
import time
import urllib.parse
from datetime import date
from pathlib import Path

from levantamento import BRASIL_INAT, CACHE, CATALOGO, PASTA, baixar_json, cache, em_lotes

SAIDA = PASTA / "fotos.json"
DECISOES = PASTA / "fotos.csv"
PAGINA = CACHE / "fotos" / "revisao.html"
# ND proíbe obra derivada, e recortar um quadrado pode contar como uma.
LICENCAS = {"cc0", "cc-by", "cc-by-sa", "cc-by-nc", "cc-by-nc-sa"}
CULTIVADAS = 12
MARCAS = {"var.", "subsp.", "ssp.", "f."}
PAUSA_INAT = 1.1  # a API pede no máximo ~1 requisição por segundo


# --- Nomes -------------------------------------------------------------------------------

def nome_para_busca(nome_cientifico):
    """'Citrus × latifolia (Tanaka) Tanaka' -> 'Citrus latifolia'; gênero ('Rosa spp.') -> None.

    Fica gênero, epíteto e o infraespecífico com a marca ('Beta vulgaris subsp. vulgaris').
    Autores, cultivares e o que vem entre parênteses saem.
    """
    limpo = re.sub(r"\(.*?\)|'.*?'|\".*?\"", " ", nome_cientifico).replace("×", " ")
    partes = [p.strip(",;") for p in limpo.split()]
    if len(partes) < 2 or not partes[1].islower() or partes[1] in ("spp.", "sp."):
        return None
    nome = partes[:2]
    if len(partes) > 3 and partes[2] in MARCAS and partes[3].islower():
        nome += partes[2:4]
    return " ".join(nome)


def chave(nome):
    """Para comparar nomes: sem ×, sem marca de rank, minúsculo."""
    partes = nome.replace("×", " ").lower().split()
    return " ".join(p for p in partes if p not in MARCAS)


def motivos_de_revisao(nome_json, taxon, por_busca):
    motivos = []
    if taxon is None:
        return ["sem táxon"]
    if nome_para_busca(nome_json) is None or taxon["rank"] == "genus":
        motivos.append("gênero")
    elif chave(taxon["nome"]) != chave(nome_para_busca(nome_json)):
        # Achado pela busca, é sinônimo; senão, a busca falhou e ficou o do levantamento.
        motivos.append("sinônimo" if por_busca else "nome")
    if re.search(r"'.*?'", nome_json):
        motivos.append("cultivar")
    if "híbrid" in nome_json.lower():
        motivos.append("híbridos")
    return motivos


# --- iNaturalist --------------------------------------------------------------------------

def casa(taxon, nome):
    """O táxon do iNaturalist é o nome pedido, por nome ou sinônimo.

    Sinônimo só vale com o mesmo epíteto: 'Vriesea splendens' é Lutheria splendens, mas
    'Rosa hybrida' também é um nome antigo de Rosa pendulina, uma roseira silvestre.
    O autônimo casa ('Raphanus sativus' é 'Raphanus sativus sativus' no iNaturalist).
    """
    pedido = chave(nome).split()
    for termo in (taxon["name"], taxon.get("matched_term") or ""):
        achado = chave(termo).split()
        if achado == pedido or (len(pedido) == 2 and achado == pedido + pedido[-1:]):
            return termo == taxon["name"] or chave(taxon["name"]).split()[-1] == pedido[-1] \
                or chave(taxon["name"]).split()[1:2] == pedido[1:2]
    return False


def buscar_taxons(nomes):
    """Nome científico -> táxon do iNaturalist ({id, nome}) ou None.

    Sem a variedade ou subespécie no iNaturalist, tenta o binômio.
    """
    respostas, salvar = cache("fotos_busca")
    achados = {}
    for n, nome in enumerate(nomes, 1):
        taxon = None
        for tentativa in dict.fromkeys([nome, " ".join(nome.split()[:2])]):
            if tentativa not in respostas:
                # O iNaturalist escreve o trinômio sem a marca: 'Beta vulgaris vulgaris'.
                busca = " ".join(p for p in tentativa.split() if p not in MARCAS)
                url = "https://api.inaturalist.org/v1/taxa?" + urllib.parse.urlencode({"q": busca, "per_page": 10})
                respostas[tentativa] = [{k: t.get(k) for k in ("id", "name", "rank", "matched_term")}
                                        for t in baixar_json(url)["results"]]
                time.sleep(PAUSA_INAT)
                if n % 20 == 0:
                    salvar()
            taxon = next((t for t in respostas[tentativa] if casa(t, tentativa)), None)
            if taxon:
                break
        achados[nome] = taxon and {"id": taxon["id"], "nome": taxon["name"]}
    salvar()
    return achados


def foto(dado, origem, taxon_nome, observacao=None):
    """Uma candidata, no formato de fotos.json. None se a licença não serve."""
    if dado.get("license_code") not in LICENCAS or not dado.get("url"):
        return None
    base = dado["url"]
    return {
        "id": dado["id"],
        "url": re.sub(r"/square\.", "/small.", base),
        "grande": re.sub(r"/square\.", "/medium.", base),
        "autor": dado.get("attribution_name") or dado.get("attribution"),
        "licenca": dado["license_code"].upper().replace("CC-", "CC ", 1),
        "pagina": f"https://www.inaturalist.org/observations/{observacao}" if observacao
                  else f"https://www.inaturalist.org/photos/{dado['id']}",
        "origem": origem,
        "taxon": taxon_nome,
    }


def detalhes_taxons(ids):
    """id -> {nome, rank, fotos do táxon}. As fotos do táxon só vêm na consulta por id."""
    detalhes, salvar = cache("fotos_taxa")
    faltam = sorted({str(i) for i in ids if str(i) not in detalhes})
    for lote in em_lotes(faltam, 30):
        for t in baixar_json(f"https://api.inaturalist.org/v1/taxa/{','.join(lote)}")["results"]:
            detalhes[str(t["id"])] = {
                "nome": t["name"],
                "rank": t["rank"],
                "fotos": [f for f in (foto(tp["photo"], "táxon", (tp.get("taxon") or {}).get("name", t["name"]))
                                      for tp in t.get("taxon_photos") or []) if f],
            }
        time.sleep(PAUSA_INAT)
    if faltam:
        salvar()
    return detalhes


def fotos_cultivadas(ids):
    """id -> fotos de exemplares cultivados no Brasil (uma por observação)."""
    achadas, salvar = cache("fotos_cultivadas")
    faltam = sorted({str(i) for i in ids if str(i) not in achadas})
    for n, taxon_id in enumerate(faltam, 1):
        url = "https://api.inaturalist.org/v1/observations?" + urllib.parse.urlencode({
            "taxon_id": taxon_id, "place_id": BRASIL_INAT, "captive": "true",
            "photo_license": ",".join(sorted(LICENCAS)), "order_by": "votes", "per_page": CULTIVADAS,
        })
        fotos = []
        for obs in baixar_json(url)["results"]:
            primeira = next((f for f in (foto(p, "cultivada", (obs.get("taxon") or {}).get("name"), obs["id"])
                                         for p in obs.get("photos") or []) if f), None)
            if primeira:
                fotos.append(primeira)
        achadas[taxon_id] = fotos
        if n % 20 == 0:
            print(f"  cultivadas: {n}/{len(faltam)}")
            salvar()
        time.sleep(PAUSA_INAT)
    if faltam:
        salvar()
    return achadas


# --- Decisões ------------------------------------------------------------------------------

def ler_decisoes():
    """fotos.csv: arquivo;foto;nota. foto = inat:<id da foto> ou SEM_FOTO. Vale a última linha."""
    if not DECISOES.exists():
        return {}
    decisoes = {}
    with open(DECISOES, encoding="utf-8") as arquivo:
        for linha in csv.DictReader((l for l in arquivo if not l.startswith("#")), delimiter=";"):
            valor = (linha["foto"] or "").strip()
            if valor != "SEM_FOTO" and not re.fullmatch(r"inat:\d+", valor):
                sys.exit(f"fotos.csv: foto inválida {valor!r} em {linha['arquivo']} (use inat:<id> ou SEM_FOTO)")
            decisoes[linha["arquivo"].strip()] = valor
    return decisoes


def main():
    catalogo = {a.name: json.loads(a.read_text(encoding="utf-8"))["planta"] for a in sorted(CATALOGO.glob("*.json"))}
    levantamento = {p["arquivo_catalogo"]: p
                    for p in json.loads((PASTA / "plantas-populares.json").read_text(encoding="utf-8"))["plantas"]}
    anterior = json.loads(SAIDA.read_text(encoding="utf-8"))["especies"] if SAIDA.exists() else {}
    decisoes = ler_decisoes()
    print(f"{len(catalogo)} espécies no catálogo, {len(decisoes)} já revisadas")

    # 1. Táxon: o do levantamento, se bate com o JSON; senão, pelo nome do JSON.
    ids_levantamento = {a: (levantamento.get(a) or {}).get("inaturalist_id") for a in catalogo}
    do_levantamento = detalhes_taxons([i for i in ids_levantamento.values() if i])
    buscar = {}
    for arquivo, planta in catalogo.items():
        nome = nome_para_busca(planta["nome_cientifico"])
        taxon = do_levantamento.get(str(ids_levantamento[arquivo]))
        if nome and not (taxon and chave(taxon["nome"]) == chave(nome)):
            buscar[arquivo] = nome
    print(f"Buscando {len(buscar)} nomes que não batem com o levantamento")
    pela_busca = buscar_taxons(sorted(set(buscar.values())))

    escolhidos = {}
    for arquivo in catalogo:
        achado = pela_busca.get(buscar.get(arquivo))
        if achado:
            escolhidos[arquivo] = (achado["id"], True)
        elif ids_levantamento[arquivo]:
            escolhidos[arquivo] = (ids_levantamento[arquivo], False)
    detalhes = detalhes_taxons([i for i, _ in escolhidos.values()])
    print("Fotos de exemplares cultivados no Brasil")
    cultivadas = fotos_cultivadas([i for i, _ in escolhidos.values()])

    # 2. Candidatas e escolha.
    especies, candidatas = {}, {}
    for arquivo, planta in catalogo.items():
        taxon_id, por_busca = escolhidos.get(arquivo, (None, False))
        taxon = detalhes.get(str(taxon_id)) if taxon_id else None
        # O táxon do levantamento entra no fim: o da variedade pode não ter foto nenhuma (salsão).
        do_lev = do_levantamento.get(str(ids_levantamento[arquivo]))
        extras = do_lev["fotos"] if do_lev and ids_levantamento[arquivo] != taxon_id else []
        lista = list({f["id"]: f for f in (taxon["fotos"] if taxon else []) + cultivadas.get(str(taxon_id), []) + extras}.values())
        # A foto escolhida antes continua valendo mesmo se sumir das candidatas.
        velha = (anterior.get(arquivo) or {}).get("foto")
        if velha and all(f["id"] != velha["id"] for f in lista):
            lista.append(velha)
        candidatas[arquivo] = lista

        decisao = decisoes.get(arquivo)
        if decisao == "SEM_FOTO":
            escolhida = None
        elif decisao:
            escolhida = next((f for f in lista if f"inat:{f['id']}" == decisao), None)
            if not escolhida:
                sys.exit(f"fotos.csv: {decisao} não está entre as candidatas de {arquivo}")
        else:
            escolhida = lista[0] if lista else None
        especies[arquivo] = {
            "nome_cientifico": planta["nome_cientifico"],
            "taxon": taxon and {"id": taxon_id, "nome": taxon["nome"], "rank": taxon["rank"]},
            "foto": escolhida,
            "revisada": arquivo in decisoes,
            "revisar": motivos_de_revisao(planta["nome_cientifico"],
                                          taxon and {"nome": taxon["nome"], "rank": taxon["rank"]}, por_busca),
        }

    SAIDA.write_text(json.dumps({
        "gerado_em": date.today().isoformat(),
        "fonte": "iNaturalist (fotos do táxon e de exemplares cultivados no Brasil); decisões em fotos.csv",
        "especies": especies,
    }, ensure_ascii=False, indent=1), encoding="utf-8")
    sem_foto = [a for a, e in especies.items() if not e["foto"]]
    marcadas = [a for a, e in especies.items() if e["revisar"] and not e["revisada"]]
    print(f"{len(especies) - len(sem_foto)} com foto, {len(sem_foto)} sem; "
          f"{sum(not e['revisada'] for e in especies.values())} a revisar ({len(marcadas)} marcadas)")
    gerar_pagina(catalogo, levantamento, especies, candidatas)
    print(f"Revisão: {PAGINA}")


# --- Página de revisão ------------------------------------------------------------------------

def gerar_pagina(catalogo, levantamento, especies, candidatas):
    def ordem(arquivo):
        e = especies[arquivo]
        posicao = (levantamento.get(arquivo) or {}).get("posicao") or 9999
        return (e["revisada"], not e["revisar"], posicao)

    dados = []
    for arquivo in sorted(especies, key=ordem):
        e = especies[arquivo]
        dados.append({
            "arquivo": arquivo,
            "nome": catalogo[arquivo]["nome_comum"],
            "cientifico": e["nome_cientifico"],
            "taxon": e["taxon"] and e["taxon"]["nome"],
            "revisar": e["revisar"],
            "revisada": e["revisada"],
            "foto": e["foto"] and e["foto"]["id"],
            "candidatas": [{k: f[k] for k in ("id", "url", "grande", "autor", "licenca", "origem", "taxon", "pagina")}
                           for f in candidatas[arquivo]],
        })
    PAGINA.parent.mkdir(parents=True, exist_ok=True)
    PAGINA.write_text(MODELO.replace("__DADOS__", json.dumps(dados, ensure_ascii=False).replace("</", "<\\/")),
                      encoding="utf-8")


MODELO = """<!doctype html>
<html lang="pt-BR"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Fotos do catálogo</title>
<style>
:root { --fundo:#f7f8f3; --texto:#1b1c18; --suave:#5d5f55; --cartao:#fff; --borda:#dde0d4;
  --marca:#3a6a1e; --alerta:#9a4a00; --troca:#1f5fa8; }
@media (prefers-color-scheme: dark) { :root { --fundo:#131410; --texto:#e4e4dc; --suave:#a9ab9f;
  --cartao:#1c1d18; --borda:#34362e; --marca:#9fd67a; --alerta:#ffb77c; --troca:#9cc4ff; } }
* { box-sizing:border-box }
body { margin:0; background:var(--fundo); color:var(--texto); font:14px/1.4 system-ui, sans-serif }
header { position:sticky; top:0; z-index:2; background:var(--fundo); border-bottom:1px solid var(--borda); padding:12px 16px }
h1 { font-size:18px; margin:0 0 4px } h2 { font-size:15px; margin:24px 16px 8px }
header p { margin:0; color:var(--suave) }
.grade { display:grid; grid-template-columns:repeat(auto-fill, minmax(150px, 1fr)); gap:12px; padding:0 16px }
.item { background:var(--cartao); border:1px solid var(--borda); border-radius:12px; padding:8px; cursor:pointer }
.item.trocada { border-color:var(--troca); box-shadow:0 0 0 1px var(--troca) }
.item.semfoto { opacity:.7 }
.mini { width:100%; aspect-ratio:1; border-radius:12px; object-fit:cover; background:var(--borda); display:block }
.vazio { display:flex; align-items:center; justify-content:center; font-size:32px }
.nome { font-weight:600; margin-top:6px } .cient { font-style:italic; color:var(--suave); font-size:12px;
  overflow:hidden; display:-webkit-box; -webkit-line-clamp:2; -webkit-box-orient:vertical }
.marca { display:inline-block; font-size:11px; padding:1px 6px; border-radius:8px; margin:4px 4px 0 0;
  background:color-mix(in srgb, var(--alerta) 15%, transparent); color:var(--alerta) }
.marca.t { background:color-mix(in srgb, var(--troca) 15%, transparent); color:var(--troca) }
dialog { width:min(960px, calc(100vw - 32px)); max-height:calc(100vh - 32px); border:1px solid var(--borda);
  border-radius:16px; background:var(--cartao); color:var(--texto); padding:16px }
dialog .grade { padding:0; grid-template-columns:repeat(auto-fill, minmax(180px, 1fr)) }
.cand { border:2px solid transparent; border-radius:14px; padding:4px; cursor:pointer }
.cand.atual { border-color:var(--marca) }
.cand small { display:block; color:var(--suave); font-size:11px; overflow-wrap:anywhere }
button { font:inherit; padding:8px 14px; border-radius:20px; border:1px solid var(--borda); background:var(--cartao);
  color:var(--texto); cursor:pointer }
button.principal { background:var(--marca); color:var(--fundo); border-color:var(--marca) }
.acoes { display:flex; gap:8px; flex-wrap:wrap; margin-top:12px }
textarea { width:100%; height:160px; font:12px ui-monospace, monospace; background:var(--fundo); color:var(--texto);
  border:1px solid var(--borda); border-radius:8px; padding:8px }
footer { padding:16px }
</style></head><body>
<header><h1>Fotos do catálogo</h1>
<p>Cada miniatura aparece recortada como na busca do app. Toque numa foto ruim para trocar.
No fim, “Gerar linhas” aprova todas as desta página que você não trocou.</p></header>
<main id="main"></main>
<footer>
<div class="acoes"><button class="principal" id="gerar">Gerar linhas para dados/fotos.csv</button>
<button id="copiar">Copiar</button><button id="limpar">Desfazer minhas trocas</button></div>
<p id="resumo"></p><textarea id="saida" readonly></textarea>
</footer>
<dialog id="dlg"><h2 id="dtitulo" style="margin:0 0 4px"></h2><p id="dsub" style="margin:0 0 12px"></p>
<div class="grade" id="dgrade"></div>
<div class="acoes"><button id="dsem">Nenhuma serve (sem foto)</button><button id="dfechar">Fechar</button></div></dialog>
<script>
const DADOS = __DADOS__;
const CHAVE = "fotos-jardim-trocas";
let trocas = {};
try { trocas = JSON.parse(localStorage.getItem(CHAVE) || "{}"); } catch (e) {}
const salvar = () => { try { localStorage.setItem(CHAVE, JSON.stringify(trocas)); } catch (e) {} };
const atual = d => d.arquivo in trocas ? trocas[d.arquivo] : d.foto;
const porId = (d, id) => d.candidatas.find(c => c.id === id);
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));

function cartao(d) {
  const id = atual(d), f = id && porId(d, id), trocada = d.arquivo in trocas;
  const marcas = d.revisar.map(m => `<span class="marca">${esc(m)}</span>`).join("")
    + (trocada ? '<span class="marca t">trocada</span>' : "");
  const img = f ? `<img class="mini" loading="lazy" src="${esc(f.url)}" alt="">` : '<div class="mini vazio">🌱</div>';
  return `<div class="item${trocada ? " trocada" : ""}${f ? "" : " semfoto"}" data-a="${esc(d.arquivo)}">${img}
    <div class="nome">${esc(d.nome)}</div><div class="cient">${esc(d.cientifico)}</div>${marcas}</div>`;
}
function desenhar() {
  const secoes = [["A revisar, marcadas", d => !d.revisada && d.revisar.length],
                  ["A revisar", d => !d.revisada && !d.revisar.length],
                  ["Já revisadas (só entram nas linhas se você trocar)", d => d.revisada]];
  document.getElementById("main").innerHTML = secoes.map(([t, f]) => {
    const itens = DADOS.filter(f);
    return itens.length ? `<h2>${t} (${itens.length})</h2><div class="grade">${itens.map(cartao).join("")}</div>` : "";
  }).join("");
}
let aberto = null;
const dlg = document.getElementById("dlg");
document.getElementById("main").addEventListener("click", e => {
  const item = e.target.closest(".item"); if (!item) return;
  aberto = DADOS.find(d => d.arquivo === item.dataset.a);
  document.getElementById("dtitulo").textContent = aberto.nome;
  document.getElementById("dsub").innerHTML = `<i>${esc(aberto.cientifico)}</i> · táxon ${esc(aberto.taxon || "nenhum")}`;
  document.getElementById("dgrade").innerHTML = aberto.candidatas.map(c =>
    `<div class="cand${c.id === atual(aberto) ? " atual" : ""}" data-id="${c.id}">
     <img class="mini" loading="lazy" src="${esc(c.grande)}" alt="">
     <small>${esc(c.origem)} · ${esc(c.taxon)}<br>${esc(c.autor)} · ${esc(c.licenca)} · <a href="${esc(c.pagina)}" target="_blank" rel="noopener">abrir</a></small></div>`
  ).join("") || "<p>Nenhuma candidata com licença livre.</p>";
  dlg.showModal();
});
document.getElementById("dgrade").addEventListener("click", e => {
  if (e.target.closest("a")) return;
  const c = e.target.closest(".cand"); if (!c) return;
  escolher(Number(c.dataset.id));
});
function escolher(id) {
  if (id === aberto.foto) delete trocas[aberto.arquivo]; else trocas[aberto.arquivo] = id;
  salvar(); dlg.close(); desenhar();
}
document.getElementById("dsem").onclick = () => escolher(null);
document.getElementById("dfechar").onclick = () => dlg.close();
document.getElementById("gerar").onclick = () => {
  const linhas = DADOS.filter(d => !d.revisada || d.arquivo in trocas).map(d => {
    const id = atual(d);
    return `${d.arquivo};${id ? "inat:" + id : "SEM_FOTO"};${d.arquivo in trocas ? "trocada na revisão" : ""}`;
  });
  document.getElementById("saida").value = linhas.join("\\n");
  document.getElementById("resumo").textContent =
    `${linhas.length} linhas (${Object.keys(trocas).length} trocadas). Cole no fim de dados/fotos.csv e rode python dados/fotos.py.`;
};
document.getElementById("copiar").onclick = () => {
  const t = document.getElementById("saida"); t.select();
  navigator.clipboard?.writeText(t.value).catch(() => document.execCommand("copy"));
};
document.getElementById("limpar").onclick = () => { if (confirm("Desfazer todas as trocas?")) { trocas = {}; salvar(); desenhar(); } };
desenhar();
</script></body></html>
"""

if __name__ == "__main__":
    main()
