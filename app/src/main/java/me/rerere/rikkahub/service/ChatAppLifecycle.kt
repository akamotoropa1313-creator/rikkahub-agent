package me.rerere.rikkahub.service

import android.os.Looper
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Lightweight process-lifecycle bridge for chat.
 *
 * ProcessLifecycleOwner requires observer registration on the main thread. Keeping that
 * registration here lets ChatService remain lazy and safe to construct from headless IO
 * entry points without forcing its large dependency graph into Application.onCreate().
 */
class ChatAppLifecycle {
    private val _isForeground = MutableStateFlow(false)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    private val stopListeners = CopyOnWriteArraySet<() -> Unit>()

    private val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> _isForeground.value = true
            Lifecycle.Event.ON_STOP -> {
                _isForeground.value = false
                stopListeners.forEach { listener ->
                    runCatching(listener).onFailure {
                        Log.w(TAG, "chat lifecycle stop listener failed", it)
                    }
                }
            }
            else -> Unit
        }
    }

    init {
        check(Looper.myLooper() == Looper.getMainLooper()) {
            "ChatAppLifecycle must be created on the main thread"
        }
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        lifecycle.addObserver(lifecycleObserver)
        _isForeground.value = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    fun addOnStopListener(listener: () -> Unit) {
        stopListeners += listener
    }

    fun removeOnStopListener(listener: () -> Unit) {
        stopListeners -= listener
    }

    companion object {
        private const val TAG = "ChatAppLifecycle"
    }
}
