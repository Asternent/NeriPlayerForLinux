#include "singleinstance.h"

#include <QCoreApplication>
#include <QDir>
#include <QLocalServer>
#include <QLocalSocket>
#include <QStandardPaths>
#include <unistd.h>

namespace {

QString runtimeDir()
{
    const QString dir = QString::fromLocal8Bit(qgetenv("XDG_RUNTIME_DIR"));
    return dir.isEmpty() ? QDir::tempPath() : dir;
}

QString socketPath()
{
    return runtimeDir() + QStringLiteral("/neriplayer-qt-%1.sock").arg(QString::number(::getuid()));
}

} // namespace

SingleInstance::SingleInstance(QObject *parent)
    : QObject(parent)
{
}

SingleInstance::~SingleInstance()
{
    if (m_server) {
        m_server->close();
        QLocalServer::removeServer(socketName());
    }
}

QString SingleInstance::socketName()
{
    return socketPath();
}

bool SingleInstance::acquire()
{
    QDir().mkpath(runtimeDir());
    const QString name = socketName();

    // 已有实例：把激活请求发过去后返回 false。
    // 注意要先探测是否真的有人监听 —— 异常退出会留下 socket 文件，
    // 直接当实例存在会让程序「启动后立刻自己退出」。
    {
        QLocalSocket probe;
        probe.connectToServer(name);
        if (probe.waitForConnected(300)) {
            probe.write("activate\n");
            probe.flush();
            probe.waitForBytesWritten(300);
            probe.disconnectFromServer();
            return false;
        }
        // 连不上说明是残留 socket，清掉后再尝试成为主实例
        QLocalServer::removeServer(name);
    }

    m_server = new QLocalServer(this);
    m_server->setSocketOptions(QLocalServer::UserAccessOption);
    if (!m_server->listen(name)) {
        // 竞态：另一个实例刚好在我们探测之后、listen 之前抢到了 socket。
        // 这种情况应当当作「已有实例」退出，而不是把自己当成独立实例继续运行
        // （否则会出现多个窗口）。
        m_server->deleteLater();
        m_server = nullptr;
        QLocalSocket probe;
        probe.connectToServer(name);
        if (probe.waitForConnected(300)) {
            probe.write("activate\n");
            probe.flush();
            probe.waitForBytesWritten(300);
            probe.disconnectFromServer();
        }
        return false;
    }
    connect(m_server, &QLocalServer::newConnection, this, [this]() {
        while (QLocalSocket *socket = m_server->nextPendingConnection()) {
            connect(socket, &QLocalSocket::disconnected, socket, &QLocalSocket::deleteLater);
            connect(socket, &QLocalSocket::readyRead, socket, [this, socket]() {
                const QByteArray data = socket->readAll();
                if (data.contains("activate"))
                    emit activationRequested();
            });
            socket->waitForReadyRead(50);
            if (socket->bytesAvailable() > 0) {
                const QByteArray data = socket->readAll();
                if (data.contains("activate"))
                    emit activationRequested();
            }
        }
    });
    return true;
}
