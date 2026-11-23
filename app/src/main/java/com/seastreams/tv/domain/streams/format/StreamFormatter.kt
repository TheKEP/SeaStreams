package com.seastreams.tv.domain.streams.format

import com.seastreams.tv.domain.model.StreamDisplay

/**
 * Renders a stream's card text from a pair of templates (PRD §13 formatter). Each rendered line is trimmed at the end
 * (and the name at both ends), since a TV card can't show the trailing spaces AIOStreams templates leave behind.
 */
class StreamFormatter(templates: FormatterTemplates) {

    private val name = TemplateRenderer.compile(templates.name)
    private val description = TemplateRenderer.compile(templates.description)

    fun format(input: FormatInput): StreamDisplay {
        val values = TemplateFields.values(input)
        return StreamDisplay(
            name = tidy(name.render(values)).lines().joinToString("\n") { it.trim() },
            description = tidy(description.render(values)),
        )
    }

    private fun tidy(text: String): String = text.lines().map { it.trimEnd() }.filter { it.isNotEmpty() }.joinToString("\n")
}
