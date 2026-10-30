package org.crisisnetwork.android

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Whether the app is on screen (phase A6), reported by [MainActivity]. Notifications are not posted
 * for what the person can see, and the network screen stops polling the core while the app is hidden.
 * [resumes] counts the times the app came back to the front, so that a screen can look again at
 * something that may have changed in the system settings (the notification switch).
 */
object AppVisibility {
    private val visibleFlow = MutableStateFlow(false)
    private val resumeFlow = MutableStateFlow(0)

    val visible: StateFlow<Boolean> = visibleFlow.asStateFlow()
    val resumes: StateFlow<Int> = resumeFlow.asStateFlow()

    fun onStart() {
        visibleFlow.value = true
    }

    fun onStop() {
        visibleFlow.value = false
    }

    fun onResume() {
        resumeFlow.update { it + 1 }
    }
}
