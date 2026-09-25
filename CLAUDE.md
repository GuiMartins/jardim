# Jardim

App Android **pessoal** (APK instalado direto, sem Play Store) de acompanhamento de plantas. Construído em etapas: a cada etapa, propor alternativas, perguntar o que for decisão do usuário e só então implementar. O roteiro está no README.

## Estrutura

- `motor/`: Kotlin puro (JVM), sem Android. Leitura e validação do JSON da planta (`leitura/`), modelo (`modelo/`) e cálculo da agenda (`agenda/`). Toda regra de "o que fazer hoje" mora aqui, com teste.
- `app/`: Android, Compose. Catálogo de espécies em `app/src/main/assets/catalogo/*.json`.

## JSON da planta

O formato é o da skill `planta-cultivo-json` (`~/.claude/skills/planta-cultivo-json/SKILL.md`). `ValidadorCultivo` é o port do `scripts/validar.py` da skill, com as mesmas mensagens: mudou uma regra lá, muda aqui também. O teste `todo JSON do catálogo do app é válido` valida todo o catálogo a cada `:motor:test`.

## Regras de produto (decididas na etapa 1, em 2026-09-24)

- Agenda calculada sob demanda (`Cronograma`). O banco guarda só o histórico (`RegistroAcao`), nunca ocorrências futuras.
- Tarefa recorrente atrasada não acumula: uma pendência só, com o atraso crescendo. A próxima vence `frequencia_dias` depois do dia em que foi feita de verdade (pular conta como feita). Se a janela fecha, ela some.
- Alerta único fica pendente até ser feito. Evento sazonal vale pelo mês (só se a planta já existia no dia 1 do mês).
- Notificação: um resumo diário num horário escolhido pelo usuário, mais um lembrete à noite para tarefas críticas atrasadas.
- Com 7 dias de atraso, o app pergunta se a planta ainda está viva.
- Catálogo embutido em `assets/`. Importar JSON e baixar de servidor ficam para depois.

## Comandos

- `./gradlew :motor:test`: testes do motor (segundos, sem emulador). Rode depois de mexer no motor ou no catálogo.
- `./gradlew assembleDebug`: APK debug.

Versões iguais às do Trombadário (AGP 8.5.2, Kotlin 1.9.24, compilador Compose 1.5.14, compileSdk 34), porque já estão no cache desta máquina.

## Commits

Conventional commits em português com gitmoji, igual aos outros repos: `feat: ✨ ...`, `fix: 🐛 ...`, `chore: 🔧 ...`, `test: ✅ ...`, `docs: 📝 ...`.
