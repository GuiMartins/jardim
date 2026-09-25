#!/usr/bin/env bash
# Atalhos de build/deploy do app, tipo um Makefile (não tem `make` no Git Bash
# deste PC). Uso: ./dev.sh <comando>
#
# Comandos:
#   emulator   abre o emulador em background
#   test       roda os testes do motor
#   build      builda o APK release (R8), o que vai para o celular
#   install    instala o APK release no dispositivo conectado
#   start      abre o app no dispositivo conectado
#   run        build + install + start, tudo de uma vez (o mais usado)
#   run-debug  o mesmo com o APK debug: dá para ler o banco com run-as e usar
#              as ferramentas do Compose, mas é 3 a 4 vezes mais lento
#   logs       segue o logcat do processo do app (precisa estar rodando)
#   print X    salva um print da tela ligada em X.png (o Fold tem duas telas)
set -euo pipefail

# Sem isso o Git Bash reescreve caminhos do aparelho (/sdcard -> C:/Program Files/Git/sdcard).
export MSYS_NO_PATHCONV=1

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

export JAVA_HOME="${JAVA_HOME:-/c/Program Files/Microsoft/jdk-17.0.19.10-hotspot}"
ANDROID_HOME="${ANDROID_HOME:-/c/Users/guilh/AndroidSdk}"

ADB="$ANDROID_HOME/platform-tools/adb.exe"
EMULATOR="$ANDROID_HOME/emulator/emulator.exe"
# Reusa o AVD dos outros apps: o nome é só do dispositivo do emulador.
AVD_NOME="${AVD_NOME:-money_hole_test}"
PACOTE="com.jardim"
ACTIVITY="$PACOTE/.MainActivity"
APK="$SCRIPT_DIR/app/build/outputs/apk/release/app-release.apk"
APK_DEBUG="$SCRIPT_DIR/app/build/outputs/apk/debug/app-debug.apk"

comando="${1:-run}"

case "$comando" in
    emulator)
        echo "Abrindo o emulador $AVD_NOME (a janela demora uns 30s-1min pra subir)..."
        "$EMULATOR" -avd "$AVD_NOME" &
        disown
        ;;
    test)
        (cd "$SCRIPT_DIR" && ./gradlew :motor:test)
        ;;
    build)
        (cd "$SCRIPT_DIR" && ./gradlew assembleRelease)
        ;;
    install)
        "$ADB" install -r "$APK"
        ;;
    start)
        "$ADB" shell am start -n "$ACTIVITY"
        ;;
    run)
        (cd "$SCRIPT_DIR" && ./gradlew assembleRelease)
        "$ADB" install -r "$APK"
        "$ADB" shell am start -n "$ACTIVITY"
        ;;
    run-debug)
        (cd "$SCRIPT_DIR" && ./gradlew assembleDebug)
        "$ADB" install -r "$APK_DEBUG"
        "$ADB" shell am start -n "$ACTIVITY"
        ;;
    logs)
        pid=$("$ADB" shell pidof -s "$PACOTE")
        echo "Seguindo logcat do PID $pid (Ctrl+C pra sair)..."
        "$ADB" logcat --pid="$pid"
        ;;
    print)
        # Celular dobrável: screencap sem -d pega uma tela qualquer (às vezes a
        # desligada). Escolhe a que está com mState=ON.
        tela=$("$ADB" shell dumpsys display | awk '/uniqueId="local:/{match($0,/local:[0-9]+/); id=substr($0,RSTART+6,RLENGTH-6)} /mState=ON/{print id; exit}')
        "$ADB" exec-out screencap -p -d "$tela" > "${2:-print}.png"
        echo "salvo em ${2:-print}.png"
        ;;
    *)
        echo "Uso: ./dev.sh [emulator|test|build|install|start|run|run-debug|logs|print]"
        exit 1
        ;;
esac
