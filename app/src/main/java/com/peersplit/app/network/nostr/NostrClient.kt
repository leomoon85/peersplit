package com.peersplit.app.network.nostr

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.Collections
import java.util.concurrent.TimeUnit

/**
 * WebSocket client connecting to an individual Nostr relay with automatic
 * reconnection, subscription replay, and offline event queuing.
 */
class NostrClient(
    val relayUrl: String,
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        private const val TAG = "NostrClient"
        private const val MAX_PENDING_EVENTS = 256

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .pingInterval(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    private var webSocket: WebSocket? = null
    @Volatile private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    // Map of subscriptionId to raw JSON REQ message
    private val activeSubscriptions = Collections.synchronizedMap(mutableMapOf<String, String>())
    // Bounded queue of pending events to send once connection is established
    private val pendingEvents = Collections.synchronizedList(mutableListOf<NostrEvent>())

    private val _eventsFlow = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 64)
    val eventsFlow: SharedFlow<NostrEvent> = _eventsFlow.asSharedFlow()

    private val _acksFlow = MutableSharedFlow<String>(replay = 0, extraBufferCapacity = 64)
    val acksFlow: SharedFlow<String> = _acksFlow.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }

    private fun enqueuePendingEvent(event: NostrEvent) {
        synchronized(pendingEvents) {
            if (pendingEvents.none { it.id == event.id }) {
                if (pendingEvents.size >= MAX_PENDING_EVENTS) {
                    pendingEvents.removeAt(0)
                }
                pendingEvents.add(event)
            }
        }
    }

    fun connect() {
        if (webSocket != null) return

        val request = Request.Builder().url(relayUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to relay: $relayUrl")
                isConnected = true

                // Re-send all active subscriptions to the relay
                synchronized(activeSubscriptions) {
                    for ((subId, reqMsg) in activeSubscriptions) {
                        webSocket.send(reqMsg)
                        Log.d(TAG, "Sent subscription $subId to $relayUrl")
                    }
                }

                // Flush pending outgoing events
                synchronized(pendingEvents) {
                    val iterator = pendingEvents.iterator()
                    while (iterator.hasNext()) {
                        val event = iterator.next()
                        val eventJson = json.encodeToString(NostrEvent.serializer(), event)
                        webSocket.send("[\"EVENT\",$eventJson]")
                        Log.d(TAG, "Flushed queued event ${event.id.take(8)} to $relayUrl")
                        iterator.remove()
                    }
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Relay failure on $relayUrl: ${t.message}")
                isConnected = false
                reconnect()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Relay closed: $relayUrl ($reason)")
                isConnected = false
                reconnect()
            }
        })
    }

    private fun reconnect() {
        webSocket = null
        scope.launch {
            delay(4000)
            Log.d(TAG, "Attempting reconnect to $relayUrl")
            connect()
        }
    }

    private fun handleMessage(text: String) {
        try {
            val element = json.parseToJsonElement(text)
            if (element is JsonArray && element.size >= 2) {
                val messageType = element[0].jsonPrimitive.content
                when (messageType) {
                    "EVENT" -> {
                        if (element.size >= 3) {
                            val eventElement = element[2]
                            val event = json.decodeFromJsonElement(NostrEvent.serializer(), eventElement)
                            Log.d(TAG, "Received EVENT ${event.id.take(8)} from $relayUrl")
                            scope.launch {
                                _eventsFlow.emit(event)
                            }
                        }
                    }
                    "OK" -> {
                        val eventId = element.getOrNull(1)?.jsonPrimitive?.content
                        val accepted = element.getOrNull(2)?.jsonPrimitive?.content
                        val msg = element.getOrNull(3)?.jsonPrimitive?.content
                        Log.d(TAG, "Relay $relayUrl OK for ${eventId?.take(8)}: accepted=$accepted msg=$msg")
                        if (!eventId.isNullOrBlank() && accepted.equals("true", ignoreCase = true)) {
                            scope.launch {
                                _acksFlow.emit(eventId)
                            }
                        }
                    }
                    "NOTICE" -> {
                        val notice = element.getOrNull(1)?.jsonPrimitive?.content
                        Log.d(TAG, "Relay $relayUrl NOTICE: $notice")
                    }
                    "EOSE" -> {
                        val subId = element.getOrNull(1)?.jsonPrimitive?.content
                        Log.d(TAG, "Relay $relayUrl EOSE for $subId (stored history received)")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message from $relayUrl: ${e.message}")
        }
    }

    /**
     * Subscribes to events for a specific group ID tag.
     * Persists subscription so it will automatically be sent whenever the socket opens/reconnects.
     */
    fun subscribeGroup(groupId: String, subscriptionId: String = "sub_$groupId") {
        // Nostr REQ format with dual #g and #t filters for maximum relay compatibility
        val reqMsg = "[\"REQ\",\"$subscriptionId\",{\"#g\":[\"$groupId\"],\"kinds\":[${NostrEvent.KIND_PEERSPLIT_BLOCK}]},{\"#t\":[\"peersplit_$groupId\"],\"kinds\":[${NostrEvent.KIND_PEERSPLIT_BLOCK}]}]"
        activeSubscriptions[subscriptionId] = reqMsg

        if (isConnected && webSocket != null) {
            webSocket?.send("[\"CLOSE\",\"$subscriptionId\"]")
            val sent = webSocket?.send(reqMsg) ?: false
            if (!sent) {
                isConnected = false
                reconnect()
            } else {
                Log.d(TAG, "Directly sent subscription $subscriptionId to $relayUrl")
            }
        } else {
            connect()
        }
    }

    /**
     * Publishes a signed NostrEvent to the relay.
     * If not connected yet, queues the event to send immediately on connection.
     */
    fun publishEvent(event: NostrEvent): Boolean {
        if (isConnected && webSocket != null) {
            val eventJson = json.encodeToString(NostrEvent.serializer(), event)
            val msg = "[\"EVENT\",$eventJson]"
            val sent = webSocket?.send(msg) ?: false
            if (!sent) {
                enqueuePendingEvent(event)
                isConnected = false
                reconnect()
            } else {
                Log.d(TAG, "Published event ${event.id.take(8)} to $relayUrl (sent=$sent)")
            }
            return sent
        } else {
            // Queue for when socket connects
            enqueuePendingEvent(event)
            connect()
            Log.d(TAG, "Queued event ${event.id.take(8)} for $relayUrl (waiting for connection)")
            return true
        }
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal Closure")
        webSocket = null
        isConnected = false
    }
}
