package com.seastreams.tv.domain.streams.format

import com.seastreams.tv.domain.streams.format.TemplateModifiers.isPresent
import com.seastreams.tv.domain.streams.format.TemplateModifiers.jsString
import java.util.concurrent.ConcurrentHashMap

/** A parsed template, ready to render against [TemplateFields.values]. */
class CompiledTemplate internal constructor(private val nodes: List<TemplateNode>) {

    /**
     * Renders, then (as AIOStreams does) drops blank lines and lines holding `{tools.removeLine}`, and turns
     * `{tools.newLine}` into line breaks.
     */
    fun render(values: Map<String, Map<String, Any?>>): String =
        TemplateRenderer.renderNodes(nodes, values, depth = 0)
            .split('\n')
            .filter { line -> line.isNotBlank() && !line.contains(REMOVE_LINE_SENTINEL) }
            .joinToString("\n")
            .replace(NEW_LINE_SENTINEL, "\n")
}

/** Renders template ASTs (AIOStreams `engine/compile.ts`). */
internal object TemplateRenderer {

    fun compile(template: String): CompiledTemplate = CompiledTemplate(TemplateParser.parse(template))

    private class Resolved(val result: Any? = null, val error: String? = null, val present: Boolean = false)

    private val modifierCache = ConcurrentHashMap<String, CompiledModifier>()
    private val branchCache = ConcurrentHashMap<String, List<TemplateNode>>()

    fun renderNodes(nodes: List<TemplateNode>, values: Map<String, Map<String, Any?>>, depth: Int): String {
        val out = StringBuilder()
        for (node in nodes) {
            if (out.length >= MAX_RENDER_LENGTH) break
            out.append(renderNode(node, values, depth))
        }
        return if (out.length > MAX_RENDER_LENGTH) out.substring(0, MAX_RENDER_LENGTH) else out.toString()
    }

    private fun renderNode(node: TemplateNode, values: Map<String, Map<String, Any?>>, depth: Int): String = when (node) {
        is TemplateNode.Raw -> node.text.replace("\\n", "\n")
        is TemplateNode.Tool -> if (node.tool == ToolKind.NEW_LINE) NEW_LINE_SENTINEL else REMOVE_LINE_SENTINEL
        is TemplateNode.Group -> renderGroup(node, values, depth)
        is TemplateNode.Expression -> renderExpression(node, values, depth)
    }

    /** Nothing renders when a bare expression in the group is absent; a conditional never suppresses it. */
    private fun renderGroup(group: TemplateNode.Group, values: Map<String, Map<String, Any?>>, depth: Int): String {
        val out = StringBuilder()
        for (child in group.nodes) {
            if (child is TemplateNode.Expression && child.check == null) {
                val resolved = resolveExpression(child, values)
                if (resolved.error == null && !resolved.present) return ""
            }
            out.append(renderNode(child, values, depth))
        }
        return out.toString()
    }

    private fun renderExpression(node: TemplateNode.Expression, values: Map<String, Map<String, Any?>>, depth: Int): String {
        val resolved = resolveExpression(node, values)
        resolved.error?.let { return it }
        val check = node.check ?: return jsString(resolved.result)
        val result = resolved.result
        if (!isPresent(result)) return check.absentTemplate?.let { branch(it, values, depth) }.orEmpty()
        if (result !is Boolean) return "{cannot_coerce_boolean_for_check_from(${jsString(result)})}"
        return branch(if (result) check.trueTemplate else check.falseTemplate, values, depth)
    }

    private fun branch(template: String, values: Map<String, Map<String, Any?>>, depth: Int): String {
        if (depth + 1 > MAX_DEPTH) return template
        if (branchCache.size > MAX_CACHED) branchCache.clear()
        val nodes = branchCache.getOrPut(template) { TemplateParser.parse(template) }
        return renderNodes(nodes, values, depth + 1)
    }

    private fun resolveExpression(node: TemplateNode.Expression, values: Map<String, Map<String, Any?>>): Resolved {
        if (node.operands.size == 1) return resolveOperand(node.operands[0], values)
        var present = operandPresent(node.operands[0], values)
        for (i in 1 until node.operands.size) {
            val next = operandPresent(node.operands[i], values)
            present = if (node.comparators[i - 1] == "or") present || next else present && next
        }
        val uniform = node.comparators.all { it == node.comparators[0] } && node.comparators[0] in setOf("and", "or")
        var result = resolveOperand(node.operands[0], values)
        for (i in 1 until node.operands.size) {
            if (result.error != null) return result
            val comparator = node.comparators[i - 1]
            if (uniform && comparator == "and" && result.result == false) return Resolved(false, present = present)
            if (uniform && comparator == "or" && result.result == true) return Resolved(true, present = present)
            val next = resolveOperand(node.operands[i], values)
            if (next.error != null) return next
            result = Resolved(combine(comparator, result.result, next.result))
        }
        return Resolved(result.result, present = present)
    }

    /** JavaScript `&&`, `||`, … on the two values (so `and` / `or` return an operand, as in AIOStreams). */
    private fun combine(comparator: String, a: Any?, b: Any?): Any? = when (comparator) {
        "and" -> if (truthy(a)) b else a
        "or" -> if (truthy(a)) a else b
        "xor" -> (truthy(a) || truthy(b)) && !(truthy(a) && truthy(b))
        "neq" -> !strictEquals(a, b)
        "equal" -> strictEquals(a, b)
        "left" -> a
        else -> b
    }

    private fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        is Long -> value != 0L
        is Int -> value != 0
        is Double -> value != 0.0 && !value.isNaN()
        else -> true
    }

    private fun strictEquals(a: Any?, b: Any?): Boolean {
        val x = (a as? Number)?.toDouble()
        val y = (b as? Number)?.toDouble()
        return if (x != null && y != null) x == y else a == b
    }

    private fun operandPresent(operand: Operand, values: Map<String, Map<String, Any?>>): Boolean = when {
        operand.literal != null -> true
        operand.modifiers.any { it.lowercase().startsWith("default(") } -> true
        else -> isPresent(values[operand.section]?.get(operand.property))
    }

    private fun resolveOperand(operand: Operand, values: Map<String, Map<String, Any?>>): Resolved {
        val context = ModifierContext { source ->
            val (section, property) = source.trim().split('.').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "") }
            TemplateFields.canonical(section, property)?.let { (s, p) -> values[s]?.get(p) }
        }
        if (operand.literal != null) {
            var value: Any? = operand.literal
            for (source in operand.modifiers) {
                val next = modifier(source)(value, context)
                if (next === NotApplicable) break
                value = capped(next)
            }
            return Resolved(value, present = true)
        }
        val section = values[operand.section] ?: return Resolved(error = "{unknown_variableType(${operand.section})}")
        if (!section.containsKey(operand.property)) {
            return Resolved(error = "{unknown_propertyName(${operand.section}.${operand.property})}")
        }
        val property = sanitise(section[operand.property])
        val present = isPresent(property) || operand.modifiers.any { it.lowercase().startsWith("default(") }
        var result: Any? = property
        for (source in operand.modifiers) {
            val input = result
            val next = modifier(source)(input, context)
            if (next !== NotApplicable) {
                result = capped(next)
                continue
            }
            if (input == null) return Resolved("", present = present)
            return Resolved(error = "{unknown_${typeName(input)}_modifier($source)}")
        }
        return Resolved(result, present = present)
    }

    private fun modifier(source: String): CompiledModifier {
        if (modifierCache.size > MAX_CACHED) modifierCache.clear()
        return modifierCache.getOrPut(source) { TemplateModifiers.compile(source) }
    }

    private fun capped(value: Any?): Any? = if (value is String && value.length > MAX_RENDER_LENGTH) value.substring(0, MAX_RENDER_LENGTH) else value

    /** Stream data can't forge a layout directive: sentinels are stripped as values enter the render. */
    private fun sanitise(value: Any?): Any? = when (value) {
        is String -> value.replace(NEW_LINE_SENTINEL, "").replace(REMOVE_LINE_SENTINEL, "")
        is List<*> -> value.map { item -> if (item is String) sanitise(item) else item }
        else -> value
    }

    private fun typeName(value: Any): String = when (value) {
        is List<*> -> "array"
        is String -> "string"
        is Boolean -> "boolean"
        is Number -> "number"
        else -> "object"
    }

    private const val MAX_RENDER_LENGTH = 8000
    private const val MAX_DEPTH = 5

    /** Portal previews can send any template, so the caches are bounded. */
    private const val MAX_CACHED = 512
}
