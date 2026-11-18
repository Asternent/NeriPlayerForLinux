#pragma once

#include <QHash>
#include <QJSValue>
#include <QJsonObject>
#include <QObject>
#include <QPointer>
#include <QTimer>
#include <QVariantMap>

class QLocalSocket;

/**
 * 与 Kotlin 无头后端（neriplayer-core）通信的客户端。
 *
 * 传输：Unix domain socket，按行分隔的 JSON。
 *   - 请求：{"id": 1, "method": "player.playPause", "params": {...}}
 *   - 响应：{"id": 1, "result": {...}} 或 {"id": 1, "error": {"code": "...", "message": "..."}}
 *   - 事件：{"event": "player", "data": {...}}
 *
 * 前端只通过这一个类和后端交互；QML 侧一般用 Api.qml 的 Promise 封装。
 */
class BackendClient : public QObject {
    Q_OBJECT
    Q_PROPERTY(bool connected READ connected NOTIFY connectedChanged)
    Q_PROPERTY(QString socketPath READ socketPath CONSTANT)

public:
    explicit BackendClient(QObject *parent = nullptr);
    ~BackendClient() override;

    bool connected() const { return m_connected; }
    QString socketPath() const { return m_socketPath; }

    /** 立即尝试连接（会自动重试，直到连上为止）。 */
    Q_INVOKABLE void connectToBackend();

    /**
     * 发送一次请求。callback 可选，签名 (result, error)：
     *   - 成功：error 为 undefined/null，result 为对象
     *   - 失败：error = {code, message}
     */
    Q_INVOKABLE int call(const QString &method, const QVariantMap &params, const QJSValue &callback = QJSValue());

    /** 后端进程是否由本客户端拉起（退出时需要一并结束）。 */
    bool spawnedBackend() const { return m_backendPid > 0; }
    qint64 backendPid() const { return m_backendPid; }
    void shutdownBackend();

signals:
    void connectedChanged();
    void event(const QString &name, const QVariantMap &data);
    void requestFailed(const QString &method, const QString &message);

private slots:
    void onReadyRead();
    void onSocketStateChanged();

private:
    void ensureConnectedSoon();
    bool tryStartBackend();
    void sendLine(const QJsonObject &object);
    void flushOutbox();

    QLocalSocket *m_socket = nullptr;
    QTimer m_reconnectTimer;
    QByteArray m_readBuffer;
    QHash<int, QJSValue> m_callbacks;
    QHash<int, qint64> m_deadlines;
    QTimer m_timeoutTimer;
    int m_nextId = 1;
    bool m_connected = false;
    QString m_socketPath;
    qint64 m_backendPid = -1;
    bool m_triedStart = false;
};
