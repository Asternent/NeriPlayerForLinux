#pragma once

#include <QObject>
#include <QString>

class QLocalServer;

/**
 * 单实例锁：第二次启动时把 "activate" 发给已运行的实例并退出。
 * 用 QLocalServer（Unix domain socket）实现，socket 放在 $XDG_RUNTIME_DIR 下。
 */
class SingleInstance : public QObject {
    Q_OBJECT

public:
    explicit SingleInstance(QObject *parent = nullptr);
    ~SingleInstance() override;

    /** 尝试成为主实例。返回 false 表示已有实例在跑（已通知它激活窗口）。 */
    bool acquire();

signals:
    void activationRequested();

private:
    static QString socketName();
    QLocalServer *m_server = nullptr;
};
