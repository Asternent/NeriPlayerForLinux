#pragma once

#include <QObject>
#include <QRect>

class QWindow;

/**
 * Wayland 下的「悬浮窗口」定位与置顶层。
 *
 * 普通 xdg-toplevel 窗口在 Wayland 上位置完全由合成器决定：客户端读写 x/y 无效，
 * WindowStaysOnTopHint 也可能被合成器忽略。这就是悬浮歌词「关掉重开又回到中间」
 * 和「不置顶」的根因。
 *
 * 正确做法是 wlr-layer-shell 协议（KDE 通过 LayerShellQt 提供）：把窗口放进
 * overlay 层，并用锚点 + 边距精确摆放。这样位置由我们自己声明、层级固定在所有
 * 普通窗口之上，且不会进入任务栏。
 *
 * 非 Wayland 平台该协议不可用，此时回退到普通窗口 + 手动 setPosition。
 */
class LayerShellHelper : public QObject
{
    Q_OBJECT
public:
    explicit LayerShellHelper(QObject *parent = nullptr);

    /** 当前平台是否支持 layer-shell（Wayland）。 */
    Q_INVOKABLE bool available() const;

    /**
     * 把窗口配置成 overlay 层的浮层，并按屏幕比例定位。
     *
     * @param window       目标窗口
     * @param ratioX/ratioY 0..1，左上角在屏幕可用区域中的归一化位置
     * @param width/height  期望尺寸（逻辑像素）；宽 <= 0 表示按内容自适应
     * @return 是否成功启用 layer-shell（false 表示应回退到普通窗口方式）
     */
    Q_INVOKABLE bool attach(QObject *window, qreal ratioX, qreal ratioY,
                            int width, int height);

    /** 更新已附着窗口的位置；不做任何事表示支持失败。 */
    Q_INVOKABLE bool setPositionRatio(QObject *window, qreal ratioX, qreal ratioY);

    /**
     * 直接按屏幕像素设置左上角位置（拖动过程中用）。
     *
     * 比 setPositionRatio 少一层「像素↔比例」换算，拖动时不会因取整来回抖动。
     */
    Q_INVOKABLE bool setPositionMargins(QObject *window, int x, int y);

    /** 更新已附着窗口的期望尺寸。 */
    Q_INVOKABLE void setDesiredSize(QObject *window, int width, int height);

    /**
     * 把窗口配置成「全屏透明覆盖层」，用于拖动时的位置预览。
     *
     * 全屏且锚定四边 → 窗口尺寸固定不动，只有内部绘制内容变化，
     * 因此跟随鼠标不会触发合成器重排（不会闪）。
     */
    Q_INVOKABLE bool attachOverlayFullscreen(QObject *window);

    /** 取消 layer-shell 附着（关闭窗口前调用，避免残留 surface 角色）。 */
    Q_INVOKABLE void detach(QObject *window);
};
