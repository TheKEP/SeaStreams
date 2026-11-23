package com.seastreams.tv.ui.screens.settings.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.datastore.DebridSettingsStore
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.debrid.DebridConnectionTester
import com.seastreams.tv.data.debrid.DebridProvider
import com.seastreams.tv.data.debrid.DebridSourcesRepository
import com.seastreams.tv.data.sources.DebridSettingsClear
import com.seastreams.tv.ui.navigation.WhileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The `DEBRID` section of `Sources` and its `Debrid providers` sub-panel (PRD §11, §14). App-wide. */
data class DebridUiState(
    val providers: List<DebridProvider> = emptyList(),
    /** `Enable Debrid`. */
    val enabled: Boolean = false,
    /** When a pending `Clear Debrid settings` commits (epoch ms); `null` when none is pending. */
    val clearDeadlineMillis: Long? = null,
)

/**
 * Settings `Sources` → `DEBRID`. Accounts are added in the Web Portal (keys and codes are typed on a phone); the TV
 * switches, reorders, tests and removes them. Strictly separate from [SourcesViewModel]'s Usenet state.
 */
@HiltViewModel
class DebridSourcesViewModel @Inject constructor(
    private val repository: DebridSourcesRepository,
    private val store: DebridSettingsStore,
    private val tester: DebridConnectionTester,
    private val clear: DebridSettingsClear,
) : ViewModel() {

    private var testJob: Job? = null
    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    val state: StateFlow<DebridUiState> = combine(repository.providers, store.enabled, clear.deadline) { providers, enabled, deadline ->
        DebridUiState(providers, enabled, deadline)
    }.stateIn(viewModelScope, WhileUiSubscribed, DebridUiState())

    /** `Enable Debrid`: providers are kept either way. */
    fun setEnabled(enabled: Boolean) = act { store.setEnabled(enabled) }

    fun setProviderEnabled(id: Long, enabled: Boolean) = act { repository.setEnabled(id, enabled) }

    fun move(id: Long, offset: Int) = act { repository.move(id, offset) }

    fun remove(id: Long) = act { repository.remove(id) }

    /** The `Test` button: the account check as a toast (premium expiry only). A second press while one runs is ignored. */
    fun test(id: Long) {
        if (testJob?.isActive == true) return
        testJob = viewModelScope.launch {
            val name = state.value.providers.firstOrNull { it.id == id }?.name
            if (name != null) _messages.send("Testing $name…")
            val message = try {
                val (kind, result) = tester.test(id)
                kind?.let(result::message) ?: "Sign-in needed: connect it again in the Web Portal"
            } catch (e: UserFacingException) {
                e.message ?: "That didn't work"
            }
            _messages.send(message)
        }
    }

    /** `Clear Debrid settings`: starts the 5-second Undo window; nothing is deleted until it passes. */
    fun clearAll() = act { clear.schedule() }

    fun undoClear() = act { if (clear.undo()) _messages.send("Debrid settings kept") }

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
