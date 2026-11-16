package com.seastreams.tv.data.setup

import com.seastreams.tv.core.datastore.SettingsDataStore
import com.seastreams.tv.core.network.TmdbApiService
import com.seastreams.tv.data.UserFacingException
import dagger.Lazy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.ktor.client.plugins.ResponseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Checks a candidate TMDB credential; `true` = accepted, `false` = rejected (401). */
fun interface TmdbKeyValidator {
    /** @throws IOException / [ResponseException] when TMDB cannot be reached or errors. */
    suspend fun isValid(candidateKey: String): Boolean
}

@Module
@InstallIn(SingletonComponent::class)
object TmdbKeyValidatorModule {
    /**
     * The service is resolved lazily on IO: the key gate is injected into main-thread ViewModels, and
     * building the HTTP stack eagerly would touch the disk cache directory on the main thread.
     */
    @Provides
    @Singleton
    fun provideValidator(service: Lazy<TmdbApiService>): TmdbKeyValidator = TmdbKeyValidator { key ->
        withContext(Dispatchers.IO) { service.get() }.validateApiKey(key)
    }
}

/**
 * The TMDB credential gate. A key is persisted only after TMDB accepts it via `/configuration`, so
 * "a key is saved" always means "a valid key is saved" — which is what unlocks onboarding.
 */
@Singleton
class TmdbKeyRepository @Inject constructor(
    private val settings: SettingsDataStore,
    private val validator: TmdbKeyValidator,
) {
    val hasValidKey: Flow<Boolean> = settings.tmdbApiKey.map { !it.isNullOrBlank() }

    /** Masked form for display (e.g. `••••••3f9a`), or `null` when no key is saved. */
    val maskedKey: Flow<String?> = settings.tmdbApiKey.map { key -> key?.let(::mask) }

    /** Validates [rawKey] against TMDB and saves it when accepted. */
    suspend fun validateAndSave(rawKey: String) {
        val key = rawKey.trim()
        if (key.isEmpty()) throw UserFacingException("Enter your TMDB API key")
        if (key.length > MAX_KEY_LENGTH || key.any { it.isWhitespace() }) {
            throw UserFacingException("That doesn't look like a TMDB API key")
        }
        val accepted = try {
            validator.isValid(key)
        } catch (e: ResponseException) {
            throw UserFacingException("TMDB answered HTTP ${e.response.status.value}; try again shortly")
        } catch (e: IOException) {
            throw UserFacingException("Couldn't reach TMDB — check the TV's internet connection")
        }
        if (!accepted) throw UserFacingException("TMDB rejected that API key")
        settings.setTmdbApiKey(key)
    }

    companion object {
        /** v3 keys are 32 chars; v4 read-access tokens are JWTs of a few hundred chars. */
        const val MAX_KEY_LENGTH = 1024
        private const val VISIBLE_SUFFIX = 4

        fun mask(key: String): String =
            if (key.length <= VISIBLE_SUFFIX) "••••" else "••••••" + key.takeLast(VISIBLE_SUFFIX)
    }
}
