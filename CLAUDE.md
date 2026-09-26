# Jardim

App Android **pessoal** (APK instalado direto, sem Play Store) de acompanhamento de plantas. Construído em etapas: a cada etapa, propor alternativas, perguntar o que for decisão do usuário e só então implementar. O roteiro está no README. Leve ao usuário só as decisões que mudam o produto; quando uma feature virar um poço de casos-limite, ofereça cortá-la (foi assim com o "ainda está viva?", removido na etapa 3).

## Estrutura

- `motor/`: Kotlin puro (JVM), sem Android. Leitura e validação do JSON da planta (`leitura/`), modelo (`modelo/`) e cálculo da agenda (`agenda/`). Toda regra de "o que fazer hoje" mora aqui, com teste.
- `app/`: Android, Compose. `dados/` (Room, catálogo, repositório), `avisos/` (notificações e agendamento), `ui/` (telas e ViewModels), catálogo de espécies em `app/src/main/assets/catalogo/*.json`. Injeção de dependência manual em `JardimApp`.
- `dados/`: fora do build. `levantamento.py` (Python, só biblioteca padrão) gera `plantas-populares.json`, as 1000 plantas cultivadas mais populares do Brasil, que é a fila de espécies para o catálogo. Correções à mão vão em `curadoria.csv`, nunca no JSON gerado. As respostas das APIs ficam em `dados/cache/` (fora do git). Sem contato no User-Agent, a Wikipedia limita a 10 requisições por minuto: rodar do zero leva cerca de 1 hora; com o cache, segundos.

## JSON da planta

O formato é o da skill `planta-cultivo-json` (`~/.claude/skills/planta-cultivo-json/SKILL.md`). `ValidadorCultivo` é o port do `scripts/validar.py` da skill, com as mesmas mensagens: mudou uma regra lá, muda aqui também. O teste `todo JSON do catálogo do app é válido` valida todo o catálogo a cada `:motor:test`.

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

## Catálogo e busca (2026-09-25)

- O catálogo vai chegar a ~1000 espécies, geradas por outra frente do projeto (`dados/`: levantamento das plantas populares e `gerar_catalogo.py`), que grava direto em `app/src/main/assets/catalogo/`. **Não ponha arquivos temporários nessa pasta nem commite `dados/`**: essa frente é de outra sessão. Para testar volume, use `-PcatalogoExtra=<pasta fora do repo>`.
- A task `gerarIndiceCatalogo` (em `app/build.gradle.kts`) gera `catalogo/indice.json` (lido como `ItemCatalogo`) nos assets gerados do build. Se `dados/plantas-populares.json` existir, junta `outros_nomes`, `categoria` e `posicao`, casando por `arquivo_catalogo`. O índice não vai para o git.
- `Catalogo.busca` lê só o índice, pré-carregado em segundo plano ao abrir o app. `Catalogo.especie(arquivo)` lê o JSON inteiro sob demanda (fora da main thread).
- `BuscaCatalogo` (motor) faz o autocomplete: normalização que mantém o tamanho do texto (a faixa destacada vale no nome original), começo de palavra em qualquer ordem, e níveis igual > nome começa > primeira palavra > palavra do meio > outro nome > científico > categoria (com plural: "orquídeas" acha Cattleya) > no meio da palavra > erro de digitação (só quando nada exato casou). Empates vão para a mais popular. As regras estão em `BuscaCatalogoTest`, com casos tirados dos nomes reais ("pitanga" → Pitangueira antes de Pitangatuba).

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

- Duas tabelas: `plantio` (com a cópia do JSON da espécie no dia do plantio) e `registro_acao`. Datas em texto ISO.
- Enums (`StatusAcao`) são gravados pelo nome: renomear exige migração.
- Esquemas exportados em `app/schemas/`. Ao mudar uma entidade, suba a `version` e escreva a migração; não use `fallbackToDestructiveMigration`, porque apagaria o histórico do usuário.

## Comandos

- `./dev.sh test` (ou `./gradlew :motor:test`): testes do motor, em segundos e sem emulador. Rode depois de mexer no motor ou no catálogo.
- `./gradlew :app:testDebugUnitTest`: testes JVM do app (formatação).
- `./dev.sh run`: build **release**, instala e abre no aparelho conectado. `./dev.sh run-debug` faz o mesmo em debug: é o único em que `run-as com.jardim` lê o banco, mas é 3 a 4 vezes mais lento. Não meça desempenho em debug.
- Depois de mexer em serialização, Room, WorkManager ou nos receivers, confira os fluxos no release (Hoje, linha do tempo, "Testar agora"), porque o R8 só quebra essas coisas em tempo de execução.
- `./dev.sh print nome`: print da tela ligada. O aparelho de teste é o celular do usuário, um **Galaxy Z Fold7 (SM-F966B, Android 16)** via USB. Ele tem duas telas, e `screencap` sem `-d` pode pegar a desligada.
- No Git Bash, `adb shell` com caminho do aparelho precisa de `MSYS_NO_PATHCONV=1` (o `dev.sh` já exporta).
- Para testar tocando na tela: `uiautomator dump` + `input tap` no centro do elemento pelo texto. A barra de notificações tem notificações pessoais de outros apps: não toque nem descreva nada lá, e peça ao usuário para tocar nos botões da notificação. Conceder permissão no aparelho também é com ele.
- O emulador (`money_hole_test`) **não sobe neste PC**: falta o driver de virtualização, que é configuração do sistema e fica com o usuário.

Versões iguais às do Trombadário e do fatura-app (AGP 8.5.2, Kotlin 1.9.24, KSP 1.9.24-1.0.20, Room 2.6.1, WorkManager 2.9.1 (a 2.10 exige compileSdk 35), DataStore 1.1.1, compilador Compose 1.5.14, compileSdk 34), porque já estão no cache desta máquina.

## Commits

Conventional commits em português com gitmoji, igual aos outros repos: `feat: ✨ ...`, `fix: 🐛 ...`, `chore: 🔧 ...`, `test: ✅ ...`, `docs: 📝 ...`.

Push: autorizado sempre, do branch inteiro, incluindo commits de outras sessões que trabalham neste repositório (autorização do usuário em 2026-09-25). Commite só os seus arquivos; os das outras sessões elas mesmas commitam.
