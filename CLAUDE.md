# Jardim

App Android **pessoal** (APK instalado direto, sem Play Store) de acompanhamento de plantas. Construído em etapas: a cada etapa, propor alternativas, perguntar o que for decisão do usuário e só então implementar. O roteiro está no README. Leve ao usuário só as decisões que mudam o produto; quando uma feature virar um poço de casos-limite, ofereça cortá-la (foi assim com o "ainda está viva?", removido na etapa 3).

## Estrutura

- `motor/`: Kotlin puro (JVM), sem Android. Leitura e validação do JSON da planta (`leitura/`), modelo (`modelo/`) e cálculo da agenda (`agenda/`). Toda regra de "o que fazer hoje" mora aqui, com teste.
- `app/`: Android, Compose. `dados/` (Room, catálogo, repositório), `ui/` (telas e ViewModels), catálogo de espécies em `app/src/main/assets/catalogo/*.json`. Injeção de dependência manual em `JardimApp`.

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

## Banco (Room)

- Duas tabelas: `plantio` (com a cópia do JSON da espécie no dia do plantio) e `registro_acao`. Datas em texto ISO.
- Enums (`StatusAcao`) são gravados pelo nome: renomear exige migração.
- Esquemas exportados em `app/schemas/`. Ao mudar uma entidade, suba a `version` e escreva a migração; não use `fallbackToDestructiveMigration`, porque apagaria o histórico do usuário.

## Comandos

- `./dev.sh test` (ou `./gradlew :motor:test`): testes do motor, em segundos e sem emulador. Rode depois de mexer no motor ou no catálogo.
- `./gradlew :app:testDebugUnitTest`: testes JVM do app (formatação).
- `./dev.sh run`: build, instala e abre no aparelho ou emulador conectado.
- O emulador (`money_hole_test`) **não sobe neste PC**: falta o driver de virtualização (Android Emulator hypervisor driver ou Windows Hypervisor Platform). Ativar é configuração do sistema, então fica com o usuário. Até lá, testar no celular via adb.

Versões iguais às do Trombadário e do fatura-app (AGP 8.5.2, Kotlin 1.9.24, KSP 1.9.24-1.0.20, Room 2.6.1, compilador Compose 1.5.14, compileSdk 34), porque já estão no cache desta máquina.

## Commits

Conventional commits em português com gitmoji, igual aos outros repos: `feat: ✨ ...`, `fix: 🐛 ...`, `chore: 🔧 ...`, `test: ✅ ...`, `docs: 📝 ...`.
