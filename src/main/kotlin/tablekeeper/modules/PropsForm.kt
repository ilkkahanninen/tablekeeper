package tablekeeper.modules

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Generic edit form generated from a type's JSON Schema, so new game modules
 * get working forms with no UI code.
 *
 *   {"type":"integer","minimum":0,"maximum":4}         -> kind "int"
 *   {"enum":[...]}                                     -> kind "select"
 *   {"type":"array","items":{"enum":[...]}}            -> kind "multi" (checkboxes)
 *   {"type":"boolean"}                                 -> kind "bool"
 *   {"type":"string","x-widget":"textarea"}            -> kind "textarea"
 *   {"type":"string"}                                  -> kind "text"
 *
 * "x-enumDescriptions": {"Fragile": "…"} on a select/multi field becomes a hover hint per option.
 * "x-enumLabels": {"Professional": "d8 Professional"} shows an option differently from its stored value.
 * "x-hideIf": {"category": ["Miscellaneous"]} hides a field while another field has one of those values;
 * "x-showIf": {"tags": ["Limited X"]} shows it only then. A hidden field is dropped on save and left off print cards.
 * "x-wide": true gives a field a full-width row of its own.
 * "x-row": "Beat 1" puts consecutive fields with the same value on one line, labelled "Beat 1", on the page and on cards.
 *
 * Kinds are plain strings so Pebble templates can compare them directly.
 */
data class FormField(
    val name: String,
    val label: String,
    val kind: String,
    val options: List<String> = emptyList(),
    val min: Int? = null,
    val max: Int? = null,
    /** Hover hint per option, from "x-enumDescriptions". */
    val hints: Map<String, String> = emptyMap(),
    /** Display text per option, from "x-enumLabels"; options without one show as stored. */
    val labels: Map<String, String> = emptyMap(),
    /** From "x-hideIf": other field name -> values that hide this field. */
    val hideIf: Map<String, List<String>> = emptyMap(),
    /** From "x-showIf": other field name -> values that must be present for this field to show. */
    val showIf: Map<String, List<String>> = emptyMap(),
    /** Whether [hideIf] / [showIf] hide this field for the current props. */
    val hidden: Boolean = false,
    /** From "x-row": label of the line this field shares with its neighbours; null = its own line. */
    val row: String? = null,
    /** From "x-wide": spans the whole form width, on its own row. */
    val wide: Boolean = false,
    /** String for scalar kinds, List<String> for "multi", Boolean for "bool". */
    val value: Any?,
) {
    val inputName: String get() = "prop.$name"
    val conditional: Boolean get() = hideIf.isNotEmpty() || showIf.isNotEmpty()

    /** [hideIf] and [showIf] as JSON, for the form's script to re-check on change. */
    val conditionsJson: String get() = GameModules.mapper.writeValueAsString(mapOf("hideIf" to hideIf, "showIf" to showIf))

    /** Current value as display text: labels applied, lists joined, true as "yes", unset as "". */
    val text: String get() = format { labels[it] ?: it }

    /** Current value as stored, e.g. "d8" where [text] is "d8 Professional". */
    val storedText: String get() = format { it }

    private fun format(show: (String) -> String): String = when (val v = value) {
        is List<*> -> v.joinToString(", ") { show(it.toString()) }
        is Boolean -> if (v) "yes" else ""
        else -> v?.toString().orEmpty().let { if (it.isEmpty()) it else show(it) }
    }
}

/** One line of the form or card: several fields under [label] ("x-row"), or a single field when [label] is null. */
data class FormRow(val label: String?, val fields: List<FormField>)

object PropsForm {
    private val json = JsonNodeFactory.instance

    fun fields(schema: JsonNode?, props: Map<String, Any?>): List<FormField> {
        val properties = schema?.get("properties") ?: return emptyList()
        return properties.fields().asSequence().map { (name, s) ->
            fun condition(key: String) = s.get(key)?.fields()?.asSequence()?.associate { (k, v) ->
                k to (if (v.isArray) v.map { it.asText() } else listOf(v.asText()))
            }.orEmpty()
            val hideIf = condition("x-hideIf")
            val showIf = condition("x-showIf")
            val hidden = matches(hideIf, props) || (showIf.isNotEmpty() && !matches(showIf, props))
            field(name, s, props).copy(hideIf = hideIf, showIf = showIf, hidden = hidden, row = s.get("x-row")?.asText(),
                wide = s.get("x-wide")?.asBoolean() == true)
        }.toList()
    }

    /** Groups consecutive fields sharing an "x-row" into one row; other fields get a row each, with no label. */
    fun rows(fields: List<FormField>): List<FormRow> = fields.fold(mutableListOf<FormRow>()) { rows, f ->
        val last = rows.lastOrNull()
        if (f.row != null && last?.label == f.row) rows[rows.lastIndex] = last.copy(fields = last.fields + f)
        else rows += FormRow(f.row, listOf(f))
        rows
    }

    /** True if any field named in [condition] currently has one of its listed values. */
    private fun matches(condition: Map<String, List<String>>, props: Map<String, Any?>) = condition.any { (other, values) ->
        val current = props[other]
        (if (current is List<*>) current else listOf(current)).any { it?.toString() in values }
    }

    /** One form field from its JSON Schema; widget kind per the table above. */
    private fun field(name: String, s: JsonNode, props: Map<String, Any?>): FormField {
        val label = s.get("title")?.asText() ?: name.replaceFirstChar { it.uppercase() }
        val current = props[name]
        fun textMap(key: String) = s.get(key)?.fields()?.asSequence()?.associate { (k, v) -> k to v.asText() }.orEmpty()
        val hints = textMap("x-enumDescriptions")
        val labels = textMap("x-enumLabels")
        return when {
            s.has("enum") -> FormField(name, label, "select", s.get("enum").map { it.asText() }, hints = hints, labels = labels,
                value = current?.toString() ?: "")
            s.get("type")?.asText() == "array" && s.get("items")?.has("enum") == true ->
                FormField(name, label, "multi", s.get("items").get("enum").map { it.asText() }, hints = hints, labels = labels,
                    value = (current as? List<*>)?.map { it.toString() } ?: emptyList<String>())
            s.get("type")?.asText() in setOf("integer", "number") ->
                FormField(name, label, "int", min = s.get("minimum")?.asInt(), max = s.get("maximum")?.asInt(),
                    value = current?.toString() ?: "")
            s.get("type")?.asText() == "boolean" -> FormField(name, label, "bool", value = current == true)
            s.get("x-widget")?.asText() == "textarea" -> FormField(name, label, "textarea", value = current?.toString() ?: "")
            else -> FormField(name, label, "text", value = current?.toString() ?: "")
        }
    }

    /**
     * Builds the props JSON from posted form params (`prop.tier=2`, `prop.domains=Occult&prop.domains=Wild`).
     * Type coercion only; constraints (min/max, enums) are checked by the module's schema afterwards.
     */
    fun parse(schema: JsonNode?, params: Map<String, List<String>>): Pair<ObjectNode, List<String>> {
        val out = json.objectNode()
        val errors = mutableListOf<String>()
        for (field in fields(schema, emptyMap())) {
            val raw = params[field.inputName].orEmpty()
            val first = raw.firstOrNull()?.trim().orEmpty()
            when (field.kind) {
                "multi" -> out.putArray(field.name).also { arr -> raw.filter { it.isNotBlank() }.forEach { arr.add(it) } }
                "bool" -> out.put(field.name, raw.isNotEmpty())
                "int" -> if (first.isNotEmpty()) {
                    first.toIntOrNull()?.let { out.put(field.name, it) } ?: errors.add("${field.label} must be a whole number")
                }
                else -> if (first.isNotEmpty()) out.put(field.name, first)
            }
        }
        // Drop fields hidden by the submitted values (e.g. quality on Miscellaneous equipment).
        @Suppress("UNCHECKED_CAST")
        val submitted = GameModules.mapper.convertValue(out, Map::class.java) as Map<String, Any?>
        fields(schema, submitted).filter { it.hidden }.forEach { out.remove(it.name) }
        return out to errors
    }
}
