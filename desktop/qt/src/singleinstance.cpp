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
    return QStringLiteral("neriplayer-qt-%1.sock").arg(QString::number(::getuid()));
}

bool SingleInstance::acquire()
{
    QDir().mkpath(runtimeDir());
    const QString name = socketName();

    // 已有实例：把激活请求发过去后返回 false
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
    }

    QLocalServer::removeServer(name);
    m_server = new QLocalServer(this);
    // 把 socket 放到 runtime 目录
    m_server->setSocketOptions(QLocalServer::UserAccessOption);
    if (!m_server->listen(name)) {
        // 监听失败也不要让应用起不来
        m_server->close();
        m_server = nullptr;
        return true;
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
