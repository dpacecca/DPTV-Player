package tv.own.owntv.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tv.own.owntv.core.backup.BackupManager
import java.io.File

/** Phase 12 — drives Backup & Restore (selective export/import to a JSON file). */
class BackupViewModel(private val backup: BackupManager) : ViewModel() {

    sealed interface State {
        data object Idle : State
        data object Working : State

        /** A restore file was picked & inspected: let the user choose which sections to apply. */
        data class ChooseRestore(
            val file: File,
            val available: Set<BackupManager.Section>,
            val encrypted: Boolean,
        ) : State
        data class Done(val message: String) : State
        data class Error(val message: String) : State

        /** Encrypted restore needs the backup password — [retry] is true after a wrong attempt. */
        data class NeedPassword(
            val file: File,
            val sections: Set<BackupManager.Section>,
            val retry: Boolean = false,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** Export with an optional backup passphrase (blank/null = omit secret password fields). */
    fun export(folder: File, sections: Set<BackupManager.Section>, backupPassword: String?) {
        viewModelScope.launch {
            _state.value = State.Working
            backup.export(folder, sections, backupPassword).fold(
                onSuccess = {
                    val note = if (backupPassword.isNullOrBlank()) " (passwords were not included)" else ""
                    _state.value = State.Done("Saved to $it$note")
                },
                onFailure = { _state.value = State.Error(it.message ?: "Export failed") },
            )
        }
    }

    /** Step 1 of restore: inspect the picked file so the section picker can show what's inside. */
    fun inspect(file: File) {
        viewModelScope.launch {
            _state.value = State.Working
            backup.sectionsIn(file).fold(
                onSuccess = { _state.value = State.ChooseRestore(file, it.sections, it.encrypted) },
                onFailure = { _state.value = State.Error(it.message ?: "Couldn't read the backup file") },
            )
        }
    }

    /** Step 2 of restore: apply the chosen sections. */
    fun import(file: File, sections: Set<BackupManager.Section>, backupPassword: String?) {
        viewModelScope.launch {
            _state.value = State.Working
            backup.import(file, sections, backupPassword).fold(
                onSuccess = {
                    val note = if (backupPassword.isNullOrBlank()) " Re-enter any saved passwords in Sources/Proxy." else ""
                    _state.value = State.Done("Restored $it items. Re-sync your sources to load content.$note")
                },
                onFailure = {
                    if (it is BackupManager.WrongPasswordException) {
                        _state.value = State.NeedPassword(file, sections, retry = true)
                    } else {
                        _state.value = State.Error(it.message ?: "Import failed")
                    }
                },
            )
        }
    }

    /** Restore proceeds: either prompt for the password (encrypted) or import straight away. */
    fun beginImport(file: File, sections: Set<BackupManager.Section>, encrypted: Boolean) {
        if (encrypted) _state.value = State.NeedPassword(file, sections)
        else import(file, sections, null)
    }

    fun reset() { _state.value = State.Idle }
}
