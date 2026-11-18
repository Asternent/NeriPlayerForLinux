#include "backendclient.h"
#include "systemtheme.h"

#include <QCommandLineOption>
#include <QCommandLineParser>
#include <QDir>
#include <QFileInfo>
#include <QGuiApplication>
#include <QImage>
#include <QQmlComponent>
#include <QQmlContext>
#include <QQmlEngine>
#include <QQuickWindow>
#include <QTimer>
#include <QVariant>

namespace {

/** 按 QML 源码目录创建 QML 侧全局对象（Theme / Api / Player / Settings / Nav / Toast）。 */
QObject *createGlobal(QQmlEngine &engine, const QString &qmlDir, const QString &name)
{
    QQmlComponent component(&engine, QUrl::fromLocalFile(qmlDir + QStringLiteral("/globals/") + name));
    if (component.isError()) {
        qWarning() << "[qml] failed to create" << name << component.errorString();
        return nullptr;
    }
    QObject *object = component.create();
    if (!object)
        qWarning() << "[qml] failed to instantiate" << name << component.errorString();
    return object;
}

QString resolveQmlDir(const QString &explicitDir)
{
    if (!explicitDir.isEmpty())
        return explicitDir;

    const QByteArray env = qgetenv("NERIPLAYER_QML_DIR");
    if (!env.isEmpty())
        return QString::fromLocal8Bit(env);

    const QString appDir = QCoreApplication::applicationDirPath();
    for (const QString &candidate : {
             appDir + QStringLiteral("/../share/neriplayer/qml"),
             appDir + QStringLiteral("/qml"),
             appDir + QStringLiteral("/../qml"),
             QStringLiteral(NERIPLAYER_SOURCE_QML_DIR),
         }) {
        if (QFileInfo::exists(candidate + QStringLiteral("/Main.qml")))
            return QDir(candidate).absolutePath();
    }
    return appDir + QStringLiteral("/qml");
}

} // namespace

int main(int argc, char *argv[])
{
    QGuiApplication app(argc, argv);
    QGuiApplication::setApplicationName(QStringLiteral("NeriPlayer"));
    QGuiApplication::setApplicationDisplayName(QStringLiteral("NeriPlayer"));
    QGuiApplication::setOrganizationName(QStringLiteral("NeriPlayer"));
    QGuiApplication::setApplicationVersion(QStringLiteral(NERIPLAYER_VERSION));
    QGuiApplication::setDesktopFileName(QStringLiteral("neriplayer"));

    QCommandLineParser parser;
    parser.setApplicationDescription(QStringLiteral("NeriPlayer Qt 前端"));
    parser.addHelpOption();
    parser.addVersionOption();

    QCommandLineOption qmlDirOption(QStringLiteral("qml-dir"),
                                    QStringLiteral("QML 源码目录（开发用）"),
                                    QStringLiteral("dir"));
    QCommandLineOption screenshotOption(QStringLiteral("screenshot"),
                                        QStringLiteral("截图后退出（用于自动化验证）"),
                                        QStringLiteral("file"));
    QCommandLineOption pageOption(QStringLiteral("page"),
                                  QStringLiteral("启动后直接打开的页面，例如 home / explore / library / settings / nowplaying"),
                                  QStringLiteral("name"));
    QCommandLineOption sizeOption(QStringLiteral("size"),
                                  QStringLiteral("窗口尺寸，例如 1180x820"),
                                  QStringLiteral("WxH"));
    QCommandLineOption waitOption(QStringLiteral("wait"),
                                  QStringLiteral("截图前的等待毫秒数"),
                                  QStringLiteral("ms"),
                                  QStringLiteral("1200"));
    parser.addOption(qmlDirOption);
    parser.addOption(screenshotOption);
    parser.addOption(pageOption);
    parser.addOption(sizeOption);
    parser.addOption(waitOption);
    parser.process(app);

    const QString qmlDir = resolveQmlDir(parser.value(qmlDirOption));
    qInfo() << "[qml] loading from" << qmlDir;

    BackendClient backend;
    SystemTheme systemTheme;
    QQmlEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("Backend"), &backend);
    engine.rootContext()->setContextProperty(QStringLiteral("SystemTheme"), &systemTheme);

    // 全局对象的创建顺序要注意依赖：Api 只依赖 Backend；Player/Theme/Settings 依赖 Api
    QObject *api = createGlobal(engine, qmlDir, QStringLiteral("Api.qml"));
    QObject *player = createGlobal(engine, qmlDir, QStringLiteral("Player.qml"));
    QObject *theme = createGlobal(engine, qmlDir, QStringLiteral("Theme.qml"));
    QObject *settings = createGlobal(engine, qmlDir, QStringLiteral("Settings.qml"));
    QObject *nav = createGlobal(engine, qmlDir, QStringLiteral("Nav.qml"));
    QObject *toast = createGlobal(engine, qmlDir, QStringLiteral("Toast.qml"));
    if (!api || !player || !theme || !settings || !nav || !toast)
        return 1;

    engine.rootContext()->setContextProperty(QStringLiteral("Api"), api);
    engine.rootContext()->setContextProperty(QStringLiteral("Player"), player);
    engine.rootContext()->setContextProperty(QStringLiteral("Theme"), theme);
    engine.rootContext()->setContextProperty(QStringLiteral("Settings"), settings);
    engine.rootContext()->setContextProperty(QStringLiteral("Nav"), nav);
    engine.rootContext()->setContextProperty(QStringLiteral("Toast"), toast);

    QQmlComponent mainComponent(&engine, QUrl::fromLocalFile(qmlDir + QStringLiteral("/Main.qml")));
    if (mainComponent.isError()) {
        qCritical() << "[qml] Main.qml error:" << mainComponent.errorString();
        return 1;
    }
    QObject *rootObject = mainComponent.create();
    if (!rootObject) {
        qCritical() << "[qml] failed to instantiate Main.qml:" << mainComponent.errorString();
        return 1;
    }

    QQuickWindow *window = qobject_cast<QQuickWindow *>(rootObject);
    if (window) {
        if (parser.isSet(pageOption))
            QMetaObject::invokeMethod(rootObject, "openPageByName",
                                      Q_ARG(QVariant, parser.value(pageOption)));
        if (parser.isSet(sizeOption)) {
            const QStringList parts = parser.value(sizeOption).split(QLatin1Char('x'));
            if (parts.size() == 2)
                window->resize(parts.at(0).toInt(), parts.at(1).toInt());
        }
    }

    backend.connectToBackend();

    if (parser.isSet(screenshotOption)) {
        const QString target = parser.value(screenshotOption);
        const int waitMs = parser.value(waitOption).toInt();
        QTimer::singleShot(waitMs, &app, [window, target]() {
            if (!window) {
                qCritical() << "[screenshot] root is not a window";
                QCoreApplication::exit(1);
                return;
            }
            const QImage image = window->grabWindow();
            if (image.isNull()) {
                qCritical() << "[screenshot] grabWindow returned null image";
                QCoreApplication::exit(1);
                return;
            }
            if (!image.save(target))
                qCritical() << "[screenshot] failed to save" << target;
            else
                qInfo() << "[screenshot] saved" << target << image.size();
            QCoreApplication::exit(0);
        });
    }

    return app.exec();
}
