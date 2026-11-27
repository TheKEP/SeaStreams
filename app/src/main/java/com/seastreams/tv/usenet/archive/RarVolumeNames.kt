package com.seastreams.tv.usenet.archive

/** How a file name places it in a multi-file set. */
enum class VolumeStyle {
    /** `name.part01.rar` … */
    RAR_PART,

    /** `name.rar`, `name.r00` … `name.r99`, `name.s00` … `name.z99`. */
    RAR_OLD,

    /** `name.ext.001`, `.002` …: raw byte splits (or, by signature, RAR / 7z volumes). */
    SPLIT,

    /** `name.7z`, `name.7z.001` …: one 7z archive split into raw byte volumes. */
    SEVEN_ZIP,
}

/** One file's place in a set: [base] (lower case) groups the set, [index] orders it (0 = first). */
data class VolumeName(val base: String, val index: Int, val style: VolumeStyle)

/**
 * Parses volume names (PRD §12): `.partNN.rar`, the old `.rar` + `.rNN` scheme with its `.s00`–`.z99` rollover
 * after `.r99`, and `.001` splits. `.7z` / `.7z.NNN` form one 7z set (`.7z` alone is index 0).
 */
object RarVolumeNames {
    private val PART = Regex("^(.+)\\.part(\\d{1,4})\\.rar$", RegexOption.IGNORE_CASE)
    private val RAR = Regex("^(.+)\\.rar$", RegexOption.IGNORE_CASE)
    private val OLD = Regex("^(.+)\\.([r-z])(\\d{2})$", RegexOption.IGNORE_CASE)
    private val SEVEN = Regex("^(.+)\\.7z(?:\\.(\\d{3}))?$", RegexOption.IGNORE_CASE)
    private val SPLIT = Regex("^(.+)\\.(\\d{3})$")

    fun parse(filename: String): VolumeName? {
        val name = filename.substringAfterLast('/').trim()
        SEVEN.matchEntire(name)?.let { m ->
            return VolumeName(m.groupValues[1].lowercase(), m.groupValues[2].toIntOrNull()?.minus(1) ?: 0, VolumeStyle.SEVEN_ZIP)
        }
        PART.matchEntire(name)?.let { m ->
            return VolumeName(m.groupValues[1].lowercase(), m.groupValues[2].toInt() - 1, VolumeStyle.RAR_PART)
        }
        RAR.matchEntire(name)?.let { m -> return VolumeName(m.groupValues[1].lowercase(), 0, VolumeStyle.RAR_OLD) }
        OLD.matchEntire(name)?.let { m ->
            val letter = m.groupValues[2].lowercase()[0]
            val index = 1 + (letter - 'r') * 100 + m.groupValues[3].toInt()
            return VolumeName(m.groupValues[1].lowercase(), index, VolumeStyle.RAR_OLD)
        }
        SPLIT.matchEntire(name)?.let { m ->
            return VolumeName(m.groupValues[1].lowercase(), m.groupValues[2].toInt() - 1, VolumeStyle.SPLIT)
        }
        return null
    }
}
