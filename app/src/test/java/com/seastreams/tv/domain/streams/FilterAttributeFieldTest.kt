package com.seastreams.tv.domain.streams

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [FilterAttribute.field] is the one name map the portal scripts and the formatter's `user` fields share. */
class FilterAttributeFieldTest {

    @Test
    fun `every attribute's field is the StreamFilters JSON key holding it`() {
        val keys = Json { encodeDefaults = true }.encodeToJsonElement(StreamFilters.serializer(), StreamFilters()).jsonObject.keys
        FilterAttribute.entries.forEach { attribute -> assertTrue(attribute.field in keys, "${attribute.name} → ${attribute.field}") }
        assertEquals(FilterAttribute.entries.size, FilterAttribute.entries.map { it.field }.toSet().size, "fields are distinct")
    }
}
