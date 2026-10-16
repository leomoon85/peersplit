package com.peersplit.app.network.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.Collections

/**
 * Manages a pool of decentralized, public Nostr relays for redundancy and high availability.
 */
class RelayPool(
    private val relayUrls: List<String> = DEFAULT_RELAYS
) {
    companion object {
        private const val TAG = "RelayPool"

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom",
            "wss://relay.snort.social"
        )
    }

    private val clients = mutableListOf<NostrClient>()
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val seenEventIds = Collections.synchronizedSet(mutableSetOf<String>())
    private val subscribedGroups = Collections.synchronizedSet(mutableSetOf<String>())

    private val _incomingEvents = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 128)
    val incomingEvents: SharedFlow<NostrEvent> = _incomingEvents.asSharedFlow()

    fun connectAll() {
        relayUrls.forEach { url ->
            val client = NostrClient(url)
            clients.add(client)
            client.connect()

            scope.launch {
                client.eventsFlow.collect { event ->
                    // Deduplicate across relays
                    if (seenEventIds.add(event.id)) {
                        Log.d(TAG, "RelayPool emitting unique event ${event.id.take(8)}")
                        _incomingEvents.emit(event)
                    }
                }
            }
        }
    }

    /**
     * Subscribes all relays in the pool to a specific group's updates.
     */
    fun subscribeGroup(groupId: String) {
        subscribedGroups.add(groupId)
        clients.forEach { client ->
            client.subscribeGroup(groupId)
        }
    }

    /**
     * Broadcasts a signed event to all relays in the pool.
     */
    fun broadcast(event: NostrEvent) {
        seenEventIds.add(event.id)
        clients.forEach { client ->
            client.publishEvent(event)
        }
    }

    fun disconnectAll() {
        clients.forEach { it.disconnect() }
        clients.clear()
    }
}
