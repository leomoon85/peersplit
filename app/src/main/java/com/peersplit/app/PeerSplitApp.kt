package com.peersplit.app

import android.app.Application
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.repository.ExpenseRepository
import com.peersplit.app.network.nostr.RelayPool
import com.peersplit.app.network.sync.P2PSyncManager

import com.peersplit.app.ui.theme.ThemeManager

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PeerSplitApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var database: AppDatabase
        private set

    lateinit var relayPool: RelayPool
        private set

    lateinit var syncManager: P2PSyncManager
        private set

    lateinit var repository: ExpenseRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        // Initialize Theme Mode (fast in-memory preference read)
        ThemeManager.init(this)

        // Initialize Database builder (lazy SQLite open)
        database = AppDatabase.getInstance(this)

        // Initialize P2P Relay Pool, Sync Manager, and Repository
        relayPool = RelayPool()
        syncManager = P2PSyncManager(this, database, relayPool)
        repository = ExpenseRepository(this, database, syncManager)

        // Offload Keystore identity init, SQLite warm-up, and WebSocket connections off the Main Thread
        appScope.launch(Dispatchers.IO) {
            KeyManager.getOrCreateIdentity(this@PeerSplitApp)
            syncManager.initialize()
        }
    }

    companion object {
        lateinit var instance: PeerSplitApp
            private set
    }
}
