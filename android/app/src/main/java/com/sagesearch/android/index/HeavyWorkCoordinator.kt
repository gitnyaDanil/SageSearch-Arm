package com.sagesearch.android.index

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex

/** Serializes memory-heavy work and lets user-requested inference take the next slot. */
class HeavyWorkCoordinator {
    private val gate = Mutex()
    private val interactiveWaiters = MutableStateFlow(0)

    val isInteractiveWaiting: Boolean
        get() = interactiveWaiters.value > 0

    suspend fun <T> withInteractiveWork(block: suspend () -> T): T {
        interactiveWaiters.update { it + 1 }
        try {
            gate.lock()
        } catch (error: Throwable) {
            interactiveWaiters.update { it - 1 }
            throw error
        }
        interactiveWaiters.update { it - 1 }
        return try {
            block()
        } finally {
            gate.unlock()
        }
    }

    suspend fun <T> withBackgroundWork(block: suspend () -> T): T {
        while (true) {
            interactiveWaiters.first { it == 0 }
            gate.lock()
            if (!isInteractiveWaiting) break
            gate.unlock()
        }
        return try {
            block()
        } finally {
            gate.unlock()
        }
    }
}
