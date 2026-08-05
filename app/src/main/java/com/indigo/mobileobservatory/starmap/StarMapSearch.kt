package com.indigo.mobileobservatory.starmap

import org.json.JSONArray

object StarMapSearch {
    /**
     * Script that asks the engine to resolve and select one of [designations].
     * Returns `true` from JS when the engine owns the object, `false` when the
     * caller has to fall back to centering on catalog coordinates.
     */
    fun selectScript(designations: List<String>): String {
        val payload = JSONArray(designations).toString()
        return "window.MercStarMap && " +
            "window.MercStarMap.selectByDesignations(${quote(payload)});"
    }

    /** JS string literal for [value], safe to inline into an eval'd script. */
    fun quote(value: String): String {
        val escaped = StringBuilder(value.length + 2)
        escaped.append('"')
        value.forEach { ch ->
            when (ch) {
                '\\' -> escaped.append("\\\\")
                '"' -> escaped.append("\\\"")
                '\n' -> escaped.append("\\n")
                '\r' -> escaped.append("\\r")
                '\t' -> escaped.append("\\t")
                '<' -> escaped.append("\\u003c")
                '>' -> escaped.append("\\u003e")
                '&' -> escaped.append("\\u0026")
                else -> if (ch < ' ') {
                    escaped.append("\\u%04x".format(ch.code))
                } else {
                    escaped.append(ch)
                }
            }
        }
        escaped.append('"')
        return escaped.toString()
    }
}
