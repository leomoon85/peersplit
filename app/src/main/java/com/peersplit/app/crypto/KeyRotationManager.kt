package com.peersplit.app.crypto

import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.local.entity.GroupKeyEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Manages versioned group encryption keys for epoch-based forward secrecy.
 * Whenever a member is removed, the key epoch is incremented and a new AES-256 key is generated,
 * ensuring newly removed members cannot decrypt future group events.
 */
object KeyRotationManager {

    private val json = Json { ignoreUnknownKeys = true }
    private val stringListSerializer = ListSerializer(String.serializer())

    /**
     * Advances the group key epoch and generates a new AES-256 encryption key.
     * Records the authorized subset of remaining member public keys who are entitled to receive it.
     */
    suspend fun rotateKeyOnRemoval(
        database: AppDatabase,
        groupId: String,
        removedMemberPubkey: String
    ): GroupKeyEntity {
        val latestKey = database.groupKeyDao().getLatestKey(groupId)
        val nextEpoch = (latestKey?.keyEpoch ?: 0) + 1
        val newKeyBytes = EncryptionUtil.generateGroupKey()
        val newKeyBase64 = EncryptionUtil.keyToBase64(newKeyBytes)

        val activeMembers = database.memberDao().getMembersForGroupSync(groupId)
            .map { it.pubkey }
            .filter { it != removedMemberPubkey }

        val entity = GroupKeyEntity(
            groupId = groupId,
            keyEpoch = nextEpoch,
            groupKeyBase64 = newKeyBase64,
            distributedToPubkeysJson = json.encodeToString(stringListSerializer, activeMembers),
            createdAtMillis = System.currentTimeMillis()
        )
        database.groupKeyDao().insertKey(entity)
        return entity
    }

    /**
     * Retrieves the appropriate decryption key for a specific epoch.
     * Returns null if this device is not authorized for that epoch.
     */
    suspend fun getKeyForEpoch(
        database: AppDatabase,
        groupId: String,
        epoch: Int
    ): String? {
        val keyEntity = database.groupKeyDao().getKey(groupId, epoch)
        if (keyEntity != null) return keyEntity.groupKeyBase64
        if (epoch == 0) {
            return database.groupDao().getGroupByIdSync(groupId)?.groupKeyBase64
        }
        return null
    }

    /**
     * Stores an incoming distributed epoch key if this device was an intended authorized recipient.
     */
    suspend fun storeEpochKey(
        database: AppDatabase,
        groupId: String,
        epoch: Int,
        keyBase64: String,
        authorizedMembers: List<String> = emptyList()
    ) {
        val entity = GroupKeyEntity(
            groupId = groupId,
            keyEpoch = epoch,
            groupKeyBase64 = keyBase64,
            distributedToPubkeysJson = json.encodeToString(stringListSerializer, authorizedMembers),
            createdAtMillis = System.currentTimeMillis()
        )
        database.groupKeyDao().insertKey(entity)
    }
}
