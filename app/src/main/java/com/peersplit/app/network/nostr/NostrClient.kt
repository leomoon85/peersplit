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
import java.util.concurrent.TimeUnit

/**
 * WebSocket client connecting to an individual Nostr relay.
 */
class NostrClient(
    val relayUrl: String,
    private val client: OkHttpClient = defaultClient()
) {
    companion object {
        private const val TAG = "NostrClient"

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
    private var isConnected = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val activeSubscriptions = mutableSetOf<String>()

    private val _eventsFlow = MutableSharedFlow<NostrEvent>(replay = 0, extraBufferCapacity = 64)
    val eventsFlow: SharedFlow<NostrEvent> = _eventsFlow.asSharedFlow()

    private val json = Json { ignoreUnknownKeys = true }

    fun connect() {
        if (webSocket != null) return

        val request = Request.Builder().url(relayUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Connected to relay: $relayUrl")
                isConnected = true
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
            delay(5000)
            Log.d(TAG, "Attempting reconnect to $relayUrl")
            connect()
        }
    }

    private fun handleMessage(text: String) {
        try {
            val element = json.parseToJsonElement(text)
            if (element is JsonArray && element.size >= 3) {
                val messageType = element[0].jsonPrimitive.content
                if (messageType == "EVENT") {
                    val eventElement = element[2]
                    val event = json.decodeFromJsonElement(NostrEvent.serializer(), eventElement)
                    scope.launch {
                        _eventsFlow.emit(event)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse message from $relayUrl: ${e.message}")
        }
    }

    /**
     * Subscribes to events for a specific group ID tag.
     */
    fun subscribeGroup(groupId: String, subscriptionId: String = "sub_$groupId") {
        if (activeSubscriptions.contains(subscriptionId)) return
        activeSubscriptions.add(subscriptionId)

        // Nostr REQ format: ["REQ", subscription_id, {"#g": [groupId], "kinds": [21042]}]
        val reqMsg = "[\"REQ\",\"$subscriptionId\",{\"#g\":[\"$groupId\"],\"kinds\":[${NostrEvent.KIND_PEERSPLIT_BLOCK}]}]"
        webSocket?.send(reqMsg)
    }

    /**
     * Publishes a signed NostrEvent to the relay.
     */
    fun publishEvent(event: NostrEvent): Boolean {
        val eventJson = json.encodeToString(NostrEvent.serializer(), event)
        val msg = "[\"EVENT\",$eventJson]"
        return webSocket?.send(msg) ?: false
    }

    fun disconnect() {
        webSocket?.close(1000, "Normal Closure")
        webSocket = null
        isConnected = false
    }
}
