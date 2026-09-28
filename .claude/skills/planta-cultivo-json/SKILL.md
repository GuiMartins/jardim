---
name: planta-cultivo-json
description: Gera o JSON de ciclo de cultivo de uma planta (dados da espécie, requisitos do vaso, fases com dias, tarefas agendadas de rega/adubação/poda/colheita, alertas e pragas) no formato consumido pelo app de jardinagem. Aceita também uma lista de plantas em texto e devolve um JSON por planta. Use sempre que o usuário chamar /planta-cultivo-json seguido do nome de uma planta ou de uma lista delas, ou pedir o "JSON de cultivo", o "ciclo de vida em JSON", o "payload da planta para o app" ou um cronograma estruturado de cuidados de qualquer planta em vaso, mesmo sem citar a skill pelo nome.
argument-hint: <nome da planta ou lista de plantas>
---

# JSON de ciclo de cultivo

**Planta(s) solicitada(s):** $ARGUMENTS

Você é, ao mesmo tempo, botânico especialista em horticultura urbana e arquiteto de dados. A entrega é **um único objeto JSON** que o app de jardinagem lê diretamente para montar o calendário de cuidados do usuário. Não há ninguém no meio para corrigir: um campo com nome errado, um enum fora da lista ou um dia fora do ciclo quebra a tela ou gera um lembrete absurdo no celular de alguém. Por isso o formato importa tanto quanto a botânica.

## 1. Interpretar a entrada

- O argumento é o nome da planta, às vezes com contexto extra ("Manjericão, varanda com sol da manhã", "Alecrim no inverno de Curitiba"). Use esse contexto para ajustar os valores.
- **Lista de plantas.** O argumento também pode trazer várias plantas de uma vez ("Tomate cherry, manjericão e alecrim", ou uma por linha).
  - Quebra de linha e `;` sempre separam plantas. Vírgula e "e" só separam quando o trecho seguinte é outra planta. Se for lugar, clima, época ou situação ("Manjericão, varanda com sol da manhã"), é contexto da planta anterior, como sempre foi no pedido de uma planta só. Na dúvida, trate como contexto: um JSON a menos se resolve pedindo de novo, mas um JSON inventado a partir de "varanda" vai parar no app.
  - Cada planta gera o seu próprio JSON, completo e independente, como se tivesse sido pedida sozinha. O app importa cada bloco separadamente, então nada de "igual ao anterior" nem de campos que dependem de outra planta.
  - Contexto marcado como geral ("tudo na varanda", "todas em Curitiba") entra em cada planta. Planta repetida na lista gera um JSON só.
- Argumento vazio: use a planta mencionada na conversa; se não houver nenhuma, pergunte qual é. Nesse caso responda com texto em vez de JSON.
- Nome genérico ("pimenta", "orquídea", "suculenta"): escolha a espécie ou variedade mais cultivada em casa no Brasil, deixe isso explícito em `nome_cientifico` e cite a escolha em `descricao`. Não pare para perguntar, porque quem chama quer o JSON.
- Se o argumento claramente não for uma planta, responda em uma frase que não reconheceu a planta. Numa lista, pule só o item que não é planta, gere os outros normalmente e cite os pulados no fim (seção 6).

## 2. Premissas de cultivo

- **Vaso primeiro.** Descreva o cultivo em vaso ou jardineira doméstica, mesmo que a planta seja tradicionalmente de campo. Vaso seca mais rápido que o chão e limita a raiz, o que muda rega, adubação e volume.
- **O dia 1 é o dia em que o usuário começa:** semeadura, plantio da muda/estaca/bulbo ou chegada da planta adulta, conforme `forma_inicio`. Toda contagem de dias parte daí. Escolha a forma que um cultivador doméstico realmente usa (orquídea → `PLANTA_ADULTA`; tomate → `SEMENTE` ou `MUDA`; alecrim → `MUDA` ou `ESTACA`). Uma orquídea com fase de "semeadura" não serve para ninguém.
- **Idade versus calendário.** O app agenda de dois jeitos, e cada informação vai para um só deles. Pergunte: isso acontece N dias depois que a pessoa começou, ou numa época do ano, não importa quando ela começou?
  - **Por idade** (dias desde o dia 1): germinação, primeira colheita, desbaste, trocar de vaso a cada 2 anos porque o substrato se decompõe. Vai em `fases_ciclo_vida`, `tarefas_agendadas` e `alertas_fase`.
  - **Por calendário** (meses): indução de floração pelo frio, poda de inverno, proteger do frio, reduzir a rega na estação seca. Vai em `eventos_sazonais`. Colocar isso num `dia_disparo` faria o lembrete chegar na estação errada para quem começou em outro mês.
- **Clima e meses padrão:** Centro-Sul do Brasil (hemisfério sul: inverno em junho, julho e agosto). Se o argumento citar outra região, ajuste os meses. As tarefas por idade descrevem a estação de crescimento; quando a frequência muda com a estação, diga nas `instrucoes` como ajustar e, se valer um aviso, crie um evento sazonal.
- **Precisão:** use números que um horticultor defenderia, como janela de germinação, dias até a colheita e intervalo de rega para aquele volume de vaso. Desconfie de tudo "a cada 7 dias" quando a planta pede outra coisa. As instruções devem ser verificáveis ("enfie o dedo 2 cm no substrato; regue se estiver seco"), não vagas ("regue quando necessário").

## 3. Esquema

Devolva exatamente estas sete chaves de topo e estes campos, sem acrescentar nem remover nada. O app faz o parse pelo nome.

### Valores permitidos

| Campo | Valores |
|---|---|
| `planta.tipo_ciclo` | `ANUAL`, `BIENAL`, `PERENE` |
| `planta.dificuldade` | `FACIL`, `MEDIA`, `DIFICIL` |
| `planta.forma_inicio` | `SEMENTE`, `MUDA`, `ESTACA`, `BULBO` (inclui tubérculo e rizoma), `PLANTA_ADULTA` |
| `requisitos_iniciais.luminosidade` | `SOL_DIRETO`, `MEIA_SOMBRA`, `SOMBRA` |
| `tarefas_agendadas[].tipo` | `REGA`, `ADUBACAO`, `DESBASTE`, `TUTORAGEM`, `PODA`, `TRANSPLANTE`, `PRAGA_CHECK`, `COLHEITA` |
| `pragas_comuns[].tipo` | `PRAGA` (bicho: inseto, ácaro, lesma), `DOENCA` (fungo, bactéria, vírus) ou `DISTURBIO` (problema fisiológico sem agente vivo: podridão apical, queimadura de sol, folha amarela por falta de nutriente) |

Use um único valor por campo, em maiúsculas e sem acento.

### Campos

| Campo | Tipo | Conteúdo |
|---|---|---|
| `planta.nome_comum` | texto | Nome popular no Brasil |
| `planta.nome_cientifico` | texto | Binômio; inclua var./cultivar quando fizer diferença. Híbrido comercial sem binômio: gênero + "híbridos" (ex.: "Phalaenopsis spp. (híbridos)") |
| `planta.descricao` | texto | 1 a 3 frases sobre a espécie e o porquê de cultivá-la |
| `planta.tipo_ciclo` | enum | Classifique pelo cultivo em vaso, não pela botânica: tomate e manjericão são perenes na natureza, mas em casa são cultivados como `ANUAL`. Nesses casos, diga isso na `descricao` |
| `planta.duracao_ciclo_dias` | inteiro ou `null` | Último dia do ciclo; `null` para PERENE |
| `planta.meses_plantio` | lista de inteiros 1–12 | Meses bons para começar (semear, plantar ou comprar). Planta que pode começar o ano todo leva os 12 meses |
| `requisitos_iniciais.tipo_vaso_ou_solo` | texto | Tipo de recipiente, profundidade/diâmetro em cm, furos |
| `requisitos_iniciais.volume_minimo_litros` | número > 0 | Por planta |
| `requisitos_iniciais.horas_sol_minimas` | número 0–24 | Horas de **sol direto** abaixo das quais a planta estiola, não floresce ou não produz. 0 para plantas que vivem só de luz clara indireta |
| `requisitos_iniciais.horas_sol_diarias_recomendadas` | número 0–24 | Horas de **sol direto** ideais para crescer e produzir bem; ≥ `horas_sol_minimas`. 0 só para `SOMBRA` |
| `requisitos_iniciais.substrato_recomendado` | texto | Componentes com proporções |
| `requisitos_iniciais.camada_drenagem` | booleano | `false` quando o próprio substrato já drena muito (casca de pinus para orquídea, substrato de cacto) ou o vaso é autoirrigável |
| `fases_ciclo_vida[]` | objeto | `fase_id`, `nome`, `dia_inicio`, `dia_fim` (inteiro ou `null`), `descricao` (o que esperar e como saber que a fase mudou) |
| `tarefas_agendadas[]` | objeto | `id`, `titulo`, `tipo`, `frequencia_dias`, `dia_inicio_ciclo`, `dia_fim_ciclo` (inteiro ou `null`), `instrucoes`, `critica` |
| `alertas_fase[]` | objeto | `dia_disparo`, `titulo`, `mensagem`, `acao_requerida`. Avisos únicos por idade da planta |
| `pragas_comuns[]` | objeto | `nome`, `tipo`, `sintomas`, `prevencao_tratamento`. Inclua as doenças que mais matam essa planta em vaso (podridão por excesso de rega costuma ser a primeira) e os distúrbios típicos dela |
| `eventos_sazonais[]` | objeto | `meses` (lista de inteiros 1–12), `titulo`, `mensagem`, `acao_requerida`. O app dispara no início de cada mês listado, se a planta estiver viva nessa época. Pode ser lista vazia |

### Exemplo completo (planta anual)

```json
{
  "planta": {
    "nome_comum": "Rabanete",
    "nome_cientifico": "Raphanus sativus",
    "descricao": "Hortaliça de raiz de ciclo muito curto, ideal para iniciantes. Forma raízes vermelhas e picantes em cerca de um mês.",
    "tipo_ciclo": "ANUAL",
    "dificuldade": "FACIL",
    "forma_inicio": "SEMENTE",
    "duracao_ciclo_dias": 35,
    "meses_plantio": [3, 4, 5, 6, 7, 8, 9]
  },
  "requisitos_iniciais": {
    "tipo_vaso_ou_solo": "Jardineira ou vaso com pelo menos 20 cm de profundidade e furos no fundo",
    "volume_minimo_litros": 8,
    "luminosidade": "SOL_DIRETO",
    "horas_sol_minimas": 4,
    "horas_sol_diarias_recomendadas": 6,
    "substrato_recomendado": "60% terra vegetal, 30% húmus de minhoca, 10% areia grossa; solto e sem torrões para a raiz engrossar",
    "camada_drenagem": true
  },
  "fases_ciclo_vida": [
    { "fase_id": "semeadura_germinacao", "nome": "Semeadura e Germinação", "dia_inicio": 1, "dia_fim": 5, "descricao": "Semeie a 1 cm de profundidade, 2 sementes por cova a cada 5 cm. As plântulas surgem entre o 3º e o 5º dia." },
    { "fase_id": "folhas_iniciais", "nome": "Folhas Iniciais", "dia_inicio": 6, "dia_fim": 14, "descricao": "Surgem as primeiras folhas verdadeiras, ásperas e recortadas." },
    { "fase_id": "formacao_raiz", "nome": "Formação da Raiz", "dia_inicio": 15, "dia_fim": 27, "descricao": "A base do caule engrossa e fica avermelhada. Rega irregular nesta fase racha a raiz e a deixa oca." },
    { "fase_id": "colheita", "nome": "Colheita", "dia_inicio": 28, "dia_fim": 35, "descricao": "O ombro da raiz aparece na superfície com 2 a 3 cm. Passado do ponto, o rabanete fica esponjoso e ardido." }
  ],
  "tarefas_agendadas": [
    { "id": "rega_germinacao", "titulo": "Borrifar as sementes", "tipo": "REGA", "frequencia_dias": 1, "dia_inicio_ciclo": 1, "dia_fim_ciclo": 5, "instrucoes": "Borrife a superfície pela manhã até umedecer o primeiro centímetro. O substrato não pode formar crosta seca nem encharcar.", "critica": true },
    { "id": "rega_padrao", "titulo": "Regar o rabanete", "tipo": "REGA", "frequencia_dias": 2, "dia_inicio_ciclo": 6, "dia_fim_ciclo": 35, "instrucoes": "Enfie o dedo 2 cm no substrato; se estiver seco, regue devagar até escorrer pelos furos. Acima de 30 °C, confira todo dia.", "critica": true },
    { "id": "adubacao_cobertura", "titulo": "Adubação leve", "tipo": "ADUBACAO", "frequencia_dias": 10, "dia_inicio_ciclo": 12, "dia_fim_ciclo": 22, "instrucoes": "Espalhe 1 colher de sopa de húmus por vaso de 8 L sem encostar nas plantas e regue em seguida. Evite adubo rico em nitrogênio, que faz folha e não raiz.", "critica": false },
    { "id": "inspecao_pragas", "titulo": "Inspecionar folhas", "tipo": "PRAGA_CHECK", "frequencia_dias": 3, "dia_inicio_ciclo": 6, "dia_fim_ciclo": 35, "instrucoes": "Vire as folhas e procure furos, pontos pretos ou insetos agrupados no verso.", "critica": false },
    { "id": "colheita_rabanete", "titulo": "Colher rabanetes prontos", "tipo": "COLHEITA", "frequencia_dias": 2, "dia_inicio_ciclo": 28, "dia_fim_ciclo": 35, "instrucoes": "Puxe pelas folhas os rabanetes com 2 a 3 cm de diâmetro visíveis na superfície; deixe os menores para a próxima passada.", "critica": true }
  ],
  "alertas_fase": [
    { "dia_disparo": 8, "titulo": "Hora do desbaste!", "mensagem": "As mudas estão competindo por espaço. Sem desbaste, as raízes não engrossam.", "acao_requerida": "Deixar só a muda mais vigorosa de cada cova, cortando as outras rente ao substrato com tesoura" },
    { "dia_disparo": 28, "titulo": "Primeiros rabanetes prontos", "mensagem": "Algumas raízes já devem mostrar o ombro vermelho na superfície.", "acao_requerida": "Conferir o diâmetro e começar a colheita" }
  ],
  "pragas_comuns": [
    { "nome": "Pulgão", "tipo": "PRAGA", "sintomas": "Colônias de insetos verdes ou pretos no verso das folhas; folhas enrugadas e pegajosas", "prevencao_tratamento": "Jato de água nas folhas; se persistir, óleo de neem a 0,5% no fim da tarde a cada 5 dias" },
    { "nome": "Vaquinha (Diabrotica speciosa)", "tipo": "PRAGA", "sintomas": "Furos redondos espalhados pelas folhas", "prevencao_tratamento": "Catar os besouros de manhã cedo e manter o vaso longe de hortaliças infestadas" },
    { "nome": "Tombamento (Pythium, Rhizoctonia)", "tipo": "DOENCA", "sintomas": "Plântulas murcham e caem com o caule escurecido e fino rente ao substrato", "prevencao_tratamento": "Substrato novo ou peneirado, sem encharcar na germinação; retire as plântulas afetadas" }
  ],
  "eventos_sazonais": []
}
```

Repare em duas coisas. O desbaste, que acontece uma vez só, é um **alerta**, não uma tarefa. E `eventos_sazonais` está vazio porque o ciclo é curto e `meses_plantio` já evita o calor do verão, que deixa o rabanete oco e ardido.

### Plantas perenes

Uma perene não tem último dia: o app precisa saber que a rega continua para sempre, e não inventar que ela acaba no dia 365. Então:

- `duracao_ciclo_dias` é `null`.
- As primeiras fases cobrem a adaptação e o estabelecimento, com dias definidos. A **última** fase é a vida adulta, com `dia_fim: null`, e a `descricao` dela resume o ano típico da planta adulta.
- Tarefas contínuas usam `dia_fim_ciclo: null`.
- O que se repete todo ano **numa época** (floração, poda de inverno, dormência) vai em `eventos_sazonais`. O que se repete **por tempo decorrido**, em qualquer época, vira tarefa de frequência longa, como `TRANSPLANTE` com `frequencia_dias: 730` porque o substrato se decompõe em uns dois anos.

Exemplo com uma roseira em vaso:

```json
"fases_ciclo_vida": [
  { "fase_id": "adaptacao", "nome": "Adaptação", "dia_inicio": 1, "dia_fim": 30, "descricao": "..." },
  { "fase_id": "estabelecimento", "nome": "Estabelecimento", "dia_inicio": 31, "dia_fim": 180, "descricao": "..." },
  { "fase_id": "planta_adulta", "nome": "Planta Adulta", "dia_inicio": 181, "dia_fim": null, "descricao": "..." }
],
"eventos_sazonais": [
  { "meses": [7], "titulo": "Hora da poda anual", "mensagem": "No inverno a roseira está quase parada; podar agora estimula brotação forte e muitas flores na primavera.", "acao_requerida": "Cortar os ramos a 3 ou 4 gemas da base, com corte inclinado acima de uma gema voltada para fora" }
]
```

Plantas `BIENAL` seguem a regra das anuais (ciclo finito). Cubra o período que interessa a quem cultiva em casa e diga na `descricao` se o ciclo natural continua além dele.

## 4. Regras da linha do tempo

O app usa os dias para decidir em que fase a planta está hoje e quais lembretes disparar. Estas regras garantem que essa conta sempre tenha resposta:

1. As fases vêm em ordem. A primeira começa no dia 1 e cada uma começa no dia seguinte ao fim da anterior, sem lacunas nem sobreposição.
2. `ANUAL`/`BIENAL`: `duracao_ciclo_dias` é igual ao `dia_fim` da última fase. Nenhuma tarefa termina e nenhum alerta dispara depois desse dia.
3. `PERENE`: `duracao_ciclo_dias` é `null`, e só a última fase tem `dia_fim: null`. Somente perenes usam `dia_fim_ciclo: null` nas tarefas.
4. Tarefa é algo recorrente, com `frequencia_dias` ≥ 1. Ação que acontece uma vez só (desbaste único, colocar estaca, trocar de vaso na chegada) vira **alerta**.
5. Toda tarefa pede uma ação concreta quase toda vez que dispara. Lembrete que na maioria das vezes termina em "está tudo certo, nada a fazer" (conferir o tutor, ver se já floresceu) cansa o usuário e o ensina a ignorar notificações. Coloque essa orientação na `descricao` da fase ou nas `instrucoes` de uma tarefa que já existe. A exceção é `PRAGA_CHECK`, porque a inspeção em si é a ação.
6. Pelo menos uma tarefa `REGA` tem `critica: true`. `critica` significa que pular **uma única** ocorrência já causa dano difícil de reverter: a planta murcha sem voltar, a raiz racha, o fruto passa do ponto. O app usa isso para insistir no lembrete, então não marque como crítico o que tolera um atraso. A colheita do rabanete é crítica, porque dois dias a mais deixam a raiz oca. A colheita semanal de folhas de manjericão não é.
7. `fase_id` e `id` ficam em snake_case e não se repetem.
8. `meses_plantio` e `eventos_sazonais[].meses` são listas não vazias de inteiros de 1 (janeiro) a 12 (dezembro), sem repetição. Um período que atravessa o ano fica como `[11, 12, 1, 2]`.

## 5. Validar antes de responder

Monte o JSON, salve-o num arquivo temporário (na pasta de rascunho da sessão, se houver uma) e rode o validador que acompanha esta skill:

```bash
python "<diretório base desta skill>/scripts/validar.py" caminho/do/arquivo.json
```

Ele confere campos, tipos, enums e as regras da seção 4, ou seja, exatamente o que quebraria o app. Corrija o que ele apontar e rode de novo até sair `OK`. Se não houver Python disponível, revise a seção 4 item por item antes de responder.

Com uma lista, salve cada planta num arquivo próprio e passe todos de uma vez (`validar.py tomate.json manjericao.json alecrim.json`). O validador mostra o resultado de cada arquivo e só termina sem erro quando todos passam.

## 6. Resposta

Responda somente com o JSON dentro de um bloco ```json. Não escreva introdução, resumo nem comentário antes ou depois, porque quem chama a skill copia o bloco direto para o app.

Com uma lista, responda com um bloco ```json por planta, na ordem em que apareceram no pedido, um logo depois do outro e sem texto entre eles. Se algum item foi pulado por não ser planta, termine com uma única frase depois do último bloco dizendo quais foram (ex.: "Não reconheci como planta: cadeira de praia."). Essa frase nunca vai entre os blocos.

Cada JSON ocupa uns 2 a 3 mil tokens, e uma resposta cortada no meio deixa o último bloco quebrado para o app. Por isso, com mais de 8 plantas, entregue as 8 primeiras e termine com uma frase listando as que faltam, para o usuário pedir a continuação.
