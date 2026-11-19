#include "backendclient.h"

#include <QCoreApplication>
#include <QDateTime>
#include <QDebug>
#include <QDir>
#include <QFileInfo>
#include <QJSEngine>
#include <QJsonArray>
#include <QJsonDocument>
#include <QJsonObject>
#include <QJsonParseError>
#include <QJsonValue>
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

/**
 * 把 JSON 转成 JS 值。这里不用 QJsonValue::toVariant()+QJSEngine::toScriptValue()：
 * Qt 6 会把 QVariantList 变成「类数组序列对象」，Array.isArray() 为 false，
 * 而且嵌套数组/对象里也依然是序列对象，QML 侧的 Array.isArray 判断全部失效。
 * 显式递归构造 QJSValue 数组，保证前后端之间传递的列表就是真正的 JS 数组。
 */
QJSValue jsonToJs(QJSEngine *engine, const QJsonValue &value)
{
    if (!engine)
        return QJSValue();
    if (value.isUndefined() || value.isNull())
        return QJSValue(QJSValue::NullValue);
    if (value.isBool())
        return QJSValue(value.toBool());
    if (value.isDouble())
        return QJSValue(value.toDouble());
    if (value.isString())
        return QJSValue(value.toString());
    if (value.isArray()) {
        const QJsonArray array = value.toArray();
        QJSValue jsArray = engine->newArray(static_cast<quint32>(array.size()));
        for (int i = 0; i < array.size(); ++i)
            jsArray.setProperty(static_cast<quint32>(i), jsonToJs(engine, array.at(i)));
        return jsArray;
    }
    if (value.isObject()) {
        const QJsonObject object = value.toObject();
        QJSValue jsObject = engine->newObject();
        for (auto it = object.constBegin(); it != object.constEnd(); ++it)
            jsObject.setProperty(it.key(), jsonToJs(engine, it.value()));
        return jsObject;
    }
    return QJSValue(QJSValue::NullValue);
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
    // socket 不存在说明后端没在跑：按 NERIPLAYER_BACKEND_JAR 拉起一个（打包后的默认行为）
    if (!m_triedStart && !QFileInfo::exists(m_socketPath)) {
        m_triedStart = true;
        tryStartBackend();
    }
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
            QJSEngine *engine = qjsEngine(this);
            emit event(name, jsonToJs(engine, object.value(QStringLiteral("data"))));
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
            args[0] = jsonToJs(engine, object.value(QStringLiteral("result")));
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
    // jar：环境变量 > 安装布局（lib/neriplayer/neriplayer-core.jar）> 开发构建产物
    QString jar = QString::fromLocal8Bit(qgetenv("NERIPLAYER_BACKEND_JAR"));
    if (jar.isEmpty() || !QFileInfo::exists(jar)) {
        const QString appDir = QCoreApplication::applicationDirPath();
        const QStringList candidates = {
            appDir + QStringLiteral("/neriplayer-core.jar"),
            appDir + QStringLiteral("/../../lib/neriplayer/neriplayer-core.jar"),
            appDir + QStringLiteral("/../lib/neriplayer/neriplayer-core.jar"),
            appDir + QStringLiteral("/../../desktop/build/libs/neriplayer-core.jar"),
        };
        jar.clear();
        for (const QString &candidate : candidates) {
            if (QFileInfo::exists(candidate)) {
                jar = candidate;
                break;
            }
        }
    }
    if (jar.isEmpty() || !QFileInfo::exists(jar))
        return false;

    // java：环境变量 > 自带的 jlink 运行时 > 系统 java
    QString java = QString::fromLocal8Bit(qgetenv("NERIPLAYER_JAVA"));
    if (java.isEmpty()) {
        const QString appDir = QCoreApplication::applicationDirPath();
        for (const QString &candidate : {
                 appDir + QStringLiteral("/runtime/bin/java"),
                 appDir + QStringLiteral("/../lib/neriplayer/runtime/bin/java"),
                 appDir + QStringLiteral("/../../lib/neriplayer/runtime/bin/java"),
             }) {
            if (QFileInfo::exists(candidate)) {
                java = candidate;
                break;
            }
        }
    }
    if (java.isEmpty())
        java = QStandardPaths::findExecutable(QStringLiteral("java"));
    if (java.isEmpty())
        return false;

    QStringList args;
    args << QStringLiteral("-Dfile.encoding=UTF-8")
         << QStringLiteral("-Xmx512m")
         << QStringLiteral("-XX:+UseG1GC")
         << QStringLiteral("-jar") << jar;

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
