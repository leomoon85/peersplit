package com.peersplit.app.network.sync

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SerialEventQueueTest {
    @Test fun concurrentProducersAndRepeatedIdleHandoffsNeverLoseEventsOrStartTwoConsumers() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val seen = ConcurrentHashMap.newKeySet<Int>()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val queue = SerialEventQueue<Int>(scope) { value ->
            val n = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, n) }
            try { check(seen.add(value)) { "Duplicate event $value" } }
            finally { active.decrementAndGet() }
        }
        try {
            withTimeout(30_000) {
                repeat(200) { round ->
                    coroutineScope {
                        repeat(8) { producer -> launch(Dispatchers.Default) {
                            queue.addAll(List(5) { round * 40 + producer * 5 + it })
                            yield()
                        } }
                    }
                    while (seen.size < (round + 1) * 40) yield()
                    // Resume an idle consumer repeatedly, including producer/worker races.
                    yield()
                }
            }
            assertEquals(8000, seen.size)
            assertEquals(1, maxActive.get())
        } finally { scope.cancel() }
    }

    @Test fun eventsEnqueuedByConsumerAreDrainedInOrder() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val complete = CompletableDeferred<List<Int>>()
        val order = mutableListOf<Int>()
        lateinit var queue: SerialEventQueue<Int>
        queue = SerialEventQueue(scope) { value ->
            order += value
            if (value < 100) queue.addAll(listOf(value + 1)) else complete.complete(order.toList())
        }
        try {
            queue.addAll(listOf(0))
            assertEquals((0..100).toList(), withTimeout(5000) { complete.await() })
        } finally { scope.cancel() }
    }
}
