package dev.k230.mentor_app.protection.security

import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.security.GeneralSecurityException
import java.security.MessageDigest
import android.util.Base64

class SecurityFailure(val code: String) : GeneralSecurityException(code)

object StrictJson {
    private val gson = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    fun parse(bytes: ByteArray, maxBytes: Int = 24576): JsonObject {
        ensure(bytes.isNotEmpty() && bytes.size <= maxBytes, "bounds")
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) { throw SecurityFailure("malformed_json") }
        ensure(!text.contains('\u0000') && !text.contains('\ufeff'), "malformed_json")
        val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
        var tokens = 0
        fun read(depth: Int): JsonElement {
            ensure(depth <= 10 && ++tokens <= 4096, "bounds")
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    val obj = JsonObject()
                    reader.beginObject()
                    while (reader.hasNext()) {
                        ensure(obj.size() < 32 && ++tokens <= 4096, "bounds")
                        val key = reader.nextName()
                        ensure(!obj.has(key), "malformed_json")
                        obj.add(key, read(depth + 1))
                    }
                    reader.endObject()
                    obj
                }
                JsonToken.BEGIN_ARRAY -> {
                    val arr = JsonArray()
                    reader.beginArray()
                    while (reader.hasNext()) {
                        ensure(arr.size() < 32, "bounds")
                        arr.add(read(depth + 1))
                    }
                    reader.endArray()
                    arr
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString())
                JsonToken.NUMBER -> {
                    val lexical = reader.nextString()
                    val number = lexical.toDoubleOrNull()
                    ensure(number != null && number.isFinite(), "bounds")
                    com.google.gson.JsonParser.parseString(lexical)
                }
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> throw SecurityFailure("malformed_json")
            }
        }
        try {
            val root = read(0)
            ensure(root.isJsonObject && reader.peek() == JsonToken.END_DOCUMENT, "malformed_json")
            return root.asJsonObject
        } catch (e: SecurityFailure) { throw e }
        catch (_: Exception) { throw SecurityFailure("malformed_json") }
        finally { reader.close() }
    }

    // Signed public records/envelopes contain ASCII strings and integral v only.
    fun canonical(obj: JsonObject): ByteArray {
        fun emit(value: JsonElement): String = when {
            value.isJsonNull -> "null"
            value.isJsonObject -> value.asJsonObject.keySet().sorted().joinToString(",", "{", "}") {
                ensure(it.all { c -> c.code in 32..126 }, "bounds")
                gson.toJson(it) + ":" + emit(value.asJsonObject[it])
            }
            value.isJsonArray -> value.asJsonArray.joinToString(",", "[", "]") { emit(it) }
            value.asJsonPrimitive.isString -> {
                ensure(value.asString.all { it.code in 32..126 }, "bounds")
                gson.toJson(value.asString)
            }
            value.asJsonPrimitive.isBoolean -> value.asBoolean.toString()
            else -> {
                ensure(value.asString.matches(Regex("0|[1-9][0-9]*")), "bounds")
                value.asString
            }
        }
        return emit(obj).toByteArray(Charsets.UTF_8)
    }

    fun json(obj: JsonObject): ByteArray = gson.toJson(obj).toByteArray(Charsets.UTF_8)
    fun exact(obj: JsonObject, vararg keys: String) = ensure(obj.keySet() == keys.toSet(), "invalid_envelope")
    fun string(obj: JsonObject, key: String): String {
        val value = obj[key]
        ensure(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString, "invalid_envelope")
        return value.asString
    }
    fun int(obj: JsonObject, key: String): Int {
        val value = obj[key]
        ensure(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isNumber &&
            value.asString.matches(Regex("0|[1-9][0-9]*")), "invalid_envelope")
        return value.asString.toIntOrNull() ?: throw SecurityFailure("bounds")
    }
    fun decimal(obj: JsonObject, key: String, positive: Boolean = false): Long {
        val text = string(obj, key)
        ensure(text.matches(Regex("0|[1-9][0-9]{0,18}")), "bounds")
        val value = text.toLongOrNull() ?: throw SecurityFailure("bounds")
        ensure(!positive || value >= 1, "bounds")
        return value
    }
    fun uuid(value: String): String {
        ensure(value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")), "bounds")
        return value
    }
    fun hex(value: String): String {
        ensure(value.matches(Regex("[0-9a-f]{64}")), "bounds")
        return value
    }
    fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
    fun ensure(condition: Boolean, code: String) { if (!condition) throw SecurityFailure(code) }
}

interface Base64Codec {
    fun encode(bytes: ByteArray): String
    fun decode(text: String): ByteArray
}

object AndroidBase64 : Base64Codec {
    private const val FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    override fun encode(bytes: ByteArray): String = Base64.encodeToString(bytes, FLAGS)
    override fun decode(text: String): ByteArray = Base64.decode(text, FLAGS)
}

fun Base64Codec.bounded(text: String, min: Int, max: Int): ByteArray {
    StrictJson.ensure(text.length <= (max * 4 + 2) / 3 && text.matches(Regex("[A-Za-z0-9_-]+")), "bounds")
    val bytes = try { decode(text) } catch (_: Exception) { throw SecurityFailure("bounds") }
    StrictJson.ensure(bytes.size in min..max && encode(bytes) == text, "bounds")
    return bytes
}
