#!/usr/bin/env bash
# Qt 前端开发辅助脚本。
#
#   tools/dev.sh backend            构建并启动无头后端（测试用 XDG 目录，默认 /tmp/neri-dev）
#   tools/dev.sh stop               停止测试后端
#   tools/dev.sh build              编译 C++ 程序
#   tools/dev.sh screenshot out.png [--page home] [--wait 2500] [--size 1180x820]
#   tools/dev.sh app                在真实桌面上启动应用（会顺带拉起后端）
#   tools/dev.sh clean              删除测试数据目录
#
# 所有命令都在隔离的 XDG 目录里跑，不会碰用户的真实播放数据。
set -euo pipefail

QT_DIR="$(cd "$(dirname "$0")/.." && pwd)"      # desktop/qt
DESKTOP_DIR="$(cd "$QT_DIR/.." && pwd)"         # desktop
REPO_DIR="$(cd "$DESKTOP_DIR/.." && pwd)"       # repo root
TEST_ROOT="${NERI_TEST_ROOT:-/tmp/neri-dev}"
export XDG_RUNTIME_DIR="$TEST_ROOT/runtime"
export XDG_DATA_HOME="$TEST_ROOT/data"
export XDG_CONFIG_HOME="$TEST_ROOT/config"
export XDG_CACHE_HOME="$TEST_ROOT/cache"
SOCKET="$XDG_RUNTIME_DIR/neriplayer/core.sock"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
JAVA_BIN="$JAVA_HOME/bin/java"
BACKEND_JAR="$DESKTOP_DIR/build/libs/neriplayer-core.jar"
BIN="$QT_DIR/build/neriplayer"

ensure_dirs() {
    mkdir -p "$XDG_RUNTIME_DIR" "$XDG_DATA_HOME" "$XDG_CONFIG_HOME" "$XDG_CACHE_HOME"
}

backend_alive() {
    [ -S "$SOCKET" ] || return 1
    python3 - "$SOCKET" <<'PY' 2>/dev/null
import socket, sys
s = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
s.settimeout(0.4)
s.connect(sys.argv[1])
s.close()
PY
}

build_backend() {
    ( cd "$DESKTOP_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew -q backendJar --offline )
}

cmd_backend() {
    ensure_dirs
    if backend_alive; then
        echo "backend already running at $SOCKET"
        return
    fi
    if [ ! -f "$BACKEND_JAR" ]; then
        echo "building backend jar ..."
        build_backend
    fi
    nohup "$JAVA_BIN" -Dfile.encoding=UTF-8 -Xmx512m -XX:+UseG1GC \
        -jar "$BACKEND_JAR" "${@:-}" >"$TEST_ROOT/backend.log" 2>&1 &
    echo $! >"$TEST_ROOT/backend.pid"
    for _ in $(seq 1 100); do
        if [ -S "$SOCKET" ]; then
            echo "backend started (pid $(cat "$TEST_ROOT/backend.pid")), socket: $SOCKET"
            return
        fi
        sleep 0.2
    done
    echo "backend failed to start, see $TEST_ROOT/backend.log" >&2
    tail -20 "$TEST_ROOT/backend.log" >&2 || true
    exit 1
}

cmd_stop() {
    if [ -f "$TEST_ROOT/backend.pid" ]; then
        kill "$(cat "$TEST_ROOT/backend.pid")" 2>/dev/null || true
        rm -f "$TEST_ROOT/backend.pid"
    fi
    pkill -f "neriplayer-core.jar" 2>/dev/null || true
    rm -f "$SOCKET"
    echo "backend stopped"
}

cmd_build() {
    cmake -S "$QT_DIR" -B "$QT_DIR/build" -G Ninja -DCMAKE_BUILD_TYPE=Debug >/dev/null
    cmake --build "$QT_DIR/build" -j"$(nproc)"
}

cmd_screenshot() {
    local out="${1:-/tmp/neri-screenshot.png}"
    shift || true
    ensure_dirs
    if ! backend_alive; then
        cmd_backend
    fi
    if [ ! -x "$BIN" ]; then
        cmd_build
    fi
    QT_QPA_PLATFORM=offscreen QT_QUICK_BACKEND=software \
        timeout 90 "$BIN" --qml-dir "$QT_DIR/qml" --screenshot "$out" "$@"
    echo "screenshot: $out"
}

cmd_app() {
    ensure_dirs
    export NERIPLAYER_BACKEND_JAR="$BACKEND_JAR"
    export NERIPLAYER_JAVA="$JAVA_BIN"
    if [ ! -f "$BACKEND_JAR" ]; then
        build_backend
    fi
    if [ ! -x "$BIN" ]; then
        cmd_build
    fi
    "$BIN" --qml-dir "$QT_DIR/qml" "$@"
}

cmd_clean() {
    cmd_stop
    rm -rf "$TEST_ROOT"
    echo "cleaned $TEST_ROOT"
}

action="${1:-}"
shift || true
case "$action" in
    backend) cmd_backend "$@" ;;
    stop) cmd_stop ;;
    build) cmd_build ;;
    screenshot) cmd_screenshot "$@" ;;
    app) cmd_app "$@" ;;
    clean) cmd_clean ;;
    *)
        sed -n '2,12p' "$0"
        exit 1
        ;;
esac
