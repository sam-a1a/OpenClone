package com.sam.openclone.clone

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * How long to wait for the installer to report back before giving the UI its
 * clone slot again.
 *
 * Committing a session usually resolves in seconds, but it is not guaranteed
 * to resolve at all: Play Protect can hold a verification open indefinitely,
 * and the user can walk away from the confirmation dialog. Neither should
 * leave the app permanently unable to start another clone.
 */
private const val INSTALL_TIMEOUT_MS = 90_000L

internal class CloneResult(val message: String, val success: Boolean)

internal class CloneUiState(
    /** Package currently being cloned, or null when idle. */
    val busyPackage: String? = null,
    val busyLabel: String = "",
    val progress: Float = 0f,
    /** True once the session is committed and the installer owns the outcome. */
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var timeout: Job? = null

    /**
     * Whether an activity is on screen. The install prompt is an activity, and
     * launching one from the background is blocked, so this decides whether the
     * prompt can be shown directly or has to go out as a notification.
     */
    @Volatile
    var uiVisible: Boolean = false

    /**
     * Takes the single clone slot, or returns false if it is already held.
     *
     * The claim has to happen the moment the row is tapped, not when the
     * service starts: `startForegroundService` returns before the service
     * runs, so a second tap in that window would otherwise start a second
     * clone racing the first for the same clone name.
     */
    @Synchronized
    fun tryClaim(packageName: String, label: String): Boolean {
        if (_state.value.busyPackage != null) return false
        _state.value = CloneUiState(busyPackage = packageName, busyLabel = label)
        return true
    }

    @Synchronized
    fun release() {
        timeout?.cancel()
        timeout = null
        _state.value = CloneUiState()
    }

    fun onProgress(fraction: Float) {
        _state.update { current ->
            if (current.busyPackage == null) current
            else CloneUiState(current.busyPackage, current.busyLabel, fraction)
        }
    }

    /**
     * Hands the outcome to the installer, and arms a timeout so a verification
     * that never comes back does not strand the UI.
     */
    @Synchronized
    fun onAwaitingConfirmation(timeoutMessage: String) {
        _state.update { current ->
            CloneUiState(current.busyPackage, current.busyLabel, 1f, awaitingConfirmation = true)
        }
        timeout?.cancel()
        timeout = scope.launch {
            delay(INSTALL_TIMEOUT_MS)
            onFinished(timeoutMessage, success = false)
        }
    }

    @Synchronized
    fun onFinished(message: String, success: Boolean) {
        timeout?.cancel()
        timeout = null
        _state.value = CloneUiState(result = CloneResult(message, success))
    }

    fun consumeResult() {
        _state.update { current ->
            if (current.result == null) current
            else CloneUiState(
                current.busyPackage,
                current.busyLabel,
                current.progress,
                current.awaitingConfirmation,
            )
        }
    }
}
