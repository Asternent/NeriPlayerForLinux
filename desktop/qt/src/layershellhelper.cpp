#include "layershellhelper.h"

#include <QGuiApplication>
#include <QScreen>
#include <QWindow>

// 由构建系统决定是否具备 LayerShellQt（见 CMakeLists 的 NERIPLAYER_HAVE_LAYERSHELLQT）
#ifdef NERIPLAYER_HAVE_LAYERSHELLQT
#include <LayerShellQt/window.h>
#define NERI_HAVE_LAYERSHELL 1
#else
#define NERI_HAVE_LAYERSHELL 0
#endif

namespace {

bool platformIsWayland()
{
    return QGuiApplication::platformName().contains(QStringLiteral("wayland"),
                                                    Qt::CaseInsensitive);
}

/** 按归一化比例计算窗口左上角在屏幕可用区域内的像素偏移。 */
QPoint computeOffset(QWindow *window, qreal ratioX, qreal ratioY, int width, int height)
{
    if (!window)
        return {};
    QScreen *screen = window->screen();
    if (!screen)
        screen = QGuiApplication::primaryScreen();
    if (!screen)
        return {};

    const QRect area = screen->availableGeometry();
    const int w = width > 0 ? width : qMax(1, window->width());
    const int h = height > 0 ? height : qMax(1, window->height());
    const qreal nx = qBound<qreal>(0.0, ratioX, 1.0);
    const qreal ny = qBound<qreal>(0.0, ratioY, 1.0);
    return {qRound((area.width() - w) * nx), qRound((area.height() - h) * ny)};
}

} // namespace

LayerShellHelper::LayerShellHelper(QObject *parent)
    : QObject(parent)
{
}

bool LayerShellHelper::available() const
{
#if NERI_HAVE_LAYERSHELL
    // 排查用开关：NERIPLAYER_NO_LAYERSHELL=1 时回退为普通窗口
    if (!qEnvironmentVariableIsEmpty("NERIPLAYER_NO_LAYERSHELL"))
        return false;
    return platformIsWayland();
#else
    return false;
#endif
}

bool LayerShellHelper::attach(QObject *object, qreal ratioX, qreal ratioY,
                              int width, int height)
{
#if NERI_HAVE_LAYERSHELL
    auto *window = qobject_cast<QWindow *>(object);
    if (!window || !platformIsWayland())
        return false;

    auto *layer = LayerShellQt::Window::get(window);
    if (!layer)
        return false;

    // overlay 层位于所有普通窗口之上（真正的「置顶」），且不进任务栏
    layer->setLayer(LayerShellQt::Window::LayerOverlay);
    layer->setScope(QStringLiteral("neriplayer-floating-lyrics"));
    // 不抢键盘焦点；不占用排他区域（否则会挤压其它窗口的可用空间）
    layer->setKeyboardInteractivity(LayerShellQt::Window::KeyboardInteractivityNone);
    layer->setExclusiveZone(-1);
    // 固定锚到左上角，用 margins 表达实际坐标
    layer->setAnchors(LayerShellQt::Window::Anchors(LayerShellQt::Window::AnchorTop)
                      | LayerShellQt::Window::AnchorLeft);
    if (width > 0 && height > 0)
        layer->setDesiredSize(QSize(width, height));

    const QPoint offset = computeOffset(window, ratioX, ratioY, width, height);
    layer->setMargins(QMargins(offset.x(), offset.y(), 0, 0));
    if (window->isVisible())
        window->requestUpdate();
    qInfo() << "[layershell] attached ratio=" << ratioX << ratioY
            << "size=" << width << height << "margin=" << offset
            << "screen=" << (window->screen() ? window->screen()->availableGeometry() : QRect());
    return true;
#else
    Q_UNUSED(object)
    Q_UNUSED(ratioX)
    Q_UNUSED(ratioY)
    Q_UNUSED(width)
    Q_UNUSED(height)
    return false;
#endif
}

bool LayerShellHelper::setPositionRatio(QObject *object, qreal ratioX, qreal ratioY)
{
#if NERI_HAVE_LAYERSHELL
    auto *window = qobject_cast<QWindow *>(object);
    if (!window || !platformIsWayland())
        return false;
    auto *layer = LayerShellQt::Window::get(window);
    if (!layer)
        return false;

    const QPoint offset = computeOffset(window, ratioX, ratioY, 0, 0);
    layer->setMargins(QMargins(offset.x(), offset.y(), 0, 0));
    // layer-shell 的属性变更要等下一次 wl_surface commit 才生效。
    // 拖动过程中窗口内容通常不变，Qt 不会主动提交，表现就是「拖的时候不动、
    // 松手才跳到新位置」。这里显式请求一帧重绘，强制提交新的 margin。
    if (window->isVisible())
        window->requestUpdate();
    return true;
#else
    Q_UNUSED(object)
    Q_UNUSED(ratioX)
    Q_UNUSED(ratioY)
    return false;
#endif
}

bool LayerShellHelper::setPositionMargins(QObject *object, int x, int y)
{
#if NERI_HAVE_LAYERSHELL
    auto *window = qobject_cast<QWindow *>(object);
    if (!window || !platformIsWayland())
        return false;
    auto *layer = LayerShellQt::Window::get(window);
    if (!layer)
        return false;
    layer->setMargins(QMargins(qMax(0, x), qMax(0, y), 0, 0));
    // margin 变更要下一次 surface commit 才生效，这里主动请求一帧
    if (window->isVisible())
        window->requestUpdate();
    return true;
#else
    Q_UNUSED(object)
    Q_UNUSED(x)
    Q_UNUSED(y)
    return false;
#endif
}

void LayerShellHelper::setDesiredSize(QObject *object, int width, int height)
{
#if NERI_HAVE_LAYERSHELL
    auto *window = qobject_cast<QWindow *>(object);
    if (!window || !platformIsWayland())
        return;
    if (auto *layer = LayerShellQt::Window::get(window))
        layer->setDesiredSize(QSize(qMax(1, width), qMax(1, height)));
#else
    Q_UNUSED(object)
    Q_UNUSED(width)
    Q_UNUSED(height)
#endif
}

bool LayerShellHelper::attachOverlayFullscreen(QObject *object)
{
#if NERI_HAVE_LAYERSHELL
    auto *window = qobject_cast<QWindow *>(object);
    if (!window || !platformIsWayland())
        return false;
    auto *layer = LayerShellQt::Window::get(window);
    if (!layer)
        return false;
    layer->setLayer(LayerShellQt::Window::LayerOverlay);
    layer->setScope(QStringLiteral("neriplayer-drag-preview"));
    layer->setKeyboardInteractivity(LayerShellQt::Window::KeyboardInteractivityNone);
    layer->setExclusiveZone(-1);
    // 锚定四边 = 铺满整个屏幕，窗口本身保持静止
    layer->setAnchors(LayerShellQt::Window::Anchors(LayerShellQt::Window::AnchorTop)
                      | LayerShellQt::Window::AnchorBottom
                      | LayerShellQt::Window::AnchorLeft
                      | LayerShellQt::Window::AnchorRight);
    layer->setMargins(QMargins(0, 0, 0, 0));
    if (window->isVisible())
        window->requestUpdate();
    return true;
#else
    Q_UNUSED(object)
    return false;
#endif
}

void LayerShellHelper::detach(QObject *object)
{
    Q_UNUSED(object)
    // layer-shell 的窗口角色在 surface 生命周期内不可撤销；窗口销毁时随之失效，
    // 因此这里无需（也无法）做额外清理。
}
