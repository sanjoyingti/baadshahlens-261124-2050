package com.aibuilt.baadshahlens.camera

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

enum class KeyPhase { DOWN, REPEAT, UP }

object HardwareKeys {

    @Volatile
    var consuming: Boolean = false

    private val _events = MutableSharedFlow<KeyPhase>(extraBufferCapacity = 8)
    val events: SharedFlow<KeyPhase> = _events

    fun emit(phase: KeyPhase) {
        _events.tryEmit(phase)
    }
}
