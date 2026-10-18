package com.peersplit.app.network.sync

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** One consumer, including when producers arrive during the empty-queue handoff. */
internal class SerialEventQueue<T>(
    private val scope: CoroutineScope,
    private val consume: suspend (T) -> Unit
) {
    private val queue = ConcurrentLinkedQueue<T>()
    private val draining = AtomicBoolean(false)

    fun addAll(events: Collection<T>) {
        queue.addAll(events)
        if (events.isNotEmpty()) drain()
    }

    private fun drain() {
        if (!draining.compareAndSet(false, true)) return
        scope.launch {
            try {
                while (true) consume(queue.poll() ?: break)
            } finally {
                draining.set(false)
                // drain() alone acquires ownership; never acquire it twice.
                if (queue.isNotEmpty()) drain()
            }
        }
    }
}
