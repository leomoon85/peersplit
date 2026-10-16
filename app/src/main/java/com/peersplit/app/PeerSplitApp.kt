package com.peersplit.app

import android.app.Application
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.repository.ExpenseRepository
import com.peersplit.app.network.nostr.RelayPool
import com.peersplit.app.network.sync.P2PSyncManager

import com.peersplit.app.ui.theme.ThemeManager

class PeerSplitApp : Application() {

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

        // Initialize Theme Mode
        ThemeManager.init(this)

        // Initialize Cryptographic Identity
        KeyManager.getOrCreateIdentity(this)

        // Initialize Database
        database = AppDatabase.getInstance(this)

        // Initialize P2P Relay Pool and Sync Manager
        relayPool = RelayPool()
        syncManager = P2PSyncManager(this, database, relayPool)
        syncManager.initialize()

        // Initialize Repository
        repository = ExpenseRepository(this, database, syncManager)
    }

    companion object {
        lateinit var instance: PeerSplitApp
            private set
    }
}
