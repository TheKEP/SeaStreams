package com.seastreams.tv.core.util

import androidx.annotation.Keep
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * API 1-compatible stand-ins for `URLDecoder.decode(String, Charset)` / `URLEncoder.encode(String, Charset)`
 * (Android API 33+, not backported by core library desugaring). `app/build.gradle.kts`
 * (`NewPipeUrlCodecRewrite`) retargets NewPipeExtractor's calls here at build time, so trailer extraction
 * works on Fire OS / API 26–32 (ARCHITECTURE §4). Referenced only from rewritten bytecode, hence [Keep].
 */
@Keep
object UrlCodecCompat {
    @JvmStatic
    fun decode(s: String, charset: Charset): String = URLDecoder.decode(s, charset.name())

    @JvmStatic
    fun encode(s: String, charset: Charset): String = URLEncoder.encode(s, charset.name())
}
