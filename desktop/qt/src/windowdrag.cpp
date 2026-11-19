#include "windowdrag.h"

#include <QCursor>
#include <QGuiApplication>
#include <QWindow>

WindowDrag::WindowDrag(QObject *parent)
    : QObject(parent)
{
}

bool WindowDrag::usesSystemMove() const
{
    return QGuiApplication::platformName().contains(QStringLiteral("wayland"),
                                                    Qt::CaseInsensitive);
}

void WindowDrag::startMove(QObject *object)
{
    auto *window = qobject_cast<QWindow *>(object);
    if (!window)
        return;
#if QT_VERSION >= QT_VERSION_CHECK(5, 15, 0)
    if (usesSystemMove())
        window->startSystemMove();
#else
    Q_UNUSED(window)
#endif
}

bool WindowDrag::detachFromParent(QObject *object)
{
    auto *window = qobject_cast<QWindow *>(object);
    if (!window)
        return false;
    // 只断开 transient parent，不改 flags / QObject parent：
    //  - 运行时重设 QWindow::flags 会让 Qt 重建原生窗口，把 visible 状态一起重置；
    //  - 改 QObject parent 会破坏 QML 的所有权，窗口可能被提前销毁。
    window->setTransientParent(nullptr);
    return true;
}

void WindowDrag::detachChildWindows(QObject *parentWindow)
{
    auto *parent = qobject_cast<QWindow *>(parentWindow);
    if (!parent)
        return;
    // 遍历该窗口的所有子窗口，凡是「需要在主窗口隐藏后继续显示」的都断开 transient 关系。
    for (QWindow *child : parent->findChildren<QWindow *>()) {
        if (!child)
            continue;
        child->setTransientParent(nullptr);
    }
}

void WindowDrag::keepVisible(QObject *object)
{
    auto *window = qobject_cast<QWindow *>(object);
    if (!window)
        return;
    // 父窗口已经不可见了：断开 transient 并重新显示自己。
    window->setTransientParent(nullptr);
    if (!window->isVisible())
        window->show();
    window->raise();
}

QPoint WindowDrag::globalCursorPos() const
{
    // QCursor::pos() 在 Wayland 下由合成器提供，与窗口位置无关
    return QCursor::pos();
}

void WindowDrag::keepOnTop(QObject *object)
{
    auto *window = qobject_cast<QWindow *>(object);
    if (!window)
        return;
    // 注意：可见状态下重设 QWindow::flags 会让 Qt 重建原生窗口，位置/可见性被重置。
    // 所以只在未显示时补标志；已显示时靠 raise 刷新合成器层级。
    if (!window->isVisible()) {
        Qt::WindowFlags flags = window->flags();
        flags |= Qt::WindowStaysOnTopHint;
        window->setFlags(flags);
        return;
    }
    window->raise();
}
