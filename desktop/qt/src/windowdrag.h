#pragma once

#include <QObject>
#include <QPoint>

class QQuickWindow;

/**
 * 给无边框窗口（悬浮歌词 / 托盘控制面板）提供跨平台的鼠标拖动。
 *
 * Wayland 下窗口位置由合成器决定，直接写 QWindow::x/y 不生效（KWin 会忽略）；
 * 必须用 QWindow::startSystemMove() 让合成器接管拖动。X11/XWayland 下则保留
 * 手动 setPosition 的老路径，避免 startSystemMove 在部分 WM 上没有重力效果。
 */
class WindowDrag : public QObject
{
    Q_OBJECT
public:
    explicit WindowDrag(QObject *parent = nullptr);

    /** 在目标窗口上开始系统级拖动；应在一个鼠标 press 事件里调用。 */
    Q_INVOKABLE void startMove(QObject *window);

    /** 当前平台是否使用合成器拖动（Wayland）。 */
    Q_INVOKABLE bool usesSystemMove() const;

    /**
     * 让目标窗口脱离父窗口独立存在。
     *
     * 在 ApplicationWindow 里声明的子 Window 默认带 transient parent，主窗口隐藏
     * （最小化到托盘）时会被连同隐藏，导致「主窗口不可见时托盘面板弹不出来」。
     * 这里直接清掉 transient parent 与 QObject 父子关系，使它成为真正独立的顶层窗口。
     * 返回是否成功脱离。
     */
    Q_INVOKABLE bool detachFromParent(QObject *window);

    /**
     * 窗口隐藏（最小化到托盘）时调用：把「需要保持可见」的窗口重新独立出来。
     *
     * QML 的 QQuickWindowQmlImpl 会在初始化/可见性变化时自动重设 transient parent，
     * 所以光在创建时脱离一次不够——每次父窗口即将隐藏前都要再摘一次。
     */
    Q_INVOKABLE void detachChildWindows(QObject *parentWindow);

    /**
     * 让某个窗口在「父窗口不可见」时依然能显示。
     *
     * 在父窗口 visibleChanged 之后调用：若父窗口已不可见，就断开 transient 关系并重新 show，
     * 抵消 QQuickWindowQmlImpl 自动重设 transient parent 带来的连带隐藏。
     */
    Q_INVOKABLE void keepVisible(QObject *window);

    /**
     * 把窗口重新提到最顶层。
     *
     * Qt.WindowStaysOnTopHint 在部分 Wayland 合成器（KWin）下会被 Qt.Tool 削弱层级，
     * 普通窗口一聚焦就盖住它。这里重新设置置顶标志并 raise，强制刷新合成器层级。
     */
    Q_INVOKABLE void keepOnTop(QObject *window);

    /**
     * 全局指针坐标。
     *
     * layer-shell 窗口的 QWindow 位置由合成器决定、Qt 侧通常不可靠，
     * 因此不能用 mapToGlobal 推算拖动位移；这里直接向合成器要真实指针位置。
     */
    Q_INVOKABLE QPoint globalCursorPos() const;
};
