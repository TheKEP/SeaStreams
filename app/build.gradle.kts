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

        // Developer TMDB key from local.properties (never committed); "" when absent.
        val tmdbApiKey = rootProject.file("local.properties")
            .takeIf { it.isFile }
            ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
            ?.getProperty("TMDB_API_KEY")
            ?.trim()
            .orEmpty()
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbApiKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
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

    // Unit tests (JUnit 5 Jupiter)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.jdbc)
}
