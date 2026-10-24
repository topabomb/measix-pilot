package net.weero.measix.pilot.ui.components.toast

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

enum class ToastType { Normal, Success, Info, Warning, Error }

internal data class ToastItem(val id: Any, val message: String, val type: ToastType)

/** Transient presentation only. All list/timer mutations run on the creating UI scope. */
@Stable
class ToasterState internal constructor(private val scope: CoroutineScope, private val capacity: Int = 3) {
    private val nextId = AtomicLong()
    internal val items = mutableStateListOf<ToastItem>()
    private val timers = mutableMapOf<Any, Job>()
    private var closed = false

    fun show(message: String, type: ToastType = ToastType.Normal, duration: Duration = 4.seconds, id: Any = nextId.incrementAndGet()) {
        scope.launch {
            if (closed) return@launch
            remove(id)
            val item = ToastItem(id, message, type)
            items.add(item)
            while (items.size > capacity) remove(items.first().id)
            if (duration.isFinite()) timers[id] = scope.launch {
                delay(duration.coerceAtLeast(Duration.ZERO))
                // An older expiry cannot dismiss a replacement using the same progress handle.
                if (items.any { it === item }) remove(id)
            }
        }
    }

    fun dismiss(id: Any) { scope.launch { remove(id) } }

    private fun remove(id: Any) {
        timers.remove(id)?.cancel()
        items.removeAll { it.id == id }
    }

    internal fun close() {
        closed = true
        timers.values.forEach(Job::cancel)
        timers.clear()
        items.clear()
    }
}

@Composable
fun rememberToasterState(): ToasterState {
    val scope = rememberCoroutineScope()
    val state = remember(scope) { ToasterState(scope) }
    DisposableEffect(state) { onDispose { state.close() } }
    return state
}
