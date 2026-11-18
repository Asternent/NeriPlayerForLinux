package moe.ouom.neriplayer.desktop.backend

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/** IPC 线协议使用的 JSON 配置。 */
val WireJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    isLenient = true
    allowSpecialFloatingPointValues = true
    allowStructuredMapKeys = true
}

/** 用 kotlinx.serialization 把任意可序列化对象编码成 JsonElement。 */
inline fun <reified T> encodeJson(value: T): JsonElement =
    WireJson.encodeToJsonElement(serializer<T>(), value)

/** 把 Map/List/基础类型混合的数据递归编码成 JsonElement（用于手写的事件负载）。 */
fun encodeAny(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    is Map<*, *> -> buildJsonObject {
        for ((key, item) in value) put(key.toString(), encodeAny(item))
    }
    is Iterable<*> -> buildJsonArray { for (item in value) add(encodeAny(item)) }
    else -> JsonPrimitive(value.toString())
}

fun jsonObjectOf(vararg pairs: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((key, value) in pairs) put(key, encodeAny(value))
}

fun JsonObject.stringOrNull(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.longOrNull(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

fun JsonObject.intOrNull(key: String): Int? =
    (this[key] as? JsonPrimitive)?.content?.toIntOrNull()

fun JsonObject.doubleOrNull(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

fun JsonObject.boolOrNull(key: String): Boolean? =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()

fun JsonObject.arrayOrNull(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.objectOrNull(key: String): JsonObject? = this[key] as? JsonObject

/** 协议错误：抛出后由 CoreServer 转成 {"id":..,"error":{code,message}}。 */
class ProtocolException(val code: String, override val message: String) : RuntimeException(message)

fun badRequest(message: String): Nothing = throw ProtocolException("bad_request", message)
