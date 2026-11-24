package com.seastreams.tv.domain.streams.format

import com.seastreams.tv.domain.model.StreamDisplay

/**
 * Renders a stream's card text from a pair of templates (PRD §13 formatter). Spaces the template author typed (between
 * fields, inside `{? … ?}` groups, around separators, at the start of a line) are kept exactly. Only whitespace at the
 * end of a line is trimmed, since a TV card can't show it, and lines left empty (an absent field) are dropped.
 */
class StreamFormatter(templates: FormatterTemplates) {

    private val name = TemplateRenderer.compile(templates.name)
    private val description = TemplateRenderer.compile(templates.description)

    fun format(input: FormatInput): StreamDisplay {
        val values = TemplateFields.values(input)
        return StreamDisplay(name = tidyCardText(name.render(values)), description = tidyCardText(description.render(values)))
    }
}

/**
 * Card text as the stream panel shows it: each line's trailing whitespace trimmed and blank lines dropped. Leading and
 * inner spaces are never touched, so `{stream.resolution}   {addon.name}` keeps its three spaces.
 */
internal fun tidyCardText(text: String): String = text.lines().map { it.trimEnd() }.filter { it.isNotBlank() }.joinToString("\n")
