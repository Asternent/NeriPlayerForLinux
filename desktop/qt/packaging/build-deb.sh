#!/usr/bin/env bash
# 构建 NeriPlayer Linux 桌面版（Qt 前端 + jlink 精简 JRE 后端）的 .deb 安装包。
#
# 用法：
#   desktop/qt/packaging/build-deb.sh [版本号]
#
# 产物：desktop/qt/build/deb/neriplayer_<版本>_amd64.deb
#
# 依赖：cmake / ninja / qt6 开发包 / JDK 17（JAVA_HOME 或 /usr/lib/jvm/java-17-openjdk-amd64）
set -euo pipefail

PKG_NAME="neriplayer"
VERSION="${1:-2.0.0}"
ARCH="amd64"
QT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
DESKTOP_DIR="$(cd "$QT_DIR/.." && pwd)"
REPO_DIR="$(cd "$DESKTOP_DIR/.." && pwd)"
BUILD_DIR="$QT_DIR/build-deb"
STAGE="$BUILD_DIR/stage"
JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
JAVA_BIN="$JAVA_HOME/bin/java"
JLINK="$JAVA_HOME/bin/jlink"
BACKEND_JAR="$DESKTOP_DIR/build/libs/neriplayer-core.jar"

echo "==> 清理 $STAGE"
rm -rf "$STAGE"
mkdir -p "$STAGE/DEBIAN"
mkdir -p "$STAGE/usr/bin"
mkdir -p "$STAGE/usr/lib/neriplayer"
mkdir -p "$STAGE/usr/share/neriplayer"
mkdir -p "$STAGE/usr/share/applications"
mkdir -p "$STAGE/usr/share/icons/hicolor/128x128/apps"
mkdir -p "$STAGE/usr/share/icons/hicolor/256x256/apps"
mkdir -p "$STAGE/usr/share/doc/$PKG_NAME"

echo "==> 构建 Qt 前端（Release）"
cmake -S "$QT_DIR" -B "$BUILD_DIR/cmake" -G Ninja -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr >/dev/null
cmake --build "$BUILD_DIR/cmake" -j"$(nproc)"
DESTDIR="$STAGE" cmake --install "$BUILD_DIR/cmake" >/dev/null

echo "==> 构建后端 jar"
( cd "$DESKTOP_DIR" && JAVA_HOME="$JAVA_HOME" ./gradlew -q backendJar --offline )
cp "$BACKEND_JAR" "$STAGE/usr/lib/neriplayer/neriplayer-core.jar"

echo "==> jlink 精简运行时"
rm -rf "$STAGE/usr/lib/neriplayer/runtime"
"$JLINK" \
    --add-modules java.base,java.logging,java.net.http,java.desktop,java.naming,java.management,java.instrument,jdk.crypto.ec,jdk.security.auth,jdk.unsupported,jdk.zipfs \
    --strip-debug --no-header-files --no-man-pages --compress=2 \
    --output "$STAGE/usr/lib/neriplayer/runtime"

echo "==> 安装启动脚本 / desktop / 图标 / 许可证"
cat > "$STAGE/usr/bin/neriplayer" <<'LAUNCHER'
#!/bin/sh
# Qt 前端会自动在 ../lib/neriplayer 下找 neriplayer-core.jar 与自带 JRE
exec /usr/lib/neriplayer/neriplayer "$@"
LAUNCHER
chmod 755 "$STAGE/usr/bin/neriplayer"

install -m 644 "$QT_DIR/packaging/neriplayer.desktop" "$STAGE/usr/share/applications/neriplayer.desktop"
install -m 644 "$DESKTOP_DIR/packaging/neriplayer-128.png" "$STAGE/usr/share/icons/hicolor/128x128/apps/neriplayer.png" 2>/dev/null \
    || install -m 644 "$DESKTOP_DIR/packaging/neriplayer.png" "$STAGE/usr/share/icons/hicolor/128x128/apps/neriplayer.png"
install -m 644 "$DESKTOP_DIR/packaging/neriplayer-256.png" "$STAGE/usr/share/icons/hicolor/256x256/apps/neriplayer.png" 2>/dev/null \
    || install -m 644 "$DESKTOP_DIR/packaging/neriplayer.png" "$STAGE/usr/share/icons/hicolor/256x256/apps/neriplayer.png"
cp "$DESKTOP_DIR/packaging/LICENSE" "$STAGE/usr/share/doc/$PKG_NAME/copyright"

echo "==> 写 control"
INSTALLED_SIZE=$(du -sk "$STAGE" | cut -f1)
cat > "$STAGE/DEBIAN/control" <<CONTROL
Package: $PKG_NAME
Version: $VERSION
Section: sound
Priority: optional
Architecture: $ARCH
Installed-Size: $INSTALLED_SIZE
Maintainer: NeriPlayer Desktop <neriplayer@localhost>
Description: NeriPlayer 音理音理 — 多源在线 / 本地音乐播放器（Qt 原生桌面版）
 支持网易云 / 哔哩哔哩 / 本地媒体库、歌词、悬浮歌词、GitHub 跨设备同步、
 托盘常驻与 MPRIS 系统媒体控制。
Depends: libqt6core6 | libqt6core6t64, libqt6gui6 | libqt6gui6t64, libqt6qml6 | libqt6qml6t64,
 liblayershellqtinterface6, layer-shell-qt,
 libqt6quick6 | libqt6quick6t64, libqt6network6 | libqt6network6t64, libqt6svg6, libqt6dbus6,
 qml6-module-qtquick, qml6-module-qtquick-window, qml6-module-qtquick-controls,
 qml6-module-qtquick-templates, qml6-module-qtquick-layouts, qml6-module-qtquick-dialogs,
 qml6-module-qtqml, qml6-module-qtqml-models, qml6-module-qtcore, qml6-module-qtnetwork,
 python3, python3-gi, gir1.2-glib-2.0, gir1.2-gtk-3.0, fonts-noto-cjk
Recommends: ffmpeg, yt-dlp, xdg-utils
Homepage: https://github.com/Asternent/NeriPlayerForLinux
CONTROL

cat > "$STAGE/DEBIAN/postinst" <<'POSTINST'
#!/bin/sh
set -e

# 只做 freedesktop 标准注册：desktop 文件已安装在 /usr/share/applications，
# KDE/GTK 会通过目录监视自动发现，不需要（也不应该）手动调 kbuildsycoca6。
# 注意：kbuildsycoca6 --noincremental 在 postinst 的非交互环境里会生成残缺缓存，
# 覆盖掉用户完整的菜单缓存，导致开始菜单分类丢失 —— 不要加回来。
case "$1" in
    configure)
        if command -v update-desktop-database >/dev/null 2>&1; then
            update-desktop-database -q /usr/share/applications || true
        fi
        if command -v gtk-update-icon-cache >/dev/null 2>&1; then
            gtk-update-icon-cache -q -t -f /usr/share/icons/hicolor || true
        fi

        # 清理 kmenuedit 里本应用的历史残留条目。
        #
        # 早期版本的 desktop 文件叫 neriplayer-NeriPlayer.desktop，后来改名为
        # neriplayer.desktop。用户菜单里因此沉积了两类残留：
        #   - <Exclude><Filename>neriplayer.desktop</Filename>  → 分类菜单里完全不显示
        #   - <Include><Filename>neriplayer-NeriPlayer.desktop</Filename> → 指向已不存在的文件
        # 表现为「开始菜单分类里没有，但 krunner 搜索能搜到」。这里把本应用相关行从菜单
        # 自定义文件里剔除，让它回到默认归类；只动本应用的行，不碰其它应用配置。
        for menu_file in /home/*/.config/menus/applications-kmenuedit.menu; do
            [ -f "$menu_file" ] || continue
            grep -q 'neriplayer' "$menu_file" 2>/dev/null || continue

            tmp="$(mktemp)" || continue
            if grep -v -E '<Filename>neriplayer(-NeriPlayer)?\.desktop</Filename>' \
                    "$menu_file" > "$tmp"; then
                # 保持原属主/权限，避免菜单文件变成 root 拥有后用户自己改不了
                owner="$(stat -c '%u:%g' "$menu_file" 2>/dev/null || echo '')"
                mode="$(stat -c '%a' "$menu_file" 2>/dev/null || echo '')"
                cat "$tmp" > "$menu_file" || true
                [ -n "$owner" ] && chown "$owner" "$menu_file" 2>/dev/null || true
                [ -n "$mode" ] && chmod "$mode" "$menu_file" 2>/dev/null || true
                # 通知 KDE 重建菜单缓存，让改动立即生效（没有可用工具时静默跳过）
                menu_user="$(stat -c '%U' "$menu_file" 2>/dev/null || echo '')"
                if [ -n "$menu_user" ] && [ "$menu_user" != "root" ]; then
                    if command -v runuser >/dev/null 2>&1; then
                        runuser -u "$menu_user" -- kbuildsycoca6 >/dev/null 2>&1 || true
                    elif command -v su >/dev/null 2>&1; then
                        su -s /bin/sh "$menu_user" -c kbuildsycoca6 >/dev/null 2>&1 || true
                    fi
                fi
            fi
            rm -f "$tmp"
        done
        ;;
esac

exit 0
POSTINST
chmod 755 "$STAGE/DEBIAN/postinst"

# 卸载时同样只刷新 MIME 数据库，不碰用户的 KDE 菜单缓存
cat > "$STAGE/DEBIAN/postrm" <<'POSTRM'
#!/bin/sh
set -e
case "$1" in
    remove|upgrade|deconfigure)
        if command -v update-desktop-database >/dev/null 2>&1; then
            update-desktop-database -q /usr/share/applications || true
        fi
        ;;
esac
exit 0
POSTRM
chmod 755 "$STAGE/DEBIAN/postrm"

echo "==> 打包 .deb"
mkdir -p "$BUILD_DIR/out"
DEB="$BUILD_DIR/out/${PKG_NAME}_${VERSION}_${ARCH}.deb"
dpkg-deb --root-owner-group -Zxz --build "$STAGE" "$DEB" >/dev/null
echo "完成：$DEB"
ls -lh "$DEB"
