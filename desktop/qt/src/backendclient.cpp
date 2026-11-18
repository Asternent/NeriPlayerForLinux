#include "backendclient.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QFileInfo>
#include <QJSEngine>
#include <QJsonDocument>
#include <QJsonParseError>
#include <QLocalSocket>
#include <QProcess>
#include <QStandardPaths>
#include <QThread>
#include <csignal>
#include <unistd.h>

namespace {

QString defaultSocketPath()
{
    const QByteArray override = qgetenv("NERIPLAYER_SOCKET");
    if (!override.isEmpty())
        return QString::fromLocal8Bit(override);

    QString runtimeDir = QString::fromLocal8Bit(qgetenv("XDG_RUNTIME_DIR"));
    if (runtimeDir.isEmpty())
        runtimeDir = QDir::tempPath();
    return runtimeDir + QStringLiteral("/neriplayer/core.sock");
}

} // namespace

BackendClient::BackendClient(QObject *parent)
    : QObject(parent)
    , m_socketPath(defaultSocketPath())
{
    m_socket = new QLocalSocket(this);
    connect(m_socket, &QLocalSocket::readyRead, this, &BackendClient::onReadyRead);
    connect(m_socket, &QLocalSocket::stateChanged, this, &BackendClient::onSocketStateChanged);
    // 连接失败（后端还没起来）时，QLocalSocket 会把错误从异步信号抛出，这里静默重试
    connect(m_socket, &QLocalSocket::errorOccurred, this, [this](QLocalSocket::LocalSocketError error) {
        if (error != QLocalSocket::PeerClosedError && !m_connected)
            ensureConnectedSoon();
    });

    m_reconnectTimer.setSingleShot(true);
    m_reconnectTimer.setInterval(400);
    connect(&m_reconnectTimer, &QTimer::timeout, this, [this]() {
        if (m_socket->state() == QLocalSocket::UnconnectedState)
            m_socket->connectToServer(m_socketPath);
    });

    m_timeoutTimer.setInterval(1000);
    connect(&m_timeoutTimer, &QTimer::timeout, this, [this]() {
        const qint64 now = QDateTime::currentMSecsSinceEpoch();
        for (auto it = m_deadlines.begin(); it != m_deadlines.end();) {
            if (it.value() > now) {
                ++it;
                continue;
            }
            const int id = it.key();
            it = m_deadlines.erase(it);
            const QJSValue callback = m_callbacks.take(id);
            if (callback.isCallable()) {
                QJSEngine *engine = qjsEngine(this);
                QJSValue args[2];
                args[0] = QJSValue(QJSValue::UndefinedValue);
                QVariantMap error;
                error.insert(QStringLiteral("code"), QStringLiteral("timeout"));
                error.insert(QStringLiteral("message"), QStringLiteral("后端请求超时"));
                args[1] = engine ? engine->toScriptValue(error) : QJSValue();
                callback.call(QJSValueList{args[0], args[1]});
            }
        }
    });
    m_timeoutTimer.start();
}

BackendClient::~BackendClient()
{
    shutdownBackend();
}

void BackendClient::connectToBackend()
{
    if (m_socket->state() != QLocalSocket::UnconnectedState)
        return;
    m_socket->connectToServer(m_socketPath);
}

void BackendClient::ensureConnectedSoon()
{
    if (!m_reconnectTimer.isActive())
        m_reconnectTimer.start();
}

void BackendClient::onSocketStateChanged()
{
    const bool nowConnected = m_socket->state() == QLocalSocket::ConnectedState;
    if (nowConnected == m_connected)
        return;
    m_connected = nowConnected;
    if (m_connected) {
        m_readBuffer.clear();
        qInfo() << "[backend] connected to" << m_socketPath;
        // QLocalSocket 在 stateChanged(ConnectedState) 的调用栈里还没真正 open，
        // 直接写会得到 "device not open"；把通知排到事件循环下一轮再发出去。
        QTimer::singleShot(0, this, [this]() {
            if (m_connected)
                emit connectedChanged();
        });
    } else {
        qInfo() << "[backend] disconnected from" << m_socketPath;
        m_reconnectTimer.start();
        emit connectedChanged();
    }
}

void BackendClient::onReadyRead()
{
    m_readBuffer.append(m_socket->readAll());
    int newline = -1;
    while ((newline = m_readBuffer.indexOf('\n')) >= 0) {
        const QByteArray line = m_readBuffer.left(newline).trimmed();
        m_readBuffer.remove(0, newline + 1);
        if (line.isEmpty())
            continue;
        QJsonParseError parseError{};
        const QJsonDocument doc = QJsonDocument::fromJson(line, &parseError);
        if (parseError.error != QJsonParseError::NoError || !doc.isObject()) {
            qWarning() << "[backend] bad frame:" << parseError.errorString() << line.left(200);
            continue;
        }
        const QJsonObject object = doc.object();
        if (object.contains(QStringLiteral("event"))) {
            const QString name = object.value(QStringLiteral("event")).toString();
            const QVariantMap data = object.value(QStringLiteral("data")).toObject().toVariantMap();
            emit event(name, data);
            continue;
        }
        const int id = object.value(QStringLiteral("id")).toInt(-1);
        if (id < 0)
            continue;
        m_deadlines.remove(id);
        const QJSValue callback = m_callbacks.take(id);
        if (!callback.isCallable())
            continue;
        QJSEngine *engine = qjsEngine(this);
        QJSValue args[2];
        if (object.contains(QStringLiteral("error"))) {
            const QVariantMap error =
                object.value(QStringLiteral("error")).toObject().toVariantMap();
            args[0] = QJSValue(QJSValue::UndefinedValue);
            args[1] = engine ? engine->toScriptValue(error) : QJSValue();
        } else {
            args[0] = engine ? engine->toScriptValue(
                                   object.value(QStringLiteral("result")).toVariant())
                             : QJSValue();
            args[1] = QJSValue(QJSValue::UndefinedValue);
        }
        callback.call(QJSValueList{args[0], args[1]});
    }
}

int BackendClient::call(const QString &method, const QVariantMap &params, const QJSValue &callback)
{
    const int id = m_nextId++;
    if (!m_connected) {
        // 未连接：不排队（避免界面卡住），立刻回调错误；调用方一般会重试或等待 Player connected 事件
        if (callback.isCallable()) {
            QJSEngine *engine = qjsEngine(this);
            QVariantMap error;
            error.insert(QStringLiteral("code"), QStringLiteral("disconnected"));
            error.insert(QStringLiteral("message"), QStringLiteral("后端未连接"));
            QJSValue undef(QJSValue::UndefinedValue);
            QJSValue errValue = engine ? engine->toScriptValue(error) : QJSValue();
            callback.call(QJSValueList{undef, errValue});
        }
        emit requestFailed(method, QStringLiteral("backend not connected"));
        return -1;
    }

    if (callback.isCallable()) {
        m_callbacks.insert(id, callback);
        m_deadlines.insert(id, QDateTime::currentMSecsSinceEpoch() + 60'000);
    }

    QJsonObject object;
    object.insert(QStringLiteral("id"), id);
    object.insert(QStringLiteral("method"), method);
    object.insert(QStringLiteral("params"),
                  QJsonObject::fromVariantMap(params.isEmpty() ? QVariantMap{} : params));
    sendLine(object);
    return id;
}

void BackendClient::sendLine(const QJsonObject &object)
{
    if (!m_connected) {
        qWarning() << "[backend] sendLine skipped: not connected";
        return;
    }
    if (!m_socket->isOpen())
        qWarning() << "[backend] sendLine: socket not open, state=" << m_socket->state()
                   << "error=" << m_socket->errorString();
    QByteArray line = QJsonDocument(object).toJson(QJsonDocument::Compact);
    line.append('\n');
    m_socket->write(line);
    m_socket->flush();
}

void BackendClient::flushOutbox()
{
    // 预留：断线重连后补发请求
}

bool BackendClient::tryStartBackend()
{
    const QByteArray jar = qgetenv("NERIPLAYER_BACKEND_JAR");
    if (jar.isEmpty() || !QFileInfo::exists(QString::fromLocal8Bit(jar)))
        return false;

    QString java = QString::fromLocal8Bit(qgetenv("NERIPLAYER_JAVA"));
    if (java.isEmpty())
        java = QStandardPaths::findExecutable(QStringLiteral("java"));
    if (java.isEmpty())
        return false;

    QStringList args;
    args << QStringLiteral("-Dfile.encoding=UTF-8")
         << QStringLiteral("-Xmx512m")
         << QStringLiteral("-XX:+UseG1GC")
         << QStringLiteral("-jar") << QString::fromLocal8Bit(jar);

    qint64 pid = -1;
    const bool started = QProcess::startDetached(java, args, QDir::homePath(), &pid);
    if (started && pid > 0) {
        m_backendPid = pid;
        qInfo() << "[backend] spawned backend pid" << pid;
    }
    return started;
}

void BackendClient::shutdownBackend()
{
    if (m_backendPid <= 0)
        return;
    ::kill(static_cast<pid_t>(m_backendPid), SIGTERM);
    m_backendPid = -1;
}
