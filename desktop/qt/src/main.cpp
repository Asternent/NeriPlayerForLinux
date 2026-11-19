#include "backendclient.h"
#include "singleinstance.h"
#include "systemtheme.h"
#include "windowdrag.h"
#include "layershellhelper.h"

#include <QCommandLineOption>
#include <QCommandLineParser>
#include <QDir>
#include <QFile>
#include <QJsonDocument>
#include <QFileInfo>
#include <QGuiApplication>
#include <QIcon>
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
    // 安装布局：二进制在 /usr/lib/neriplayer，QML 在 /usr/share/neriplayer/qml
    const QStringList installedCandidates = {
        appDir + QStringLiteral("/../../share/neriplayer/qml"),
        appDir + QStringLiteral("/../share/neriplayer/qml"),
        appDir + QStringLiteral("/share/neriplayer/qml"),
        appDir + QStringLiteral("/qml"),
    };
    for (const QString &candidate : installedCandidates) {
        if (QFileInfo::exists(candidate + QStringLiteral("/Main.qml")))
            return QDir(candidate).absolutePath();
    }

    // 开发布局：仅当显式通过环境变量指定，或编译期源码目录确实存在且未安装时使用
    if (!QStringLiteral(NERIPLAYER_SOURCE_QML_DIR).isEmpty()
        && QFileInfo::exists(QStringLiteral(NERIPLAYER_SOURCE_QML_DIR) + QStringLiteral("/Main.qml"))) {
        return QStringLiteral(NERIPLAYER_SOURCE_QML_DIR);
    }
    return installedCandidates.first();
}

} // namespace

namespace {

/**
 * 手动界面缩放（设置项 uiScale > 0 时生效）。
 * 在 QGuiApplication 构造前读取 settings.json 并设置 QT_SCALE_FACTOR —— Qt 的平台插件
 * 在初始化时就读取该环境变量，之后再改无效。
 */
void applyManualUiScale()
{
    if (!qEnvironmentVariableIsEmpty("QT_SCALE_FACTOR"))
        return;
    QString configDir = QString::fromLocal8Bit(qgetenv("XDG_CONFIG_HOME"));
    if (configDir.isEmpty())
        configDir = QDir::homePath() + QStringLiteral("/.config");
    const QString path = configDir + QStringLiteral("/NeriPlayer/settings.json");
    QFile file(path);
    if (!file.open(QIODevice::ReadOnly))
        return;
    const QJsonDocument doc = QJsonDocument::fromJson(file.readAll());
    if (!doc.isObject())
        return;
    const double scale = doc.object().value(QStringLiteral("uiScale")).toDouble(0.0);
    if (scale > 0.0 && scale >= 0.5 && scale <= 3.0)
        qputenv("QT_SCALE_FACTOR", QByteArray::number(scale));
}

} // namespace

int main(int argc, char *argv[])
{
    applyManualUiScale();
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
    QCommandLineOption panelOption(QStringLiteral("panel"),
                                   QStringLiteral("启动后直接打开某个浮层面板：queue/volume/effects/sleep/downloads/login"),
                                   QStringLiteral("name"));
    QCommandLineOption screenshotAllOption(QStringLiteral("screenshot-all"),
                                           QStringLiteral("抓取所有顶层窗口后退出（前缀路径，生成 <prefix>-1.png 等）"),
                                           QStringLiteral("prefix"));
    QCommandLineOption selftestOption(QStringLiteral("selftest"),
                                      QStringLiteral("开发自检：验证 QML 全局包装器可调用后退出"));
    QCommandLineOption waitOption(QStringLiteral("wait"),
                                  QStringLiteral("截图前的等待毫秒数"),
                                  QStringLiteral("ms"),
                                  QStringLiteral("1200"));
    QCommandLineOption hideMainOption(QStringLiteral("hide-main"),
                                      QStringLiteral("启动后隐藏主窗口（验证托盘面板可独立弹出）"));
    parser.addOption(qmlDirOption);
    parser.addOption(selftestOption);
    parser.addOption(screenshotOption);
    parser.addOption(screenshotAllOption);
    parser.addOption(pageOption);
    parser.addOption(panelOption);
    parser.addOption(sizeOption);
    parser.addOption(waitOption);
    parser.addOption(hideMainOption);
    parser.process(app);

    SingleInstance instance;
    if (!instance.acquire()) {
        // 已有实例在跑：它已被通知激活窗口，本进程直接退出
        return 0;
    }

    const QString qmlDir = resolveQmlDir(parser.value(qmlDirOption));

    // 应用图标（窗口/Wayland 任务栏；桌面文件里的 Icon= 之外再兜底一层）
    for (const QString &candidate : {
             qmlDir + QStringLiteral("/resources/neriplayer.png"),
             QCoreApplication::applicationDirPath() + QStringLiteral("/../../desktop/packaging/neriplayer.png"),
             QStringLiteral("/usr/share/icons/hicolor/128x128/apps/neriplayer.png"),
         }) {
        if (QFileInfo::exists(candidate)) {
            QGuiApplication::setWindowIcon(QIcon(candidate));
            break;
        }
    }
    qInfo() << "[qml] loading from" << qmlDir;

    BackendClient backend;
    SystemTheme systemTheme;
    WindowDrag windowDrag;
    LayerShellHelper layerShell;
    QQmlEngine engine;
    engine.rootContext()->setContextProperty(QStringLiteral("Backend"), &backend);
    engine.rootContext()->setContextProperty(QStringLiteral("SystemTheme"), &systemTheme);
    engine.rootContext()->setContextProperty(QStringLiteral("WindowDrag"), &windowDrag);
    engine.rootContext()->setContextProperty(QStringLiteral("LayerShell"), &layerShell);

    // 全局对象用 *Global.qml 命名：如果文件就叫 Player.qml，同一目录的隐式导入会让 "Player"
    // 解析成 QML 类型而不是 context property（表现为跨对象调用 TypeError）。
    // Api 只依赖 Backend；Player/Theme/Settings 在函数体里引用 Api，所以 Api 先创建并立刻注册。
    QObject *api = createGlobal(engine, qmlDir, QStringLiteral("ApiGlobal.qml"));
    if (!api)
        return 1;
    engine.rootContext()->setContextProperty(QStringLiteral("Api"), api);

    QObject *player = createGlobal(engine, qmlDir, QStringLiteral("PlayerGlobal.qml"));
    QObject *theme = createGlobal(engine, qmlDir, QStringLiteral("ThemeGlobal.qml"));
    QObject *settings = createGlobal(engine, qmlDir, QStringLiteral("SettingsGlobal.qml"));
    QObject *nav = createGlobal(engine, qmlDir, QStringLiteral("NavGlobal.qml"));
    QObject *toast = createGlobal(engine, qmlDir, QStringLiteral("ToastGlobal.qml"));
    if (!player || !theme || !settings || !nav || !toast)
        return 1;

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
        // 窗口隐藏时释放 GPU/场景图缓存。
        //
        // 主窗口收进托盘后可能长时间不显示，Qt 默认仍保留场景图的纹理与图形资源。
        // releaseResources() 会在隐藏后把这块交还给驱动，重新显示时再按需重建。
        QObject::connect(window, &QWindow::visibleChanged, window, [window]() {
            if (!window->isVisible())
                window->releaseResources();
        });
        if (parser.isSet(pageOption))
            QMetaObject::invokeMethod(rootObject, "openPageByName",
                                      Q_ARG(QVariant, parser.value(pageOption)));
        // --hide-main 时面板由下面的分支统一弹出，避免这里重复调用导致「开了又关」
        if (parser.isSet(panelOption) && !parser.isSet(hideMainOption))
            QMetaObject::invokeMethod(rootObject, "openPanelByName",
                                      Q_ARG(QVariant, parser.value(panelOption)));
        if (parser.isSet(sizeOption)) {
            const QStringList parts = parser.value(sizeOption).split(QLatin1Char('x'));
            if (parts.size() == 2)
                window->resize(parts.at(0).toInt(), parts.at(1).toInt());
        }
        // 验证用：先隐藏主窗口，再从「托盘已在后台」的状态弹面板。
        if (parser.isSet(hideMainOption)) {
            QMetaObject::invokeMethod(rootObject, "hideMainWindow");
            if (parser.isSet(panelOption))
                QMetaObject::invokeMethod(rootObject, "openPanelByName",
                                          Q_ARG(QVariant, parser.value(panelOption)));
        }
    }

    backend.connectToBackend();

    QObject::connect(&instance, &SingleInstance::activationRequested, &app, [&rootObject]() {
        if (rootObject)
            QMetaObject::invokeMethod(rootObject, "showMainWindow");
    });

    if (parser.isSet(screenshotAllOption)) {
        const QString prefix = parser.value(screenshotAllOption);
        const int waitMs = parser.value(waitOption).toInt();
        QTimer::singleShot(waitMs, &app, [prefix]() {
            int index = 1;
            for (QWindow *window : QGuiApplication::topLevelWindows()) {
                auto *quick = qobject_cast<QQuickWindow *>(window);
                if (!quick || !quick->isVisible())
                    continue;
                const QImage image = quick->grabWindow();
                if (image.isNull())
                    continue;
                const QString path = prefix + QStringLiteral("-") + QString::number(index) + QStringLiteral(".png");
                if (image.save(path))
                    qInfo() << "[screenshot] saved" << path << image.size() << window->title();
                ++index;
            }
            QCoreApplication::exit(index > 1 ? 0 : 1);
        });
    } else if (parser.isSet(screenshotOption)) {
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
