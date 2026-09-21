#!/usr/bin/env python3
"""NeriPlayer 系统托盘桥接进程（StatusNotifierItem + DBusMenu）。

桌面（GNOME / KDE / XFCE 等）通过这套 D-Bus 协议显示托盘图标与菜单：
菜单内容和点击事件都转交给 NeriPlayer 主程序（总线名 org.neriplayer.Tray 上的
org.neriplayer.Tray 接口），因此菜单完全由桌面渲染，不存在「弹出来的窗口收不到点击」的问题。

为什么要单独一个进程：JVM 侧 dbus-java 编组「结构体返回值」时会多包一层括号
（GetLayout 需要 (u(ia{sv}av))，它发出 ((u(ia{sv}av)))），GNOME 会据此认为菜单为空。
这里用 GIO 实现，编组交给 GLib，没有这个问题。

用法：
  neriplayer-tray-bridge.py --app org.neriplayer.Tray \
      --service org.kde.StatusNotifierItem-<pid>-1 --icon /path/to/icon.png
"""

import argparse
import json
import os
import sys

import gi

gi.require_version("Gio", "2.0")
gi.require_version("GLib", "2.0")
gi.require_version("GdkPixbuf", "2.0")
from gi.repository import Gio, GLib, GdkPixbuf  # noqa: E402

APP_PATH = "/org/neriplayer/Tray"
APP_IFACE = "org.neriplayer.Tray"
ITEM_PATH = "/StatusNotifierItem"
MENU_PATH = "/MenuBar"

SNI_XML = """
<node>
  <interface name="org.kde.StatusNotifierItem">
    <property name="Category" type="s" access="read"/>
    <property name="Id" type="s" access="read"/>
    <property name="Title" type="s" access="read"/>
    <property name="Status" type="s" access="read"/>
    <property name="IconThemePath" type="s" access="read"/>
    <property name="Menu" type="o" access="read"/>
    <property name="ItemIsMenu" type="b" access="read"/>
    <property name="IconName" type="s" access="read"/>
    <property name="IconPixmap" type="a(iiay)" access="read"/>
    <method name="Activate">
      <arg name="x" type="i" direction="in"/>
      <arg name="y" type="i" direction="in"/>
    </method>
    <method name="SecondaryActivate">
      <arg name="x" type="i" direction="in"/>
      <arg name="y" type="i" direction="in"/>
    </method>
    <method name="Scroll">
      <arg name="delta" type="i" direction="in"/>
      <arg name="orientation" type="s" direction="in"/>
    </method>
  </interface>
</node>
"""

MENU_XML = """
<node>
  <interface name="com.canonical.dbusmenu">
    <method name="GetLayout">
      <arg name="parentId" type="i" direction="in"/>
      <arg name="recursionDepth" type="i" direction="in"/>
      <arg name="propertyNames" type="as" direction="in"/>
      <arg name="revision" type="u" direction="out"/>
      <arg name="layout" type="(ia{sv}av)" direction="out"/>
    </method>
    <method name="GetGroupProperties">
      <arg name="ids" type="ai" direction="in"/>
      <arg name="propertyNames" type="as" direction="in"/>
      <arg name="properties" type="a(ia{sv})" direction="out"/>
    </method>
    <method name="GetProperty">
      <arg name="id" type="i" direction="in"/>
      <arg name="name" type="s" direction="in"/>
      <arg name="value" type="v" direction="out"/>
    </method>
    <method name="Event">
      <arg name="id" type="i" direction="in"/>
      <arg name="eventId" type="s" direction="in"/>
      <arg name="data" type="v" direction="in"/>
      <arg name="timestamp" type="u" direction="in"/>
    </method>
    <method name="EventGroup">
      <arg name="events" type="a(isvu)" direction="in"/>
      <arg name="idErrors" type="ai" direction="out"/>
      <arg name="idSuccesses" type="ai" direction="out"/>
    </method>
    <method name="AboutToShow">
      <arg name="id" type="i" direction="in"/>
      <arg name="needUpdate" type="b" direction="out"/>
    </method>
    <signal name="LayoutUpdated">
      <arg name="revision" type="u"/>
      <arg name="parent" type="i"/>
    </signal>
    <signal name="ItemsPropertiesUpdated">
      <arg name="updatedProps" type="a(ia{sv})"/>
      <arg name="removedProps" type="a(ias)"/>
    </signal>
    <property name="Version" type="u" access="read"/>
    <property name="TextDirection" type="s" access="read"/>
    <property name="Status" type="s" access="read"/>
    <property name="IconThemePath" type="as" access="read"/>
  </interface>
</node>
"""


def log(message):
    print(f"[bridge] {message}", flush=True)


class TrayBridge:
    def __init__(self, app_name, service_name, icon_path):
        self.app_name = app_name
        self.service_name = service_name
        self.icon_path = icon_path
        self.connection = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self.revision = 1
        self.items = []
        self.last_model_json = ""
        self.pixmap = self._load_pixmap(icon_path)

    # ---------------- 与主程序通信 ----------------
    def _call_app(self, method, params=None, reply_type=None):
        try:
            return self.connection.call_sync(
                self.app_name, APP_PATH, APP_IFACE, method, params, reply_type,
                Gio.DBusCallFlags.NONE, 1500, None)
        except Exception as error:
            log(f"调用主程序 {method} 失败: {error}")
            return None

    def app_title(self):
        reply = self._call_app("Title", None, GLib.VariantType.new("(s)"))
        return reply.unpack()[0] if reply else "NeriPlayer"

    def app_model(self):
        reply = self._call_app("MenuModel", None, GLib.VariantType.new("(s)"))
        if reply is None:
            return None
        try:
            return json.loads(reply.unpack()[0])
        except Exception as error:
            log(f"解析菜单模型失败: {error}")
            return None

    # ---------------- 图标 ----------------
    def _load_pixmap(self, path):
        try:
            pixbuf = GdkPixbuf.Pixbuf.new_from_file_at_size(path, 32, 32)
            channels = pixbuf.get_n_channels()
            rowstride = pixbuf.get_rowstride()
            pixels = pixbuf.get_pixels()
            width, height = pixbuf.get_width(), pixbuf.get_height()
            has_alpha = pixbuf.get_has_alpha()
            data = bytearray()
            for y in range(height):
                for x in range(width):
                    offset = y * rowstride + x * channels
                    r = pixels[offset]
                    g = pixels[offset + 1]
                    b = pixels[offset + 2]
                    a = pixels[offset + 3] if has_alpha else 255
                    data += bytes((a, r, g, b))
            return [(width, height, list(data))]
        except Exception as error:
            log(f"读取图标失败: {error}")
            return []

    # ---------------- SNI ----------------
    def _sni_properties(self):
        return {
            "Category": GLib.Variant("s", "ApplicationStatus"),
            "Id": GLib.Variant("s", "neriplayer"),
            "Title": GLib.Variant("s", self.app_title()),
            "Status": GLib.Variant("s", "Active"),
            "IconThemePath": GLib.Variant("s", ""),
            "Menu": GLib.Variant("o", MENU_PATH),
            "ItemIsMenu": GLib.Variant("b", True),
            "IconName": GLib.Variant("s", ""),
            "IconPixmap": GLib.Variant("a(iiay)", self.pixmap),
        }

    def on_sni_call(self, _conn, _sender, _path, interface, method, params, invocation):
        if interface == "org.freedesktop.DBus.Properties":
            if method == "Get":
                name = params.unpack()[1]
                value = self._sni_properties().get(name, GLib.Variant("s", ""))
                invocation.return_value(GLib.Variant("(v)", (value,)))
            elif method == "GetAll":
                invocation.return_value(GLib.Variant("(a{sv})", (self._sni_properties(),)))
            else:
                invocation.return_value(None)
            return
        if method == "Activate":
            self._call_app("Activate", None, None)
        elif method == "SecondaryActivate":
            self._call_app("SecondaryActivate", None, None)
        elif method == "Scroll":
            delta, orientation = params.unpack()
            self._call_app("Scroll", GLib.Variant("(is)", (delta, orientation)), None)
        invocation.return_value(None)

    # ---------------- DBusMenu ----------------
    def _refresh_items(self):
        model = self.app_model()
        if model is None:
            return
        text = json.dumps(model, ensure_ascii=False, sort_keys=True)
        if text == self.last_model_json:
            return
        self.last_model_json = text
        self.items = model
        self.revision += 1
        try:
            self.connection.emit_signal(
                None, MENU_PATH, "com.canonical.dbusmenu", "LayoutUpdated",
                GLib.Variant("(ui)", (self.revision, 0)))
        except Exception as error:
            log(f"发送菜单更新失败: {error}")

    def _item_props(self, item):
        props = {
            "label": GLib.Variant("s", item.get("label", "")),
            "enabled": GLib.Variant("b", bool(item.get("enabled", True))),
            "visible": GLib.Variant("b", bool(item.get("visible", True))),
            "type": GLib.Variant("s", item.get("type", "standard")),
        }
        if item.get("toggle"):
            props["toggle-type"] = GLib.Variant("s", item["toggle"])
            props["toggle-state"] = GLib.Variant("i", 1 if item.get("checked") else 0)
        return props

    def _layout_tuple(self):
        children = []
        for item in self.items:
            props = {
                "label": GLib.Variant("s", item.get("label", "")),
                "enabled": GLib.Variant("b", bool(item.get("enabled", True))),
                "visible": GLib.Variant("b", bool(item.get("visible", True))),
                "type": GLib.Variant("s", item.get("type", "standard")),
            }
            if item.get("toggle"):
                props["toggle-type"] = GLib.Variant("s", item["toggle"])
                props["toggle-state"] = GLib.Variant("i", 1 if item.get("checked") else 0)
            children.append(GLib.Variant("(ia{sv}av)", (int(item["id"]), props, [])))
        root_props = {"children-display": GLib.Variant("s", "submenu")}
        return (0, root_props, children)

    def _layout(self):
        return GLib.Variant("(ia{sv}av)", self._layout_tuple())

    def on_menu_call(self, _conn, _sender, _path, interface, method, params, invocation):
        if interface == "org.freedesktop.DBus.Properties":
            if method == "GetAll":
                props = {
                    "Version": GLib.Variant("u", 3),
                    "TextDirection": GLib.Variant("s", "ltr"),
                    "Status": GLib.Variant("s", "normal"),
                    "IconThemePath": GLib.Variant("as", []),
                }
                invocation.return_value(GLib.Variant("(a{sv})", (props,)))
            else:
                invocation.return_value(None)
            return

        if method == "GetLayout":
            self._refresh_items()
            invocation.return_value(GLib.Variant("(u(ia{sv}av))", (self.revision, self._layout_tuple())))
            return
        if method == "Event":
            item_id, event_id, _data, _timestamp = params.unpack()
            if event_id == "clicked":
                self._call_app("MenuEvent", GLib.Variant("(is)", (int(item_id), event_id)), None)
                self._refresh_items()
            invocation.return_value(None)
            return
        if method == "EventGroup":
            events = params.unpack()[0]
            handled = []
            for item_id, event_id, _data, _timestamp in events:
                if event_id == "clicked":
                    self._call_app("MenuEvent", GLib.Variant("(is)", (int(item_id), event_id)), None)
                    handled.append(int(item_id))
            invocation.return_value(GLib.Variant("(aiai)", ([], handled)))
            return
        if method == "AboutToShow":
            self._refresh_items()
            invocation.return_value(GLib.Variant("(b)", (False,)))
            return
        if method == "GetGroupProperties":
            ids = params.unpack()[0]
            entries = []
            for item in self.items:
                item_id = int(item["id"])
                if ids and item_id not in ids:
                    continue
                entries.append((item_id, self._item_props(item)))
            invocation.return_value(GLib.Variant("(a(ia{sv}))", (entries,)))
            return
        if method == "GetProperty":
            item_id, name = params.unpack()
            value = GLib.Variant("s", "")
            for item in self.items:
                if int(item["id"]) == item_id:
                    value = self._item_props(item).get(name, value)
                    break
            invocation.return_value(GLib.Variant("(v)", (value,)))
            return
        invocation.return_value(None)

    # ---------------- 主循环 ----------------
    def run(self):
        stored = None
        if self.icon_path and os.path.isfile(self.icon_path):
            stored = self.pixmap
        else:
            log("图标文件不存在，使用空图标")

        # 用「对象路径」形式注册：桌面会用本连接的唯一名 + 该路径查询指示器，
        # 不需要等 well-known name 抢到手（避免注册时名字还没生效）。
        Gio.bus_own_name(
            Gio.BusType.SESSION, self.service_name, Gio.BusNameOwnerFlags.NONE,
            None, None, None)

        sni_info = Gio.DBusNodeInfo.new_for_xml(SNI_XML)
        self.connection.register_object(ITEM_PATH, sni_info.interfaces[0], self.on_sni_call, None, None)
        menu_info = Gio.DBusNodeInfo.new_for_xml(MENU_XML)
        self.connection.register_object(MENU_PATH, menu_info.interfaces[0], self.on_menu_call, None, None)

        self._refresh_items()

        try:
            self.connection.call_sync(
                "org.kde.StatusNotifierWatcher", "/StatusNotifierWatcher",
                "org.kde.StatusNotifierWatcher", "RegisterStatusNotifierItem",
                GLib.Variant("(s)", (ITEM_PATH,)), None,
                Gio.DBusCallFlags.NONE, 3000, None)
            log(f"已注册托盘指示器 {self.service_name}（菜单项 {len(self.items)} 个）")
        except Exception as error:
            log(f"注册状态栏指示器失败: {error}")
            return 1

        loop = GLib.MainLoop()

        def on_name_owner_changed(_conn, _sender, _path, _iface, _signal, params):
            name, _old, new = params.unpack()
            if name == self.app_name and not new:
                log("主程序已退出，桥接进程结束")
                loop.quit()

        self.connection.signal_subscribe(
            "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged",
            "/org/freedesktop/DBus", None, Gio.DBusSignalFlags.NONE, on_name_owner_changed)

        # 定期刷新菜单文案（播放/暂停、悬浮歌词开关等）
        GLib.timeout_add_seconds(2, lambda: (self._refresh_items(), True)[1])

        loop.run()
        return 0


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--app", default=APP_IFACE)
    parser.add_argument("--service", default="")
    parser.add_argument("--icon", default="")
    args = parser.parse_args()
    service = args.service or f"org.kde.StatusNotifierItem-{os.getpid()}-1"
    return TrayBridge(args.app, service, args.icon).run()


if __name__ == "__main__":
    sys.exit(main())
