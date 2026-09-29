# Jardim

App Android **pessoal** (APK instalado direto, sem Play Store) de acompanhamento de plantas. Construído em etapas: a cada etapa, propor alternativas, perguntar o que for decisão do usuário e só então implementar. O roteiro está no README. Leve ao usuário só as decisões que mudam o produto; quando uma feature virar um poço de casos-limite, ofereça cortá-la (foi assim com o "ainda está viva?", removido na etapa 3).

## Estrutura

- `motor/`: Kotlin puro (JVM), sem Android. Leitura e validação do JSON da planta (`leitura/`), modelo (`modelo/`) e cálculo da agenda (`agenda/`). Toda regra de "o que fazer hoje" mora aqui, com teste.
- `app/`: Android, Compose. `dados/` (Room, catálogo, repositório), `avisos/` (notificações e agendamento), `ui/` (telas e ViewModels), catálogo de espécies em `app/src/main/assets/catalogo/*.json`. Injeção de dependência manual em `JardimApp`.
- `dados/`: fora do build. `levantamento.py` (Python, só biblioteca padrão) gera `plantas-populares.json`, as 1000 plantas cultivadas mais populares do Brasil, que é a fila de espécies para o catálogo. Correções à mão vão em `curadoria.csv`, nunca no JSON gerado. Quando o JSON de um gênero (`Begonia spp.`) descreve uma espécie que também está na lista (Begônia-cerosa), o `levantamento.py` liga as duas ao mesmo arquivo (casa pelo nome científico do JSON) e o índice fica com os dados da espécie: marque a espécie com `EXCLUIR` na curadoria e ponha o nome dela nos outros nomes do gênero. Foi assim com Dendróbio, Filodendro, Begônia e Tilândsia (2026-09-25). As respostas das APIs ficam em `dados/cache/` (fora do git). Sem contato no User-Agent, a Wikipedia limita a 10 requisições por minuto: rodar do zero leva cerca de 1 hora; com o cache, segundos.

## JSON da planta

O formato é o da skill `planta-cultivo-json`, versionada em `.claude/skills/planta-cultivo-json/` (2026-09-27). É skill de projeto: o Claude Code só a enxerga aberto dentro do repositório, inclusive numa subpasta (o `gerar_catalogo.py` roda o `claude -p` em `dados/cache/geracao/<planta>/`). Não crie uma cópia em `~/.claude/skills/`: a pessoal tem prioridade sobre a de projeto, e as mudanças feitas aqui deixariam de valer sem aviso. `ValidadorCultivo` é o port do `scripts/validar.py` da skill, com as mesmas mensagens: mudou uma regra lá, muda aqui também. O teste `todo JSON do catálogo do app é válido` valida todo o catálogo a cada `:motor:test`.

`temperatura_minima_c` e `temperatura_maxima_c` (em `planta`, 2026-09-27) são os limites dos avisos do tempo. As espécies geradas antes deles foram preenchidas por `dados/temperaturas.py`, que manda ao claude só as frases sobre temperatura, em lotes (US$ 2,47 pelas 627, contra uns US$ 200 de rodar a skill de novo). Onde o texto já fixa o número ("com previsão abaixo de 5 °C, abrigue"), vale o texto: é a regra 9 da skill, e o claude bateu com ele em 96% dos casos (nos outros, ficou o texto).

## Revisão do conteúdo (2026-09-27)

O `validar.py` confere a forma; a skill `revisar-planta` (`.claude/skills/revisar-planta/`) confere o conteúdo contra fontes na web e devolve achados em JSON (`campo`, `atual`, `proposto`, `gravidade`, `fontes`). Sem fonte aberta, não há achado. `dados/revisar_catalogo.py` roda a skill em lote, com Sonnet (outro modelo que não o da geração, para não herdar os mesmos enganos), numa cópia do JSON em `dados/cache/revisao/<planta>/`:

- `--calibrar` revisa cópias com os erros de `evals/erros_plantados.json` (todos passam no `validar.py`, senão o validador entregaria a resposta) e mede quantos a skill acha. Primeira rodada: 9 de 10. As cópias ficam em `revisao-b/`, e não "calibracao", porque o claude vê o nome da pasta.
- `--amostra N` sorteia por categoria (uma por categoria, o resto proporcional) e o relatório (`dados/cache/revisao/relatorio.md`) pondera cada categoria pelo tamanho dela no catálogo. Amostra de 30 (2026-09-28): 2 com achado ALTA (~8% do catálogo, IC 3% a 23%), 19 com ALTA ou MEDIA, quase tudo `temperatura_minima_c` alta demais (a temperatura em que a planta para de crescer, não a de dano). A skill agora explica a mínima e ignora diferença de até 2 °C.
- `--todas --esperar` revisa o catálogo inteiro, da planta mais popular à menos, e no limite do plano espera 30 min e continua (decisão do usuário em 2026-09-28, ~US$ 0,60 por planta).
- Nada muda o catálogo sozinho: `--lista` escreve `dados/cache/revisao/achados.md` com os achados sem decisão, o usuário marca `[x]` (aplicar) ou `[-]` (recusar), e `--aplicar` grava, passa no `validar.py` e registra a decisão em `achados.json`. Girassol e rabanete, formatados à mão, só têm o valor trocado no texto.
- O Sonnet exagera a gravidade (marca ALTA o que a régua diz MEDIA): decida pela taxa de "ALTA ou MEDIA".
- Nome popular trocado (`Língua-de-leão` como `Hibiscus × archeri`) vem da `curadoria.csv`, não do gerador: a correção é lá, e a espécie precisa ser gerada de novo.

O **girassol** (`catalogo/girassol.json`) é o JSON de referência do projeto, fornecido pelo usuário: os testes de cenário real, os exemplos e as decisões de tela e notificação partem dele.

## Regras de produto

Etapa 1 (2026-09-24):
- Agenda calculada sob demanda (`Cronograma`). O banco guarda só o histórico (`RegistroAcao`), nunca ocorrências futuras.
- Tarefa recorrente atrasada não acumula: uma pendência só, com o atraso crescendo. A próxima vence `frequencia_dias` depois do dia em que foi feita de verdade. Se a janela fecha, ela some.
- Alerta único fica pendente até ser feito. Evento sazonal vale pelo mês (só se a planta já existia no dia 1 do mês).
- Notificação: um resumo diário num horário escolhido pelo usuário, mais um lembrete à noite para tarefas críticas atrasadas.
- Catálogo embutido em `assets/`. Importar JSON e baixar de servidor ficam para depois.

Etapa 3 (2026-09-24):
- Rega tem dois botões: "Feito" (`FEITA`) e "Conferi, não precisou" (`NAO_PRECISOU`). Os dois reiniciam a contagem; o histórico guarda a diferença. As outras pendências só têm "Feito".
- "Ressemeei hoje" (ou "Replantei", se não for semente) move o dia 1 do plantio para hoje. O histórico fica no banco, mas o `Cronograma` ignora registros anteriores ao dia 1.
- Começar fora de `meses_plantio` só gera aviso (`foraDeEpoca`), nunca bloqueia.
- Não existe mais a pergunta "essa planta ainda está viva?": para tirar uma planta da tela, o usuário usa "Encerrar plantio".

Etapa 4 (2026-09-24):
- Resumo às 8h e lembrete às 19h, ajustáveis em Configurações (DataStore). À noite só entram pendências `critica` (`paraAviso` no motor).
- Notificações em grupo: um resumo mais uma notificação por pendência, com "Feito" e, na rega, "Não precisou". Os itens chegam ordenados do mais importante ao menos importante e são publicados na ordem inversa, com `when` decrescente: o Samsung mostra na face do grupo o filho mais novo.
- A permissão de notificação é pedida ao tocar em "Semeei"/"Plantei". Se estiver negada, a tela Hoje mostra um cartão com atalho para as configurações do Android.

Etapa 5 (2026-09-25):
- Tela da planta com cinco seções: Agora (dia, progresso, fase atual), Próximos 7 dias, Fases (linha do tempo com datas), Problemas comuns (`pragas_comuns`) e Histórico. Sem diário de fotos por enquanto.
- A previsão (`Cronograma.proximosDias`) supõe que o pendente de hoje é feito hoje e o resto no dia em que vence. Ela roda o próprio `pendenciasEm` com um histórico imaginário, para nunca divergir da agenda real.
- O histórico mostra também o que foi feito antes de um "Ressemeei"; `Cronograma.identificar` traduz a chave (`tarefa:…`, `alerta:N`, `sazonal:N:AAAA-MM`) de volta para o título.

Depois da etapa 5 (2026-09-25):
- O resumo das 8h fica no WorkManager, sem alarme exato: o usuário aceitou que ele chegue quando o celular sai do Doze (no primeiro dia real, 08:44). Alarme exato com `USE_EXACT_ALARM` seria permitido, porque o app é instalado direto e a regra da Play Store não vale, mas foi recusado.
- O celular recebe o build **release** (R8 + shrink), assinado com a chave de debug desta máquina: instala por cima do debug sem perder o banco. Instalar de outra máquina exige desinstalar antes, e isso apaga o histórico.

## Avisos do tempo (2026-09-27)

- A cidade é escolhida uma vez, nas boas-vindas ou em Configurações: pela localização aproximada (`ACCESS_COARSE_LOCATION`, pedida só nessa hora, coordenadas arredondadas a 2 casas) ou pela busca da Open-Meteo. A previsão também é da Open-Meteo (grátis, sem chave), guardada no DataStore e renovada quando tem mais de 3 h: na abertura, ao voltar para o Hoje e antes de cada aviso. Sem rede, vale a última.
- "Onde ela vai ficar?" é obrigatório ao plantar, sem resposta marcada de início: `Local` (`AO_AR_LIVRE`, `COBERTO`, `DENTRO_DE_CASA`), gravado pelo nome no banco. Plantios de antes da pergunta ficam com `local` null (sem aviso do tempo) até a resposta, nas boas-vindas ou na tela da planta.
- O `Cronograma` recebe `Tempo` (local + previsão). Frio: mínima de amanhã, arredondada, abaixo de `temperatura_minima_c` (coberto ganha 2 °C); é crítico, então entra no lembrete das 19h. Calor: máxima de hoje, arredondada, acima de `temperatura_maxima_c`. Vento: rajada de 60 km/h ou mais. A chave é `tempo:<risco>:<data>` e vale só pelo dia.
- Chuva de 10 mm ou mais ontem só acrescenta contexto à rega de vaso ao ar livre ("confira com o dedo"): o tempo nunca some com uma tarefa.
- Fora do Centro-Sul (pelo estado da cidade; sem estado, latitude ao norte de 15° S), o app não diz "fora de época", porque os meses do catálogo são do Centro-Sul.
- As boas-vindas aparecem uma vez (flag no DataStore), por cima da navegação e embaixo da semente: explicam e pedem notificação e cidade, e perguntam onde fica cada planta antiga. Tudo é opcional.
- Banco v2: coluna `local`, e as cópias do JSON dos plantios completadas com as temperaturas do catálogo atual (`Migracoes.comTemperaturas`; espécie que saiu do catálogo leva 5 e 30 °C, a mediana).

## Nova planta em etapas e "Para plantar" (2026-09-28)

- Busca → **resumo** (`ResumoTela`: descrição, luz, vaso, temperatura, época) com dois botões: "Já semeei/plantei" vai direto aos dados do plantio (`PlantarTela`: apelido, dia 1, local), como antes; "Vou semear/plantar" vai ao **preparo** (`PrepararTela`). O verbo segue a `forma_inicio`: só 13% do catálogo começa por semente.
- O preparo é uma lista de caixas tirada dos campos que já existem (`itensDePreparo` no motor: início pela `forma_inicio`, vaso, substrato, drenagem só se `camada_drenagem`, lugar com luz). Extrair materiais mais finos do JSON (tutor, tela, adubo) ficou para depois, se a lista se mostrar útil.
- Salvar é **explícito** ("Salvar para depois"), nunca efeito de marcar uma caixa (decisão do usuário). Depois de salva, cada toque no preparo grava na hora. A lixeira do preparo tira a planta da lista.
- A planta salva fica na tabela `planejado`, fora de `plantio`, porque tudo o que lê `plantio` supõe um dia 1. Ao plantar, `plantarPlanejado` insere o plantio e apaga o planejamento na mesma transação. A cópia do JSON é feita só nessa hora, com o catálogo do dia.
- "Para plantar" fica no fim da tela Hoje, com o que falta e a época ("Está na época", "A época começa em março").
- **Aviso de época**: o resumo da manhã publica "Começou a época: X" uma vez por começo de época (`avisarEpoca`), fora do grupo das pendências e sem botão "Feito". Planejar dentro da época não avisa; se o dia 1 passar sem aviso, sai no primeiro resumo depois. `epocaAvisadaEm` só é gravado se a notificação saiu. "Testar agora" não gasta o aviso. Fora do Centro-Sul, não há aviso.
- Fotos das espécies ficaram para uma frente separada (o catálogo não tem imagem nenhuma).

## Fotos da busca (2026-09-29)

- Cada espécie tem uma foto do iNaturalist na busca (`MiniaturaPlanta`, 56 dp). **Não vão no APK** (decisão do usuário, contra a recomendação de embutir ~5 MB): o índice leva o endereço da miniatura `small` (240 px, ~33 KB, ~0,6 s para chegar) e `Miniaturas` baixa quando a linha aparece, recorta o centro em 160 px e guarda em `files/miniaturas` (WebP, ~5 KB). Só as que aparecem na tela, nunca todas de uma vez (também decisão do usuário). Sem internet, as nunca vistas mostram o emoji da categoria (`emojiDaCategoria`), igual à espécie sem foto boa.
- `dados/fotos.py` escolhe a foto e grava `dados/fotos.json`, que a task `gerarIndiceCatalogo` lê. O táxon vem do nome científico do JSON (a busca do iNaturalist aceita sinônimo), não só do levantamento: Begônia descreve a begônia-de-cera, não o gênero. Candidatas: fotos do táxon e de exemplares **cultivados no Brasil** (mostram a planta como é vendida: o pingo-de-ouro dourado, o filodendro no vaso). Licenças: CC0, BY, BY-SA, BY-NC, BY-NC-SA; ND fica fora, porque recortar pode contar como obra derivada. Autor e licença ficam no `fotos.json`.
- A Wikipedia foi descartada como fonte: gravuras antigas, uma bandeira e espécies trocadas (crisântemo com *Glebionis*, beterraba com acelga).
- Conferir o nome científico garante a botânica, não o reconhecimento: a foto padrão costuma ser flor ou fruto na natureza. Por isso a revisão é **da grade inteira**, à mão, em `dados/cache/fotos/revisao.html` (marcadas primeiro: gênero, cultivar, híbridos, sinônimo, nome que não bate). A página gera as linhas de `dados/fotos.csv` (`arquivo;inat:<id da foto>|SEM_FOTO;nota`); espécie sem linha ainda não foi revisada. Espécie nova no catálogo: rode `python dados/fotos.py` e revise as que aparecerem.
- **Compose 1.7.2 + `SharedTransitionLayout`**: pôr ou tirar um filho de um nó de tamanho fixo quando um estado assíncrono muda (a foto chegando) derruba o app com "Placement happened before lookahead", só em tempo de execução. `MiniaturaPlanta` desenha a foto com `drawWithContent` e mantém o mesmo `Text`; `Crossfade` também caía.
- Medido no emulador (a mesma densidade do Fold, 420 dpi), a rolagem com fotos ficou igual à de antes, dentro do ruído; a medida que vale, a do Fold, ainda não foi feita.

## Catálogo e busca (2026-09-25)

- O catálogo vai chegar a ~1000 espécies, geradas por outra frente do projeto (`dados/`: levantamento das plantas populares e `gerar_catalogo.py`), que grava direto em `app/src/main/assets/catalogo/`. **Não ponha arquivos temporários nessa pasta.** Para testar volume, use `-PcatalogoExtra=<pasta fora do repo>`.
- `dados/` vai para o git (menos `cache/`), porque `plantas-populares.json` entra no build: sem ele, o índice perde sinônimos, categoria e ranking. Espécies geradas e a lista andam juntas no mesmo commit (decisão do usuário em 2026-09-27, na v0.1.0).
- A task `gerarIndiceCatalogo` (em `app/build.gradle.kts`) gera `catalogo/indice.json` (lido como `ItemCatalogo`) nos assets gerados do build. Se `dados/plantas-populares.json` existir, junta `outros_nomes`, `categoria` e `posicao`, casando por `arquivo_catalogo`. O índice não vai para o git.
- `Catalogo.busca` lê só o índice, pré-carregado em segundo plano ao abrir o app. `Catalogo.especie(arquivo)` lê o JSON inteiro sob demanda (fora da main thread).
- `BuscaCatalogo` (motor) faz o autocomplete: normalização que mantém o tamanho do texto (a faixa destacada vale no nome original), começo de palavra em qualquer ordem, e níveis igual > nome começa > primeira palavra > palavra do meio > outro nome > científico > categoria (com plural: "orquídeas" acha Cattleya) > no meio da palavra > erro de digitação (só quando nada exato casou). Empates vão para a mais popular. As regras estão em `BuscaCatalogoTest`, com casos tirados dos nomes reais ("pitanga" → Pitangueira antes de Pitangatuba).

## Abertura (2026-09-25)

- Animação "semente" (`ui/Abertura.kt`, Compose): a semente cai, o caule sobe, abrem duas folhas e aparece "Jardim"; ~1,6 s, e um toque pula. O usuário escolheu essa entre três opções, sabendo que ela atrasa cada abertura a frio (a "Brotar", de 0,7 s, e a "Só a saída", sem espera, foram recusadas).
- Ela é uma camada por cima da navegação: o Hoje carrega por baixo enquanto a semente brota. `rememberSaveable` impede que ela volte ao dobrar o celular ou girar a tela.
- A abertura do sistema (Android 12+) fica só com o fundo do tema e um ícone transparente (`values-v31/themes.xml`), para emendar na semente. O `windowBackground` segue claro/escuro (`@color/fundo`); antes, a janela piscava branca antes do tema escuro.

## Transições de tela (2026-09-25)

- Container transform (`Modifier.container`, em `ui/Transicoes.kt`) do cartão da planta para a tela dela e do botão "Nova planta" para a busca; profundidade (eixo Z) no resto. O padrão do navigation-compose, crossfade de 700 ms, foi recusado.
- As duas pontas de um container usam a mesma chave (`chaveDoPlantio`, `CHAVE_NOVA_PLANTA`). Só vai para a camada de cima quem tem par (`isMatchFound`): sem isso, o botão e os outros cartões flutuavam sobre a tela que abria.
- O `SharedTransitionLayout` tem o fundo do tema: sem ele, a profundidade mostrava o fundo claro da janela entre uma tela e outra.
- Para conferir uma animação: `adb shell screenrecord`, depois `ffmpeg` (instalado pelo winget) extrai os quadros.

## Notificações e agendamento

- Cada aviso é um OneTimeWork único que, ao rodar, agenda o do dia seguinte com `APPEND_OR_REPLACE`: `REPLACE` cancelaria o worker que está rodando, e `KEEP` ignoraria o pedido. A abertura do app chama `garantirAgendado()` (`KEEP`), que reconstrói a corrente se ela quebrar. Não use PeriodicWorkRequest: ele escorrega o horário.
- O id da notificação é `hash(plantioId|chave)`: o aviso da noite substitui o da manhã da mesma pendência, e marcar no app cancela a notificação certa.
- `AcaoAvisoReceiver` usa `registrarSePendente`, que confere se a pendência ainda existe (sob um Mutex): um botão de notificação velha não duplica o histórico.
- Pendente conhecido: ao marcar uma pendência, o título do resumo do grupo ("4 tarefas atrasadas") não é recalculado. O contador do Samsung fica certo.

## Banco (Room)

- Três tabelas: `plantio` (com a cópia do JSON da espécie no dia do plantio), `registro_acao` e `planejado` (v3, as plantas de "Para plantar"). Datas em texto ISO.
- Enums (`StatusAcao`, `Local`, `FormaInicio`, `ItemPreparo` em `planejado.faltam`) são gravados pelo nome: renomear exige migração.
- Esquemas exportados em `app/schemas/`. Ao mudar uma entidade, suba a `version` e escreva a migração; não use `fallbackToDestructiveMigration`, porque apagaria o histórico do usuário.

## Comandos

- `./dev.sh test` (ou `./gradlew :motor:test`): testes do motor, em segundos e sem emulador. Rode depois de mexer no motor ou no catálogo.
- `./gradlew :app:testDebugUnitTest`: testes JVM do app (formatação).
- `./dev.sh run`: build **release**, instala e abre no aparelho conectado. `./dev.sh run-debug` faz o mesmo em debug: é o único em que `run-as com.jardim` lê o banco, mas é 3 a 4 vezes mais lento. Não meça desempenho em debug.
- Depois de mexer em serialização, Room, WorkManager ou nos receivers, confira os fluxos no release (Hoje, linha do tempo, "Testar agora"), porque o R8 só quebra essas coisas em tempo de execução.
- `./dev.sh release`: roda os testes, builda o release, cria a tag `vX.Y.Z` (lida do `versionName`) e publica a release no GitHub com o APK. Exige árvore limpa. Antes de cada release, suba `versionName` e `versionCode` em `app/build.gradle.kts`. O APK sai deste PC, nunca do CI, porque só a chave de debug daqui instala por cima do que está no celular.
- CI (`.github/workflows/testes.yml`): `:motor:test` e `:app:testDebugUnitTest` a cada push.
- `./dev.sh print nome`: print da tela ligada. O aparelho de teste é o celular do usuário, um **Galaxy Z Fold7 (SM-F966B, Android 16)** via USB. Ele tem duas telas, e `screencap` sem `-d` pode pegar a desligada.
- No Git Bash, `adb shell` com caminho do aparelho precisa de `MSYS_NO_PATHCONV=1` (o `dev.sh` já exporta).
- Para testar tocando na tela: `uiautomator dump` + `input tap` no centro do elemento pelo texto. A barra de notificações tem notificações pessoais de outros apps: não toque nem descreva nada lá, e peça ao usuário para tocar nos botões da notificação. Conceder permissão no aparelho também é com ele.
- O emulador (`money_hole_test`) **não sobe neste PC**: falta o driver de virtualização, que é configuração do sistema e fica com o usuário.

Versões iguais às do Trombadário e do fatura-app (AGP 8.5.2, Kotlin 1.9.24, KSP 1.9.24-1.0.20, Room 2.6.1, WorkManager 2.9.1 (a 2.10 exige compileSdk 35), DataStore 1.1.1, compilador Compose 1.5.14, compileSdk 34), porque já estão no cache desta máquina.

## Commits

Conventional commits em português com gitmoji, igual aos outros repos: `feat: ✨ ...`, `fix: 🐛 ...`, `chore: 🔧 ...`, `test: ✅ ...`, `docs: 📝 ...`.

Push: autorizado sempre, do branch inteiro, incluindo commits de outras sessões que trabalham neste repositório (autorização do usuário em 2026-09-25). Commite só os seus arquivos; os das outras sessões elas mesmas commitam.
