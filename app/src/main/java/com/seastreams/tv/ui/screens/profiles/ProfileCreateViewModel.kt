package com.seastreams.tv.ui.screens.profiles

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seastreams.tv.core.webserver.PortalLink
import com.seastreams.tv.core.webserver.PortalLinkProvider
import com.seastreams.tv.data.UserFacingException
import com.seastreams.tv.data.profile.ProfileDraft
import com.seastreams.tv.data.profile.ProfileRepository
import com.seastreams.tv.data.setup.TmdbKeyRepository
import com.seastreams.tv.domain.model.MovieCertification
import com.seastreams.tv.domain.model.TvCertification
import com.seastreams.tv.ui.navigation.WhileUiSubscribed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Where the editor was opened from. */
sealed interface ProfileEditorMode {
    /** First run: no profiles yet; the TMDB key gate applies. */
    data object Onboarding : ProfileEditorMode
    data object Create : ProfileEditorMode
    data class Edit(val profileId: Long) : ProfileEditorMode
}

/** Local, editable part of the screen state. */
data class ProfileEditorForm(
    val draft: ProfileDraft = ProfileDraft(name = ""),
    val keyInput: String = "",
    val validatingKey: Boolean = false,
    val keyError: String? = null,
    val keyJustSaved: Boolean = false,
    val saving: Boolean = false,
    val error: String? = null,
)

data class ProfileCreateUiState(
    val mode: ProfileEditorMode = ProfileEditorMode.Onboarding,
    val form: ProfileEditorForm = ProfileEditorForm(),
    /** True once a TMDB-validated key is stored — from the TV *or* the Web Portal. */
    val keySaved: Boolean = false,
    val maskedKey: String? = null,
    val portal: PortalLink = PortalLink(url = null, problem = null),
) {
    val canSubmit: Boolean get() = keySaved && form.draft.name.isNotBlank() && !form.saving
}

@HiltViewModel
class ProfileCreateViewModel @Inject constructor(
    private val profiles: ProfileRepository,
    private val tmdbKeys: TmdbKeyRepository,
    portalLinks: PortalLinkProvider,
) : ViewModel() {

    private val mode = MutableStateFlow<ProfileEditorMode>(ProfileEditorMode.Onboarding)
    private val form = MutableStateFlow(ProfileEditorForm())

    val uiState: StateFlow<ProfileCreateUiState> = combine(
        mode,
        form,
        tmdbKeys.hasValidKey,
        tmdbKeys.maskedKey,
        portalLinks.observe(),
    ) { mode, form, keySaved, masked, portal ->
        ProfileCreateUiState(mode, form, keySaved, masked, portal)
    }.stateIn(viewModelScope, WhileUiSubscribed, ProfileCreateUiState())

    /** (Re)initialises the editor; called whenever the screen is entered with a new [newMode]. */
    fun start(newMode: ProfileEditorMode) {
        mode.value = newMode
        form.value = ProfileEditorForm()
        if (newMode is ProfileEditorMode.Edit) {
            viewModelScope.launch {
                val existing = profiles.getProfile(newMode.profileId) ?: return@launch
                form.update { it.copy(draft = ProfileDraft.from(existing)) }
            }
        }
    }

    fun onNameChange(name: String) = editDraft { copy(name = name.take(ProfileRepository.MAX_NAME_LENGTH)) }
    fun onAvatarSelected(index: Int) = editDraft { copy(avatarIndex = index) }
    fun onKidsChange(isKids: Boolean) = editDraft { copy(isKids = isKids) }
    fun onMovieCeiling(ceiling: MovieCertification) = editDraft { copy(movieCeiling = ceiling) }
    fun onTvCeiling(ceiling: TvCertification) = editDraft { copy(tvCeiling = ceiling) }
    fun onAllowUnratedChange(allow: Boolean) = editDraft { copy(allowUnrated = allow) }

    fun onKeyInputChange(input: String) = form.update { it.copy(keyInput = input, keyError = null) }

    fun verifyKey() {
        val candidate = form.value.keyInput
        if (form.value.validatingKey) return
        form.update { it.copy(validatingKey = true, keyError = null, keyJustSaved = false) }
        viewModelScope.launch {
            val error = try {
                tmdbKeys.validateAndSave(candidate)
                null
            } catch (e: UserFacingException) {
                e.message
            }
            form.update {
                it.copy(
                    validatingKey = false,
                    keyError = error,
                    keyJustSaved = error == null,
                    keyInput = if (error == null) "" else it.keyInput,
                )
            }
        }
    }

    /** Creates or saves the profile; [onDone] receives the profile id. */
    fun submit(onDone: (Long) -> Unit) {
        val state = uiState.value
        if (!state.canSubmit) return
        runSaving(onDone) {
            when (val m = state.mode) {
                is ProfileEditorMode.Edit -> profiles.update(m.profileId, state.form.draft).id
                ProfileEditorMode.Create, ProfileEditorMode.Onboarding -> profiles.create(state.form.draft).id
            }
        }
    }

    fun delete(onDone: (Long) -> Unit) {
        val m = mode.value as? ProfileEditorMode.Edit ?: return
        runSaving(onDone) {
            profiles.delete(m.profileId)
            m.profileId
        }
    }

    private fun runSaving(onDone: (Long) -> Unit, action: suspend () -> Long) {
        if (form.value.saving) return
        form.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val id = action()
                form.update { it.copy(saving = false) }
                onDone(id)
            } catch (e: UserFacingException) {
                form.update { it.copy(saving = false, error = e.message) }
            }
        }
    }

    private fun editDraft(transform: ProfileDraft.() -> ProfileDraft) =
        form.update { it.copy(draft = it.draft.transform(), error = null) }

}
