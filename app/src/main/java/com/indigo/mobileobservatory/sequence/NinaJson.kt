package com.indigo.mobileobservatory.sequence

import org.json.JSONArray
import org.json.JSONObject

/**
 * One NINA sequence object. [type] is the raw `$type` string.
 * [fields] keeps every other property, including ones this app does not execute.
 */
class NinaNode(
    val type: String,
    val id: String?,
    val fields: LinkedHashMap<String, NinaValue> = LinkedHashMap()
) {
    val className: String get() = classNameOf(type)
}

sealed class NinaValue {
    data object Null : NinaValue()
    data class Bool(val value: Boolean) : NinaValue()
    data class Num(val value: Double, val integral: Boolean) : NinaValue()
    data class Text(val value: String) : NinaValue()
    data class Ref(val id: String) : NinaValue()
    data class Obj(val node: NinaNode) : NinaValue()
    data class Collection(val type: String, val id: String?, val values: List<NinaValue>) : NinaValue()
    data class Array(val values: List<NinaValue>) : NinaValue()
}

fun parseNinaSequence(json: String): NinaNode {
    val root = parseAny(JSONObject(json))
    val node = (root as? NinaValue.Obj)?.node
        ?: throw IllegalArgumentException("Sequence root is not a JSON object")
    if (node.type.isEmpty()) throw IllegalArgumentException("Sequence root is missing ${'$'}type")
    return node
}

fun NinaNode.toJson(indent: Int = 2): String = writeNode(this).toString(indent)

fun classNameOf(type: String): String {
    val fullName = type.substringBefore(',').trim()
    return fullName.substringAfterLast('.')
}

fun NinaNode.collectionNodes(field: String): List<NinaNode> {
    val collection = fields[field] as? NinaValue.Collection ?: return emptyList()
    return collection.values.mapNotNull { value ->
        when (value) {
            is NinaValue.Obj -> value.node
            else -> null
        }
    }
}

fun NinaNode.childItems(): List<NinaNode> = collectionNodes("Items")

fun NinaNode.intField(name: String): Int? = when (val value = fields[name]) {
    is NinaValue.Num -> value.value.toInt()
    is NinaValue.Text -> value.value.toIntOrNull()
    else -> null
}

fun NinaNode.doubleField(name: String): Double? = when (val value = fields[name]) {
    is NinaValue.Num -> value.value
    is NinaValue.Text -> value.value.toDoubleOrNull()
    else -> null
}

fun NinaNode.textField(name: String): String? = (fields[name] as? NinaValue.Text)?.value

const val NINA_EXPRESSION_TYPE = "NINA.Sequencer.Logic.Expression, NINA.Sequencer"

fun expressionDefinition(node: NinaNode, name: String): String? {
    val fromExpr = (node.fields["${name}Expression"] as? NinaValue.Obj)?.node?.textField("Definition")
    if (!fromExpr.isNullOrBlank()) return fromExpr
    val fromDefinition = node.textField("${name}Definition")
    if (!fromDefinition.isNullOrBlank()) return fromDefinition
    return when (val value = node.fields[name]) {
        is NinaValue.Num -> if (value.integral) value.value.toLong().toString() else value.value.toString()
        is NinaValue.Text -> value.value
        else -> null
    }
}

fun expressionNumber(node: NinaNode, name: String): Double? =
    expressionDefinition(node, name)?.toDoubleOrNull() ?: node.doubleField(name)

fun expressionIsUnsupported(node: NinaNode, name: String): Boolean {
    val definition = expressionDefinition(node, name) ?: return false
    if (definition.isBlank()) return false
    return definition.toDoubleOrNull() == null
}

fun nodeHasUnsupportedExpression(node: NinaNode): Boolean {
    node.fields.keys.filter { it.endsWith("Definition") }.forEach { key ->
        val def = node.textField(key) ?: return@forEach
        if (def.isNotBlank() && def.toDoubleOrNull() == null) return true
    }
    node.fields.keys.filter { it.endsWith("Expression") }.forEach { key ->
        val def = (node.fields[key] as? NinaValue.Obj)?.node?.textField("Definition") ?: return@forEach
        if (def.isNotBlank() && def.toDoubleOrNull() == null) return true
    }
    return false
}

fun putExpression(fields: LinkedHashMap<String, NinaValue>, name: String, value: Double, id: String? = null) {
    val integral = value % 1.0 == 0.0 && !value.isNaN() && !value.isInfinite()
    val definition = if (integral) value.toLong().toString() else value.toString()
    putExpression(fields, name, definition, NinaValue.Num(value, integral), id)
}

fun putExpression(
    fields: LinkedHashMap<String, NinaValue>,
    name: String,
    definition: String,
    snapshot: NinaValue? = null,
    id: String? = null
) {
    fields["${name}Expression"] = NinaValue.Obj(
        NinaNode(
            type = NINA_EXPRESSION_TYPE,
            id = id,
            fields = linkedMapOf("Definition" to NinaValue.Text(definition))
        )
    )
    val number = definition.toDoubleOrNull()
    fields[name] = snapshot ?: if (number != null) {
        NinaValue.Num(number, integral = number % 1.0 == 0.0 && !number.isNaN() && !number.isInfinite())
    } else {
        NinaValue.Text(definition)
    }
    fields["${name}Definition"] = NinaValue.Text(definition)
}

private fun parseAny(value: Any?): NinaValue = when (value) {
    null, JSONObject.NULL -> NinaValue.Null
    is Boolean -> NinaValue.Bool(value)
    is Int -> NinaValue.Num(value.toDouble(), integral = true)
    is Long -> NinaValue.Num(value.toDouble(), integral = true)
    is Float -> parseFloating(value.toDouble())
    is Double -> parseFloating(value)
    is java.math.BigInteger -> NinaValue.Num(value.toDouble(), integral = true)
    is java.math.BigDecimal -> parseFloating(value.toDouble())
    is String -> NinaValue.Text(value)
    is JSONArray -> NinaValue.Array((0 until value.length()).map { parseAny(value.get(it)) })
    is JSONObject -> parseObject(value)
    else -> NinaValue.Text(value.toString())
}

private fun parseFloating(value: Double): NinaValue.Num =
    NinaValue.Num(value, integral = value % 1.0 == 0.0 && !value.isNaN() && !value.isInfinite())

private fun parseObject(obj: JSONObject): NinaValue {
    if (obj.has("\$ref")) return NinaValue.Ref(obj.getString("\$ref"))
    if (obj.has("\$values")) {
        val values = obj.getJSONArray("\$values")
        return NinaValue.Collection(
            type = obj.optString("\$type", ""),
            id = obj.optString("\$id", "").ifEmpty { null },
            values = (0 until values.length()).map { parseAny(values.get(it)) }
        )
    }
    val fields = LinkedHashMap<String, NinaValue>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        if (key == "\$id" || key == "\$type") continue
        fields[key] = parseAny(obj.get(key))
    }
    return NinaValue.Obj(
        NinaNode(
            type = obj.optString("\$type", ""),
            id = obj.optString("\$id", "").ifEmpty { null },
            fields = fields
        )
    )
}

private fun writeNode(node: NinaNode): JSONObject {
    val obj = JSONObject()
    if (node.id != null) obj.put("\$id", node.id)
    obj.put("\$type", node.type)
    for ((key, value) in node.fields) {
        obj.put(key, writeValue(value))
    }
    return obj
}

private fun writeValue(value: NinaValue): Any = when (value) {
    NinaValue.Null -> JSONObject.NULL
    is NinaValue.Bool -> value.value
    is NinaValue.Num -> if (value.integral) value.value.toLong() else value.value
    is NinaValue.Text -> value.value
    is NinaValue.Ref -> JSONObject().put("\$ref", value.id)
    is NinaValue.Obj -> writeNode(value.node)
    is NinaValue.Array -> JSONArray().apply { value.values.forEach { put(writeValue(it)) } }
    is NinaValue.Collection -> JSONObject().apply {
        if (value.id != null) put("\$id", value.id)
        if (value.type.isNotEmpty()) put("\$type", value.type)
        put("\$values", JSONArray().apply { value.values.forEach { put(writeValue(it)) } })
    }
}
