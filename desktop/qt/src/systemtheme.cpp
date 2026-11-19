#include "systemtheme.h"

#include <QEvent>
#include <QGuiApplication>
#include <QPalette>
#include <QScreen>
#include <QStyleHints>

namespace {

QString colorToHex(const QColor &color)
{
    if (!color.isValid())
        return QString();
    return color.name(QColor::HexRgb).toUpper().replace(QLatin1Char('#'), QString());
}

} // namespace

SystemTheme::SystemTheme(QObject *parent)
    : QObject(parent)
{
    connect(QGuiApplication::styleHints(), &QStyleHints::colorSchemeChanged, this,
            [this](Qt::ColorScheme) { notifyChange(); });
    // Qt6 中 paletteChanged 已废弃，改为监听 ApplicationPaletteChange 事件（KDE 换配色/强调色时触发）
    QGuiApplication::instance()->installEventFilter(this);
}

bool SystemTheme::eventFilter(QObject *watched, QEvent *event)
{
    if (event->type() == QEvent::ApplicationPaletteChange)
        notifyChange();
    return QObject::eventFilter(watched, event);
}

void SystemTheme::notifyChange()
{
    emit changed();
}

QVariantMap SystemTheme::payload() const
{
    const QPalette palette = QGuiApplication::palette();
    const Qt::ColorScheme scheme = QGuiApplication::styleHints()->colorScheme();

    QColor accent;
    // Qt 6.6+ 有 Accent 角色；KDE 平台上由 plasma-integration 提供
    accent = palette.color(QPalette::Accent);
    if (!accent.isValid() || accent == palette.color(QPalette::Window))
        accent = palette.color(QPalette::Highlight);
    if (!accent.isValid())
        accent = QColor(QStringLiteral("#0061A4"));

    bool isDark = scheme == Qt::ColorScheme::Dark;
    const QColor window = palette.color(QPalette::Window);
    if (scheme == Qt::ColorScheme::Unknown)
        isDark = window.lightness() < 128;

    QVariantMap result;
    result.insert(QStringLiteral("isDark"), isDark);
    result.insert(QStringLiteral("accent"), colorToHex(accent));
    result.insert(QStringLiteral("background"), colorToHex(window));
    result.insert(QStringLiteral("foreground"), colorToHex(palette.color(QPalette::WindowText)));
    result.insert(QStringLiteral("darkAvailable"), scheme != Qt::ColorScheme::Unknown);

    QScreen *screen = QGuiApplication::primaryScreen();
    result.insert(QStringLiteral("devicePixelRatio"), screen ? screen->devicePixelRatio() : 1.0);
    return result;
}

qreal SystemTheme::devicePixelRatio() const
{
    QScreen *screen = QGuiApplication::primaryScreen();
    return screen ? screen->devicePixelRatio() : 1.0;
}
