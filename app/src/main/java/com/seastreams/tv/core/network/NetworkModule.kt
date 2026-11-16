package com.seastreams.tv.core.network

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    const val HTTP_CACHE_DIR = "http_cache"
    const val HTTP_CACHE_SIZE_BYTES: Long = 100L * 1024 * 1024
    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 30L

    @Provides
    @Singleton
    fun provideApiEndpoints(): ApiEndpoints = ApiEndpoints()

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
        coerceInputValues = true
    }

    @Provides
    @Singleton
    fun provideHttpCachePolicy(endpoints: ApiEndpoints): HttpCachePolicy = HttpCachePolicy(endpoints)

    @Provides
    @Singleton
    fun provideHttpCache(@ApplicationContext context: Context): Cache =
        Cache(File(context.cacheDir, HTTP_CACHE_DIR), HTTP_CACHE_SIZE_BYTES)

    @Provides
    @Singleton
    fun provideOkHttpClient(cache: Cache, cachePolicy: HttpCachePolicy): OkHttpClient =
        buildOkHttpClient(cache, cachePolicy, RateLimitRetryInterceptor())

    @Provides
    @Singleton
    fun provideHttpClient(okHttpClient: OkHttpClient, json: Json): HttpClient =
        buildKtorClient(okHttpClient, json)

    /**
     * The 429 retry runs as an *application* interceptor (wrapping cache + network), while the
     * TTL rewrite runs as a *network* interceptor so the disk cache stores the rewritten headers.
     */
    fun buildOkHttpClient(
        cache: Cache,
        cachePolicy: HttpCachePolicy,
        retryInterceptor: RateLimitRetryInterceptor,
    ): OkHttpClient = OkHttpClient.Builder()
        .cache(cache)
        .addInterceptor(retryInterceptor)
        .addNetworkInterceptor(CacheTtlInterceptor(cachePolicy))
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    fun buildKtorClient(okHttpClient: OkHttpClient, json: Json): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = okHttpClient }
        expectSuccess = true
        install(ContentNegotiation) { json(json) }
    }
}
