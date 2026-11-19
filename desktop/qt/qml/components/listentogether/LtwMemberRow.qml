import QtQuick
import ".."

// 一起听成员行：头像首字 + 昵称 + 角色。控制者用高亮色标出，「我」额外标记。
Item {
    id: root

    property var member: ({})
    property bool isSelf: false

    readonly property string nickname: member && member.nickname !== undefined && member.nickname !== null
                                      ? String(member.nickname) : "未知成员"
    readonly property bool controllerMember: member && String(member.role || "") === "controller"
    readonly property string initial: nickname.length > 0 ? nickname.substring(0, 1).toUpperCase() : "?"

    implicitHeight: 48

    Row {
        anchors.fill: parent
        spacing: Theme.spacingMd

        Rectangle {
            width: 34
            height: 34
            radius: 17
            anchors.verticalCenter: parent.verticalCenter
            color: root.controllerMember
                   ? Theme.withAlpha(Theme.tertiary, 0.18)
                   : Theme.withAlpha(Theme.primary, 0.12)

            Text {
                anchors.centerIn: parent
                text: root.initial
                color: root.controllerMember ? Theme.tertiary : Theme.primary
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelLarge").size
                font.weight: Font.DemiBold
            }
        }

        Column {
            width: parent.width - 34 - (roleChip.width + 8) - Theme.spacingMd * 2
            anchors.verticalCenter: parent.verticalCenter
            spacing: 1

            Row {
                spacing: 6

                Text {
                    text: root.nickname
                    color: Theme.onSurfaceColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("bodyMedium").size
                    elide: Text.ElideRight
                    width: Math.min(implicitWidth, parent.parent.width - 40)
                }

                Text {
                    visible: root.isSelf
                    text: "（我）"
                    color: Theme.onSurfaceVariantColor
                    font.family: Theme.fontFamily
                    font.pixelSize: Theme.textStyle("labelSmall").size
                    anchors.verticalCenter: parent.verticalCenter
                }
            }

            Text {
                width: parent.width
                text: root.controllerMember ? "控制播放与房间设置" : "跟随控制者同步播放"
                color: Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
                elide: Text.ElideRight
            }
        }

        Row {
            id: roleChip

            anchors.verticalCenter: parent.verticalCenter
            spacing: 4

            NeriIcon {
                name: root.controllerMember ? "crown" : "person"
                iconSize: 14
                color: root.controllerMember ? Theme.tertiary : Theme.onSurfaceVariantColor
                anchors.verticalCenter: parent.verticalCenter
            }

            Text {
                text: root.controllerMember ? "控制者" : "听众"
                color: root.controllerMember ? Theme.tertiary : Theme.onSurfaceVariantColor
                font.family: Theme.fontFamily
                font.pixelSize: Theme.textStyle("labelSmall").size
                anchors.verticalCenter: parent.verticalCenter
            }
        }
    }
}
