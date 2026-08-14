package com.sagesearch.android.planner

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal

internal class StrictJsonObjectDecoder {
    fun decode(rawOutput: String): JsonObject {
        valueCount = 0
        val text = rawOutput.trim()
        if (text.length > MAX_OUTPUT_CHARACTERS) reject(PlannerRejection.OUTPUT_TOO_LARGE)
        if (!text.startsWith('{') || !text.endsWith('}')) reject(PlannerRejection.ROOT_NOT_OBJECT)
        try {
            JsonReader(StringReader(text)).use { reader ->
                reader.strictness = Strictness.STRICT
                reader.nestingLimit = MAX_NESTING_DEPTH
                val element = readElement(reader)
                if (reader.peek() != JsonToken.END_DOCUMENT) reject(PlannerRejection.MALFORMED_JSON)
                return element as? JsonObject ?: reject(PlannerRejection.ROOT_NOT_OBJECT)
            }
        } catch (invalid: InvalidPlannerOutput) {
            throw invalid
        } catch (_: Throwable) {
            reject(PlannerRejection.MALFORMED_JSON)
        }
    }

    private var valueCount = 0

    private fun readElement(reader: JsonReader): JsonElement {
        valueCount += 1
        if (valueCount > MAX_VALUES) reject(PlannerRejection.OUTPUT_TOO_LARGE)
        return when (reader.peek()) {
            JsonToken.BEGIN_OBJECT -> {
                reader.beginObject()
                val objectValue = JsonObject()
                val names = mutableSetOf<String>()
                while (reader.hasNext()) {
                    val name = reader.nextName()
                    if (!names.add(name)) reject(PlannerRejection.DUPLICATE_KEY)
                    objectValue.add(name, readElement(reader))
                }
                reader.endObject()
                objectValue
            }
            JsonToken.BEGIN_ARRAY -> {
                reader.beginArray()
                val array = JsonArray()
                while (reader.hasNext()) array.add(readElement(reader))
                reader.endArray()
                array
            }
            JsonToken.STRING -> JsonPrimitive(reader.nextString())
            JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
            JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
            JsonToken.NULL -> {
                reader.nextNull()
                JsonNull.INSTANCE
            }
            else -> reject(PlannerRejection.MALFORMED_JSON)
        }
    }

    companion object {
        private const val MAX_OUTPUT_CHARACTERS = 4_096
        private const val MAX_NESTING_DEPTH = 8
        private const val MAX_VALUES = 128
    }
}

internal class InvalidPlannerOutput(val reason: PlannerRejection) : RuntimeException()

internal fun reject(reason: PlannerRejection): Nothing = throw InvalidPlannerOutput(reason)
