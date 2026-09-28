---
name: revisar-planta
description: Revisa o conteúdo de um JSON de cultivo do catálogo do Jardim (o formato da skill planta-cultivo-json) contra fontes na web, como nome científico, temperaturas, rega, época de plantio, dias de colheita, luz e doses de produtos, e devolve os achados em JSON, cada um com a fonte. Use quando o usuário chamar /revisar-planta com o caminho de um JSON, ou pedir para conferir, auditar ou checar se as informações de uma planta do catálogo estão certas.
argument-hint: <caminho do JSON da planta>
---

# Revisão de um JSON de cultivo

**Arquivo:** $ARGUMENTS

O JSON foi escrito por outro modelo, de memória, sem consultar fonte nenhuma. Ele já passou no validador de formato, então o erro que sobrou é de conteúdo: um número plausível mas errado, uma planta trocada por outra, uma dose que queima. Seu trabalho é achar esses erros **com prova**. Quem lê os achados aceita ou recusa um por um, e cada achado aceito muda o que o app manda alguém fazer com uma planta de verdade.

Duas regras valem mais que o resto:

- **Sem fonte, sem achado.** Todo achado cita pelo menos uma página que você abriu e que sustenta a correção. A sua memória serve para desconfiar, não para corrigir: você aprendeu com os mesmos textos do modelo que escreveu o JSON e tende a repetir os mesmos enganos.
- **Convenção diferente não é erro.** As fontes costumam falar de cultivo no chão e no hemisfério norte; o JSON fala de vaso no Centro-Sul do Brasil. Converta antes de comparar (meses do hemisfério norte: some 6). Se a fonte dá uma faixa e o JSON está dentro dela, está certo. Um achado que troca um número razoável por outro número razoável só gasta o tempo de quem revisa.

Argumento vazio ou arquivo que não existe: diga isso em uma frase, sem JSON.

## 1. Ler

Leia o arquivo. O que cada campo significa e as premissas do formato (vaso primeiro, dia 1 é o dia em que a pessoa começa, clima do Centro-Sul, idade versus calendário) estão nas seções 2 e 3 de `${CLAUDE_SKILL_DIR}/../planta-cultivo-json/SKILL.md`. Leia essas duas seções antes de julgar: um campo pode parecer errado só porque você imaginou outra convenção.

Não altere o arquivo.

## 2. O que conferir

Na ordem do estrago que um erro faz. Confira sempre os itens 1 a 4.

1. **Identidade.** O `nome_cientifico` é a planta que se chama `nome_comum` no Brasil? Se a planta estiver trocada, o resto do JSON descreve outra espécie: registre esse achado e pare por aí.
2. **Números que viram lembrete no celular.** Um erro aqui faz a pessoa agir errado, e na hora errada.
   - `temperatura_minima_c` (o app manda abrigar a planta quando a previsão fica abaixo disso) e `temperatura_maxima_c`.
   - `meses_plantio` e os `meses` de `eventos_sazonais` (floração, poda, dormência).
   - A rega de regime, que é a tarefa `REGA` com a janela mais longa: `frequencia_dias` para aquele volume de vaso, e `critica`. Fontes quase nunca dão a rega em dias. Julgue pela tolerância da planta à seca e ao encharcamento, que as fontes descrevem.
   - Os dias que marcam o ciclo: germinação, primeira colheita, `duracao_ciclo_dias` de planta anual.
   - `luminosidade` e horas de sol.
3. **Doses e produtos**, nas instruções de adubação e em `pragas_comuns[].prevencao_tratamento`. Confira a concentração (neem, bicarbonato, calda, adubo em gramas por litro de vaso) e se o produto serve para aquele problema. Dose errada queima a planta.
4. **Pragas e doenças.** As listadas atacam mesmo essa planta? O agente causador tem o nome científico certo?
5. **Texto** (descrições das fases, instruções). Aponte só as afirmações que alguma fonte aberta para os itens acima desmentiu. Não faça busca só para conferir o texto.

## 3. Fontes

- Prefira: Embrapa, universidades e serviços de extensão (ESALQ, UFV, UF/IFAS, NC State Extension, RHS, Missouri Botanical Garden), Flora e Funga do Brasil e POWO (Kew) para nome científico, CABI para pragas e doenças.
- Evite: blogs, lojas, sites de dicas e vídeos. Se só achar esse tipo de fonte, o achado fica no máximo como `BAIXA`.
- Faça de 3 a 8 buscas no total e abra só as páginas de que precisar. Cada página custa, e a revisão roda no catálogo inteiro.

## 4. Gravidade

- `ALTA`: quem seguir o JSON perde a planta ou a colheita. Exemplos: planta trocada; rega que apodrece ou seca; mínima baixa demais, que deixa a geada passar sem aviso; plantio numa estação em que ela não vinga; dose que queima.
- `MEDIA`: a planta sobrevive, mas rende ou floresce bem menos, ou o app avisa à toa. Exemplos: valor fora da faixa das fontes; fase com duração muito diferente; luz errada em uma categoria; mínima alta demais, que manda abrigar a planta em noites que não fazem mal. Aviso demais não mata a planta, mas ensina a pessoa a ignorar o app.
- `BAIXA`: imprecisão que não muda o que a pessoa faz. Exemplos: nome antigo de patógeno; detalhe de texto.

## 5. Resposta

Responda só com um bloco ```json, sem texto antes ou depois, porque quem chama esta skill lê o bloco direto:

```json
{
  "achados": [
    {
      "campo": "tarefas_agendadas[rega_padrao].frequencia_dias",
      "atual": 2,
      "proposto": 10,
      "gravidade": "ALTA",
      "motivo": "A babosa guarda água nas folhas e apodrece com o substrato úmido; as fontes mandam regar só com o substrato seco, a cada 2 ou 3 semanas no calor.",
      "fontes": ["https://..."]
    }
  ],
  "conferidos": [
    { "campo": "planta.temperatura_minima_c", "fontes": ["https://..."] }
  ]
}
```

- `campo` é o caminho no JSON: `.` entre as chaves, e o item de uma lista entre colchetes. Em `tarefas_agendadas` o item vai pelo `id`; em `fases_ciclo_vida`, pelo `fase_id`; nas outras listas, pela posição a partir de 0 (`pragas_comuns[2].prevencao_tratamento`). Uma lista de números, como `planta.meses_plantio`, é um campo só.
- `atual` e `proposto` têm o mesmo tipo do campo. `proposto` é o valor pronto para substituir o atual. Num campo de texto, é o texto inteiro já corrigido, mudando só o trecho errado.
- Uma correção que mexe em vários campos (luminosidade e horas de sol, por exemplo) vira um achado por campo.
- `conferidos` lista o que você checou numa fonte e estava certo. É assim que quem lê sabe o que foi coberto: "nenhum achado" com `conferidos` vazio não diz nada.
- Nenhum achado é uma resposta válida, e esperada, quando o JSON está certo.
