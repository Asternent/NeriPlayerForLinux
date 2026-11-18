#pragma once

#include <QObject>
#include <QVariantMap>

/**
 * 系统主题信息：深浅色 + 系统强调色（KDE 的 accent 色 / Qt 调色板 Highlight）。
 * 用户要求 Qt 前端的主题沿用系统配置，后端据此生成 Material3 调色板。
 */
class SystemTheme : public QObject {
    Q_OBJECT

public:
    explicit SystemTheme(QObject *parent = nullptr);

    /** 当前系统主题快照：{ isDark, accent, background, foreground }。 */
    Q_INVOKABLE QVariantMap payload() const;

    /** 界面缩放跟随的系统缩放因子。 */
    Q_INVOKABLE qreal devicePixelRatio() const;

signals:
    void changed();

protected:
    bool eventFilter(QObject *watched, QEvent *event) override;

private:
    void notifyChange();
};
