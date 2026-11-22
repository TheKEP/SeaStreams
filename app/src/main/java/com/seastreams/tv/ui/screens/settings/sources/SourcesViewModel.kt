package com.seastreams.tv.ui.screens.settings.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.UsenetSettingsStore
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.usenet.NewznabIndexer
import com.seastreams.tv.data.usenet.UsenetProvider
import com.seastreams.tv.data.usenet.UsenetSourcesRepository
import com.seastreams.tv.data.usenet.headers.HeaderPreset
import com.seastreams.tv.data.usenet.headers.HeaderUse
import com.seastreams.tv.data.usenet.headers.HeaderVersionUpdater
import com.seastreams.tv.data.usenet.headers.RequestHeaderSettings
import com.seastreams.tv.domain.usenet.UsenetPerformance
import com.seastreams.tv.domain.usenet.UsenetProfile
import com.seastreams.tv.ui.navigation.WhileUiSubscribed
import com.seastreams.tv.usenet.nntp.NntpConnectionTester
import kotlinx.coroutines.Job
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Everything the Settings `Sources` tab and its sub-panels show (PRD §11, §12). App-wide. */
data class SourcesUiState(
    val providers: List<UsenetProvider> = emptyList(),
    val indexers: List<NewznabIndexer> = emptyList(),
    val performance: UsenetPerformance = UsenetPerformance(),
    val headers: RequestHeaderSettings = RequestHeaderSettings(),
    val updatingHeaders: Boolean = false,
)

/** Settings `Sources` tab. Providers/indexers are added in the Web Portal; the TV toggles, reorders and removes them. */
@HiltViewModel
class SourcesViewModel @Inject constructor(
    private val repository: UsenetSourcesRepository,
    private val store: UsenetSettingsStore,
    private val updater: HeaderVersionUpdater,
    private val tester: NntpConnectionTester,
) : ViewModel() {

    private var testJob: Job? = null

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<SourcesUiState> = combine(
        repository.providers,
        repository.indexers,
        store.performance,
        store.headers,
        updater.updating,
    ) { providers, indexers, performance, headers, updating ->
        SourcesUiState(providers, indexers, performance, headers, updating)
    }.stateIn(viewModelScope, WhileUiSubscribed, SourcesUiState())

    fun setProviderEnabled(id: Long, enabled: Boolean) = act { repository.setProviderEnabled(id, enabled) }

    fun moveProvider(id: Long, offset: Int) = act { repository.moveProvider(id, offset) }

    fun removeProvider(id: Long) = act { repository.removeProvider(id) }

    /**
     * The provider `Test` button: one sign-in outside the engine's pools, reported as a toast (success, bad login,
     * connection limit or unreachable). A second press while one runs is ignored.
     */
    fun testProvider(id: Long) {
        if (testJob?.isActive == true) return
        testJob = viewModelScope.launch {
            val message = try {
                val endpoint = repository.endpoint(id)
                _messages.send("Testing ${endpoint.name}…")
                val dialTimeoutMs = store.performance.first().dialTimeoutSeconds * 1_000
                tester.test(endpoint, dialTimeoutMs).message
            } catch (e: UserFacingException) {
                e.message ?: "That didn't work"
            } catch (e: IOException) {
                "Couldn't read the Usenet settings"
            }
            _messages.send(message)
        }
    }

    fun setIndexerEnabled(id: Long, enabled: Boolean) = act { repository.setIndexerEnabled(id, enabled) }

    fun moveIndexer(id: Long, offset: Int) = act { repository.moveIndexer(id, offset) }

    fun removeIndexer(id: Long) = act { repository.removeIndexer(id) }

    fun setHeaderPreset(use: HeaderUse, preset: HeaderPreset) = act { store.setHeaderPreset(use, preset) }

    /** Picking a template overwrites every value; `Custom` keeps them. */
    fun setProfile(profile: UsenetProfile) = act { store.updatePerformance { it.withProfile(profile) } }

    /** Any single-value edit switches the profile to `Custom`. */
    fun editPerformance(transform: (UsenetPerformance) -> UsenetPerformance) = act {
        store.updatePerformance { it.edited(transform) }
    }

    /** `Update header versions`. */
    fun updateHeadersNow() {
        viewModelScope.launch {
            val updated = try {
                updater.updateNow()
            } catch (e: IOException) {
                false
            }
            _messages.send(if (updated) "Header versions updated" else "Couldn't reach GitHub, so the current versions are kept")
        }
    }

    /** Opening the `Sources` tab runs the daily check if it's due (silently). */
    fun checkHeadersIfDue() {
        viewModelScope.launch {
            try {
                updater.checkIfDue()
            } catch (e: IOException) {
                // DataStore unreadable: the automatic check simply runs again next time.
            }
        }
    }

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: UserFacingException) {
                _messages.send(e.message ?: "That didn't work")
            }
        }
    }
}
