import com.android.build.api.instrumentation.AsmClassVisitorFactory
import com.android.build.api.instrumentation.ClassContext
import com.android.build.api.instrumentation.ClassData
import com.android.build.api.instrumentation.FramesComputationMode
import com.android.build.api.instrumentation.InstrumentationParameters
import com.android.build.api.instrumentation.InstrumentationScope
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.room)
}

android {
    namespace = "com.seastreams.tv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.seastreams.tv"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1"

        // Developer values from local.properties (never committed); "" when absent.
        val localProperties = rootProject.file("local.properties")
            .takeIf { it.isFile }
            ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
        fun localString(name: String): String =
            localProperties?.getProperty(name)?.trim().orEmpty().replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "TMDB_API_KEY", "\"${localString("TMDB_API_KEY")}\"")
        // OAuth client IDs for Debrid-Link device sign-in and put.io OAuth-code sign-in (PRD §14). While blank, the
        // portal offers key / token paste only for those two services.
        buildConfigField("String", "DEBRID_LINK_CLIENT_ID", "\"${localString("DEBRID_LINK_CLIENT_ID")}\"")
        buildConfigField("String", "PUTIO_CLIENT_ID", "\"${localString("PUTIO_CLIENT_ID")}\"")
    }

    buildTypes {
        release {
            // The developer key from local.properties is for debug builds only.
            buildConfigField("String", "TMDB_API_KEY", "\"\"")
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor uses java.time / java.util APIs newer than minSdk 26.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/INDEX.LIST",
                "/META-INF/io.netty.versions.properties",
            )
        }
    }
    testOptions {
        unitTests.all { it.useJUnitPlatform() }
        unitTests.isReturnDefaultValues = true
    }
}

/**
 * NewPipeExtractor calls `URLDecoder.decode(String, Charset)` / `URLEncoder.encode(String, Charset)`, which
 * Android only ships from API 33 and desugaring doesn't backport (Fire OS crash). Retarget both calls to
 * `core/util/UrlCodecCompat` (ARCHITECTURE.md §4). Same descriptor, so stack and frames are unchanged.
 */
abstract class NewPipeUrlCodecRewrite : AsmClassVisitorFactory<InstrumentationParameters.None> {
    override fun isInstrumentable(classData: ClassData): Boolean =
        classData.className.startsWith("org.schabi.newpipe.")

    override fun createClassVisitor(classContext: ClassContext, nextClassVisitor: ClassVisitor): ClassVisitor =
        object : ClassVisitor(Opcodes.ASM9, nextClassVisitor) {
            override fun visitMethod(
                access: Int, name: String?, descriptor: String?, signature: String?, exceptions: Array<out String>?,
            ): MethodVisitor {
                val next = super.visitMethod(access, name, descriptor, signature, exceptions)
                return object : MethodVisitor(Opcodes.ASM9, next) {
                    override fun visitMethodInsn(opcode: Int, owner: String, name: String, descriptor: String, isInterface: Boolean) {
                        val rewrite = opcode == Opcodes.INVOKESTATIC && descriptor == CHARSET_CODEC_DESCRIPTOR &&
                            ((owner == "java/net/URLDecoder" && name == "decode") || (owner == "java/net/URLEncoder" && name == "encode"))
                        super.visitMethodInsn(opcode, if (rewrite) URL_CODEC_COMPAT else owner, name, descriptor, isInterface)
                    }
                }
            }
        }

    private companion object {
        const val CHARSET_CODEC_DESCRIPTOR = "(Ljava/lang/String;Ljava/nio/charset/Charset;)Ljava/lang/String;"
        const val URL_CODEC_COMPAT = "com/seastreams/tv/core/util/UrlCodecCompat"
    }
}

androidComponents {
    onVariants { variant ->
        variant.instrumentation.transformClassesWith(NewPipeUrlCodecRewrite::class.java, InstrumentationScope.ALL) {}
        variant.instrumentation.setAsmFramesComputationMode(FramesComputationMode.COPY_FRAMES)
    }
}

kotlin {
    compilerOptions {
        allWarningsAsErrors = false
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    // AndroidX + Compose for TV
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.tv.material)

    // DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Persistence
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)

    // Networking + embedded LAN portal server
    implementation(libs.okhttp)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Playback: Media3 ExoPlayer (+ HLS, DASH for YouTube trailers, OkHttp data source) and NewPipeExtractor
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.datasource.okhttp)
    // FFmpeg software decoding of AC-3/E-AC-3/TrueHD/DTS when the sink can't take them (ARCHITECTURE.md §4)
    implementation(libs.nextlib.media3ext)
    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    // Images, QR codes
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.zxing.core)
    // Usenet stored / password-protected 7z (ARCHITECTURE §4, §10). xz decodes the LZMA-packed 7z header.
    implementation(libs.commons.compress)
    implementation(libs.xz)

    // Unit tests (JUnit 5 Jupiter)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
}
