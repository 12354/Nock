package app.nock.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.nock.android.domain.escalation.EscalationEngine
import app.nock.android.domain.model.Reminder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * App-scoped holder for the "reminder deleted" undo. The detail editor pops itself
 * (and its ViewModel) off the back stack the moment delete is tapped, so the undo
 * has to live above the NavHost — here — to outlast that navigation.
 */
@HiltViewModel
class ReminderUndoViewModel @Inject constructor(
    private val engine: EscalationEngine,
) : ViewModel() {

    fun restore(reminder: Reminder) {
        viewModelScope.launch {
            // NonCancellable so a config change mid-restore can't drop the row.
            withContext(NonCancellable) { engine.restoreReminder(reminder) }
        }
    }

    companion object {
        const val UNDO_WINDOW_MS: Long = 5_000L
    }
}
