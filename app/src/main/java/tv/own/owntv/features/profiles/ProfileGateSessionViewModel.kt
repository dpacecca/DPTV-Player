package tv.own.owntv.features.profiles

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel

/**
 * Activity-scoped gate/session state for the profile gate and the in-session "Who's watching?"
 * transitions. Owns nothing persisted.
 *
 * **Why a ViewModel and not `rememberSaveable`** (see `docs/internationalization.md`, Phase 0a —
 * "The profile gate: configuration-only retention, never saveable"):
 *
 * A gate-passed flag must survive an Activity recreation caused by a *configuration* change (font
 * scale, dark mode, a script-family language switch — all of which OwnTV now triggers far more often
 * than before i18n), so the user is not dumped back at the PIN gate mid-session. But it must **not**
 * survive system-initiated process death: `rememberSaveable` persists through `onSaveInstanceState`,
 * which is restored after a background kill, and a restored `gatePassed = true` would put a user
 * straight past the profile/PIN gate when Android revives the task — precisely the case the gate
 * exists to cover.
 *
 * A ViewModel tied to the Activity's `ViewModelStore` has exactly that lifetime: it survives
 * configuration recreations through store retention, and is cleared when the process dies. There is
 * deliberately **no `SavedStateHandle`** and **no saved-state key** for the authentication flag. The
 * neighbouring in-session navigation flags (`addingProfile`, `switchProfileRequested`) share that
 * lifetime because they are transient UI navigation the user expects to survive a rotation but not a
 * kill; they were audited individually rather than batch-converted (see the table in the same
 * section of the plan).
 *
 * `everHadProfiles` is **not** hosted here: it is derived from the persisted active-profile id (an
 * id `>= 0` means a profile has existed in the store), so it is observed, not remembered.
 */
class ProfileGateSessionViewModel : ViewModel() {

    /** True once the active profile has been authenticated this session. Resets on process death. */
    var gatePassed by mutableStateOf(false)
        private set

    /** True while the "add a profile" onboarding flow is open from the gate. Resets on process death. */
    var addingProfile by mutableStateOf(false)
        private set

    /**
     * Set by the sidebar avatar single-click so the "Who's watching?" gate opens even for a single
     * unpinned profile (otherwise switch-profile is a silent no-op with one profile). Resets on
     * process death.
     */
    var switchProfileRequested by mutableStateOf(false)
        private set

    /** User authenticated through the gate / finished onboarding; clear any pending switch request. */
    fun markGatePassed() {
        gatePassed = true
        switchProfileRequested = false
    }

    /** Open the add-profile onboarding from the gate. */
    fun startAddingProfile() {
        addingProfile = true
        switchProfileRequested = false
    }

    /** Add-profile onboarding completed: enter the shell as the new profile. */
    fun completeAddingProfile() {
        addingProfile = false
        gatePassed = true
    }

    /** Add-profile onboarding cancelled: back to the gate. */
    fun cancelAddingProfile() {
        addingProfile = false
    }

    /** Back out of a user-requested switch: return past the gate. */
    fun cancelSwitchProfileRequest() {
        switchProfileRequested = false
        gatePassed = true
    }

    /**
     * Sidebar "switch profile": drop back to the gate and force it open even for a single unpinned
     * profile. Playback stop is the caller's responsibility (it owns the player engines); this only
     * owns session state.
     */
    fun requestSwitchProfile() {
        gatePassed = false
        switchProfileRequested = true
    }
}