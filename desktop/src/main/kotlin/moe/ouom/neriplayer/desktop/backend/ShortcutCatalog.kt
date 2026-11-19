package moe.ouom.neriplayer.desktop.backend

import moe.ouom.neriplayer.desktop.core.ShortcutAction

/** 把快捷键动作表编码成前端可展示的数据（id/文案/分类/默认键位）。 */
object ShortcutCatalog {

    fun payload(): List<Map<String, Any?>> = ShortcutAction.entries.map { action ->
        mapOf(
            "id" to action.id,
            "label" to action.label,
            "category" to action.category,
            "systemKey" to action.systemKey,
            "globalSupported" to action.globalSupported,
            "localDefault" to action.defaultLocalBinding.display(),
            "globalDefault" to action.defaultGlobalBinding.display(),
        )
    }
}
