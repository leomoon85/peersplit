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
        private const val MAX_SEEN_EVENT_IDS = 5000

        val DEFAULT_RELAYS = listOf(
            "wss://relay.damus.io",
            "wss://nos.lol",
            "wss://relay.primal.net",
            "wss://nostr.mom",
            "wss://relay.snort.social"
        )
    }

    private val clients = Collections.synchronizedList(mutableListOf<NostrClient>())
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Bounded LRU set of seen event IDs across all relays
    private val seenEventIds: MutableSet<String> = Collections.synchronizedSet(
        Collections.newSetFromMap(
            object : LinkedHashMap<String, Boolean>(1024, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?): Boolean {
                    return size > MAX_SEEN_EVENT_IDS
                }
            }
        )
    )
    private val subscribedGroups = Collections.synchronizedSet(mutableSetOf<String>())

    private val _incomingEvents = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 128)
    val incomingEvents: SharedFlow<NostrEvent> = _incomingEvents.asSharedFlow()

    private val _ackedEventIds = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 128)
    val ackedEventIds: SharedFlow<String> = _ackedEventIds.asSharedFlow()

    fun connectAll() {
        synchronized(clients) {
            if (clients.isNotEmpty()) {
                clients.forEach { it.connect() }
                return
            }

            relayUrls.forEach { url ->
                val client = NostrClient(url)
                clients.add(client)
                client.connect()

                scope.launch {
                    client.eventsFlow.collect { event ->
                        // Deduplicate across relays using bounded LRU set
                        if (seenEventIds.add(event.id)) {
                            Log.d(TAG, "RelayPool emitting unique event ${event.id.take(8)}")
                            _incomingEvents.emit(event)
                        }
                    }
                }

                scope.launch {
                    client.acksFlow.collect { eventId ->
                        _ackedEventIds.emit(eventId)
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
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { client ->
            client.subscribeGroup(groupId)
        }
    }

    /**
     * Broadcasts a signed event to all relays in the pool.
     */
    fun broadcast(event: NostrEvent): Boolean {
        val snapshot = synchronized(clients) { clients.toList() }
        var anySent = false
        snapshot.forEach { client ->
            if (client.publishEvent(event)) {
                anySent = true
            }
        }
        return anySent
    }

    fun disconnectAll() {
        val snapshot = synchronized(clients) {
            val list = clients.toList()
            clients.clear()
            list
        }
        snapshot.forEach { it.disconnect() }
    }
}
