package com.mtbanalyzer.viewer

import java.util.Locale

/**
 * Just enough JSON to emit the viewer API's payloads.
 *
 * Hand-rolled rather than org.json so the payload builders stay pure Kotlin and can be
 * covered by local unit tests — org.json is stubbed out in the JVM test runtime and throws
 * on every call.
 */
internal object Json {

    fun escape(value: String): String {
        val out = StringBuilder(value.length + 16)
        for (ch in value) {
            when (ch) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                '\b' -> out.append("\\b")
                '\u000C' -> out.append("\\f")
                // U+2028/U+2029 are valid JSON but break JavaScript string literals
                ' ', ' ' -> out.append(String.format(Locale.US, "\\u%04x", ch.code))
                else ->
                    if (ch < ' ') out.append(String.format(Locale.US, "\\u%04x", ch.code))
                    else out.append(ch)
            }
        }
        return out.toString()
    }

    fun string(value: String): String = "\"" + escape(value) + "\""

    fun error(message: String): String = "{\"error\":" + string(message) + "}"
}
