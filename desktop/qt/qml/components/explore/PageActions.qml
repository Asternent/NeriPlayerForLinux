import QtQuick

// 页面侧播放 / 设置写入的兼容层。
//
// 背景：globals/ 下的全局对象一旦内部引用 Api，就可能因为同目录隐式导入把 "Player"/"Settings"
// 解析成 QML 类型而不是 context property，跨对象调用会抛 TypeError（Player.setQueue / Settings.set
// 全部不可用）。主程序已改用 *Global.qml 命名并先注册 Api；这里仍保留一次兜底：
// 优先走全局封装，抛错时直接调用后端命令，避免页面功能因全局对象实现变化而失效。
QtObject {
    id: actions

    /** 用整张列表设置播放队列并从 index 开始播放。 */
    function playSongs(songs, index) {
        const list = songs || []
        if (list.length === 0)
            return
        const start = index === undefined || index === null ? 0 : index
        try {
            Player.setQueue(list, start, true)
            return
        } catch (error) {
            // 回落到底层命令（见文件头说明）
        }
        Api.call("player.setQueue", { "songs": list, "startIndex": start, "autoPlay": true }).catch(function(e) {
            Toast.show(e && e.message ? e.message : "播放失败", "error")
        })
    }

    /** 把歌曲插到当前播放的下一首。 */
    function enqueueNext(songs) {
        const list = songs || []
        if (list.length === 0)
            return
        try {
            Player.enqueueNext(list)
            Toast.show("已添加到下一首")
            return
        } catch (error) {
            // 回落到底层命令（见文件头说明）
        }
        Api.call("player.enqueueNext", { "songs": list }).then(function() {
            Toast.show("已添加到下一首")
        }).catch(function(e) {
            Toast.show(e && e.message ? e.message : "添加失败", "error")
        })
    }

    /** 写单个设置项；成功后同步 Settings.values，让依赖设置的界面立刻刷新。 */
    function saveSetting(key, value) {
        try {
            Settings.set(key, value)
            return
        } catch (error) {
            // 回落到底层命令（见文件头说明）
        }
        const patch = {}
        patch[key] = value
        Api.call("settings.set", { "patch": patch }).then(function(result) {
            if (result && result.settings)
                Settings.values = result.settings
        }).catch(function(e) {
            Toast.show(e && e.message ? e.message : "保存设置失败", "error")
        })
    }
}
