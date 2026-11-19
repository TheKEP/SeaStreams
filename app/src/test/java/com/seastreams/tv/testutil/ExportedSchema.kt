package com.seastreams.tv.testutil

import com.seastreams.tv.core.database.SeaStreamsDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

private const val TABLE_NAME_PLACEHOLDER = "`\${TABLE_NAME}`"

/**
 * Table and index DDL from Room's exported schema for [version] (the module dir is the unit-test
 * working dir), ready to run against an in-memory sqlite-jdbc database.
 */
fun exportedSchemaSql(version: Int): List<String> {
    val file = File("schemas/${SeaStreamsDatabase::class.java.name}/$version.json")
    val entities = Json.parseToJsonElement(file.readText()).jsonObject.getValue("database").jsonObject.getValue("entities").jsonArray
    return entities.flatMap { element ->
        val entity = element.jsonObject
        val table = entity.getValue("tableName").jsonPrimitive.content
        val indices = entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject.getValue("createSql").jsonPrimitive.content }
        (listOf(entity.getValue("createSql").jsonPrimitive.content) + indices).map { it.replace(TABLE_NAME_PLACEHOLDER, "`$table`") }
    }
}
