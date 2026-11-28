package com.seastreams.tv.data.skip

import com.seastreams.tv.core.network.NetworkModule
import com.seastreams.tv.domain.playback.skip.SegmentKind
import com.seastreams.tv.domain.playback.skip.SegmentSource
import com.seastreams.tv.domain.playback.skip.SkipSegment
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Milestone 14: IntroDB `/segments` answers → skip segments. */
class IntroDbResponseTest {

    private val json = NetworkModule.provideJson()

    private fun decode(text: String) = json.decodeFromString(IntroDbSegmentsDto.serializer(), text).toSegments()

    @Test
    fun `timed segments decode, untimed ones are absent`() {
        val text = """
            {"imdb_id":"tt0903747","media_type":"tv","is_movie":false,"season":1,"episode":1,
             "intro":{"start_sec":2.5,"end_sec":58,"start_ms":2500,"end_ms":58000,"confidence":0.92,"submission_count":14},
             "recap":null,"outro":{"start_sec":2700.25,"end_sec":2760},"post_credits":null,
             "no_intro":false,"no_recap":true,"no_outro":false}
        """.trimIndent()

        assertEquals(
            listOf(
                SkipSegment(SegmentKind.INTRO, 2_500, 58_000, SegmentSource.INTRODB),
                SkipSegment(SegmentKind.CREDITS, 2_700_250, 2_760_000, SegmentSource.INTRODB),
            ),
            decode(text),
        )
    }

    @Test
    fun `a movie's post-credits scene decodes`() {
        val text = """{"outro":{"start_ms":7000000,"end_ms":7300000},"post_credits":{"start_ms":7300000,"end_ms":7400000}}"""

        assertEquals(
            listOf(
                SkipSegment(SegmentKind.CREDITS, 7_000_000, 7_300_000, SegmentSource.INTRODB),
                SkipSegment(SegmentKind.POST_CREDITS, 7_300_000, 7_400_000, SegmentSource.INTRODB),
            ),
            decode(text),
        )
    }

    @Test
    fun `invalid times are dropped`() {
        val text = """{"intro":{"start_sec":60,"end_sec":30},"recap":{"start_sec":-5,"end_sec":10},"outro":{"start_sec":100}}"""

        assertEquals(emptyList<SkipSegment>(), decode(text))
    }
}
