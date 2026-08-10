package com.sam.openclone.clone

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

internal class CloneResult(val message: String, val success: Boolean)

internal class CloneUiState(
    /** Package currently being cloned, or null when idle. */
    val busyPackage: String? = null,
    val busyLabel: String = "",
    val progress: Float = 0f,
    /** True once the system installer prompt is up and we are waiting on it. */
    val awaitingConfirmation: Boolean = false,
    val result: CloneResult? = null,
)

/**
 * Shared state between the clone service, the install-result receiver and the
 * UI.
 *
 * The work runs in a foreground service so it survives the activity going
 * away, which means progress cannot live in a ViewModel. This object is the
 * one place all three talk through.
 */
internal object CloneCoordinator {

    private val _state = MutableStateFlow(CloneUiState())
    val state: StateFlow<CloneUiState> = _state.asStateFlow()

    /**
     * Whether an activity is on screen. The install prompt is an activity, and
     * launching one from the background is blocked, so this decides whether the
     * prompt can be shown directly or has to go out as a notification.
     */
    @Volatile
    var uiVisible: Boolean = false

    val isBusy: Boolean get() = _state.value.busyPackage != null

    fun onStarted(packageName: String, label: String) {
        _state.value = CloneUiState(busyPackage = packageName, busyLabel = label)
    }

    fun onProgress(fraction: Float) {
        _state.update { current ->
            if (current.busyPackage == null) current
            else CloneUiState(current.busyPackage, current.busyLabel, fraction)
        }
    }

    fun onAwaitingConfirmation() {
        _state.update { current ->
            CloneUiState(current.busyPackage, current.busyLabel, 1f, awaitingConfirmation = true)
        }
    }

    fun onFinished(message: String, success: Boolean) {
        _state.value = CloneUiState(result = CloneResult(message, success))
    }

    fun consumeResult() {
        _state.update { current ->
            if (current.result == null) current
            else CloneUiState(current.busyPackage, current.busyLabel, current.progress, current.awaitingConfirmation)
        }
    }
}
