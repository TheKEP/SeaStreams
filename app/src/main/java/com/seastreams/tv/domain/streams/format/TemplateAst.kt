package com.seastreams.tv.domain.streams.format

/**
 * Formatter template AST (AIOStreams `formatters/engine/ast.ts`). Unparseable spans stay [Raw] text so a typo shows
 * up in the rendered card instead of failing the whole template.
 */
sealed interface TemplateNode {
    data class Raw(val text: String) : TemplateNode

    /** `{tools.newLine}` / `{tools.removeLine}`: layout directives, not values. */
    data class Tool(val tool: ToolKind) : TemplateNode

    /** `{? … ?}`: renders only when every bare expression inside it has a value. */
    data class Group(val nodes: List<TemplateNode>) : TemplateNode

    /** `{ operand (::comparator:: operand)* (["true"||"false"(||"absent")])? }` */
    data class Expression(
        val source: String,
        val operands: List<Operand>,
        /** Always `operands.size - 1` entries: `and`, `or`, `xor`, `neq`, `equal`, `left`, `right`. */
        val comparators: List<String>,
        val check: Check?,
    ) : TemplateNode
}

enum class ToolKind { NEW_LINE, REMOVE_LINE }

/** `section.property::modifier::…`, or a quoted [literal] used in place of a field. */
data class Operand(val section: String, val property: String, val modifiers: List<String>, val literal: String? = null)

/** The branches of `["true"||"false"]`; [absentTemplate] is taken when the value is missing rather than false. */
data class Check(val trueTemplate: String, val falseTemplate: String, val absentTemplate: String?)
