# Jardim

App Android **pessoal** (APK instalado direto, sem Play Store) de acompanhamento de plantas. Construído em etapas: a cada etapa, propor alternativas, perguntar o que for decisão do usuário e só então implementar. O roteiro está no README. Leve ao usuário só as decisões que mudam o produto; quando uma feature virar um poço de casos-limite, ofereça cortá-la (foi assim com o "ainda está viva?", removido na etapa 3).

## Estrutura

- `motor/`: Kotlin puro (JVM), sem Android. Leitura e validação do JSON da planta (`leitura/`), modelo (`modelo/`) e cálculo da agenda (`agenda/`). Toda regra de "o que fazer hoje" mora aqui, com teste.
- `app/`: Android, Compose. `dados/` (Room, catálogo, repositório), `avisos/` (notificações e agendamento), `ui/` (telas e ViewModels), catálogo de espécies em `app/src/main/assets/catalogo/*.json`. Injeção de dependência manual em `JardimApp`.

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
- `./dev.sh run`: build, instala e abre no aparelho conectado.
- `./dev.sh print nome`: print da tela ligada. O aparelho de teste é o celular do usuário, um **Galaxy Z Fold7 (SM-F966B, Android 16)** via USB. Ele tem duas telas, e `screencap` sem `-d` pode pegar a desligada.
- No Git Bash, `adb shell` com caminho do aparelho precisa de `MSYS_NO_PATHCONV=1` (o `dev.sh` já exporta).
- Para testar tocando na tela: `uiautomator dump` + `input tap` no centro do elemento pelo texto. A barra de notificações tem notificações pessoais de outros apps: não toque nem descreva nada lá, e peça ao usuário para tocar nos botões da notificação. Conceder permissão no aparelho também é com ele.
- O emulador (`money_hole_test`) **não sobe neste PC**: falta o driver de virtualização, que é configuração do sistema e fica com o usuário.

Versões iguais às do Trombadário e do fatura-app (AGP 8.5.2, Kotlin 1.9.24, KSP 1.9.24-1.0.20, Room 2.6.1, WorkManager 2.9.1 (a 2.10 exige compileSdk 35), DataStore 1.1.1, compilador Compose 1.5.14, compileSdk 34), porque já estão no cache desta máquina.

## Commits

Conventional commits em português com gitmoji, igual aos outros repos: `feat: ✨ ...`, `fix: 🐛 ...`, `chore: 🔧 ...`, `test: ✅ ...`, `docs: 📝 ...`.
