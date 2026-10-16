package com.peersplit.app.data.backup

import android.content.Context
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.local.entity.BlockEntity
import com.peersplit.app.data.local.entity.GroupEntity
import com.peersplit.app.data.local.entity.MemberEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class BackupIdentity(
    val privateKeyHex: String,
    val publicKeyHex: String,
    val displayName: String,
    val upiId: String? = null
)

@Serializable
data class BackupPayload(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val identity: BackupIdentity,
    val groups: List<BackupGroup>,
    val members: List<BackupMember>,
    val blocks: List<BackupBlock>
)

@Serializable
data class BackupGroup(
    val id: String,
    val name: String,
    val currency: String,
    val creatorPubkey: String,
    val groupKeyBase64: String,
    val isSecret: Boolean,
    val secretPinHash: String?,
    val isPersonalSolo: Boolean,
    val createdAt: Long
)

@Serializable
data class BackupMember(
    val groupId: String,
    val pubkey: String,
    val displayName: String,
    val upiId: String? = null
)

@Serializable
data class BackupBlock(
    val blockHash: String,
    val index: Long,
    val groupId: String,
    val prevHash: String,
    val timestamp: Long,
    val creatorPubkey: String,
    val blockType: String,
    val payloadJson: String,
    val signature: String,
    val isVerified: Boolean
)

object BackupManager {

    private val MAGIC_HEADER = "PEERBAK1".toByteArray(Charsets.US_ASCII)
    private const val SALT_LENGTH = 16
    private const val IV_LENGTH = 12
    private const val KEY_LENGTH_BITS = 256
    private const val ITERATION_COUNT = 65536

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = false }
    private val secureRandom = SecureRandom()

    /**
     * Creates an encrypted byte array containing all groups, blocks, members, and keys.
     */
    suspend fun createEncryptedBackup(context: Context, database: AppDatabase, password: String): ByteArray {
        val identity = KeyManager.getOrCreateIdentity(context)
        val privKeyHex = KeyManager.getPrivateKeyHex(context)

        val groups = database.groupDao().getGroupsSync().map {
            BackupGroup(
                id = it.id,
                name = it.name,
                currency = it.currency,
                creatorPubkey = it.creatorPubkey,
                groupKeyBase64 = it.groupKeyBase64,
                isSecret = it.isSecret,
                secretPinHash = it.secretPinHash,
                isPersonalSolo = it.isPersonalSolo,
                createdAt = it.createdAt
            )
        }

        val allMembers = mutableListOf<BackupMember>()
        val allBlocks = mutableListOf<BackupBlock>()

        for (g in groups) {
            val members = database.memberDao().getMembersForGroupSync(g.id).map {
                BackupMember(groupId = it.groupId, pubkey = it.pubkey, displayName = it.displayName, upiId = it.upiId)
            }
            allMembers.addAll(members)

            val blocks = database.blockDao().getBlocksForGroupSync(g.id).map {
                BackupBlock(
                    blockHash = it.blockHash,
                    index = it.index,
                    groupId = it.groupId,
                    prevHash = it.prevHash,
                    timestamp = it.timestamp,
                    creatorPubkey = it.creatorPubkey,
                    blockType = it.blockType,
                    payloadJson = it.payloadJson,
                    signature = it.signature,
                    isVerified = it.isVerified
                )
            }
            allBlocks.addAll(blocks)
        }

        val payload = BackupPayload(
            identity = BackupIdentity(
                privateKeyHex = privKeyHex,
                publicKeyHex = identity.publicKeyHex,
                displayName = identity.displayName,
                upiId = identity.upiId
            ),
            groups = groups,
            members = allMembers,
            blocks = allBlocks
        )

        val plainJsonBytes = json.encodeToString(payload).toByteArray(Charsets.UTF_8)

        // Derive key from password with PBKDF2
        val salt = ByteArray(SALT_LENGTH).also { secureRandom.nextBytes(it) }
        val iv = ByteArray(IV_LENGTH).also { secureRandom.nextBytes(it) }

        val keySpec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plainJsonBytes)

        // Packaging: MAGIC (8) + SALT (16) + IV (12) + CIPHERTEXT
        return MAGIC_HEADER + salt + iv + ciphertext
    }

    /**
     * Decrypts and restores backup data into the local Room database and KeyManager.
     */
    suspend fun restoreFromBackup(context: Context, database: AppDatabase, backupBytes: ByteArray, password: String): Boolean {
        if (backupBytes.size < MAGIC_HEADER.size + SALT_LENGTH + IV_LENGTH + 16) {
            throw IllegalArgumentException("Invalid backup file: file too small or corrupted")
        }

        // Validate magic header
        for (i in MAGIC_HEADER.indices) {
            if (backupBytes[i] != MAGIC_HEADER[i]) {
                throw IllegalArgumentException("Unrecognized backup file format")
            }
        }

        var offset = MAGIC_HEADER.size
        val salt = backupBytes.copyOfRange(offset, offset + SALT_LENGTH)
        offset += SALT_LENGTH
        val iv = backupBytes.copyOfRange(offset, offset + IV_LENGTH)
        offset += IV_LENGTH
        val ciphertext = backupBytes.copyOfRange(offset, backupBytes.size)

        // Derive key
        val keySpec = PBEKeySpec(password.toCharArray(), salt, ITERATION_COUNT, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val keyBytes = factory.generateSecret(keySpec).encoded
        val secretKey = SecretKeySpec(keyBytes, "AES")

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))

        val decryptedJsonBytes = try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            throw IllegalArgumentException("Incorrect password or corrupted backup file", e)
        }

        val jsonStr = String(decryptedJsonBytes, Charsets.UTF_8)
        val payload = json.decodeFromString(BackupPayload.serializer(), jsonStr)

        // 1. Restore Identity
        KeyManager.restoreIdentity(
            context = context,
            privHex = payload.identity.privateKeyHex,
            pubHex = payload.identity.publicKeyHex,
            displayName = payload.identity.displayName,
            upiId = payload.identity.upiId
        )

        // 2. Restore Database Entities
        payload.groups.forEach { g ->
            database.groupDao().insertGroup(
                GroupEntity(
                    id = g.id,
                    name = g.name,
                    currency = g.currency,
                    creatorPubkey = g.creatorPubkey,
                    groupKeyBase64 = g.groupKeyBase64,
                    isSecret = g.isSecret,
                    secretPinHash = g.secretPinHash,
                    isPersonalSolo = g.isPersonalSolo,
                    createdAt = g.createdAt
                )
            )
        }

        payload.members.forEach { m ->
            database.memberDao().insertMember(
                MemberEntity(
                    groupId = m.groupId,
                    pubkey = m.pubkey,
                    displayName = m.displayName,
                    upiId = m.upiId
                )
            )
        }

        payload.blocks.forEach { b ->
            database.blockDao().insertBlock(
                BlockEntity(
                    blockHash = b.blockHash,
                    index = b.index,
                    groupId = b.groupId,
                    prevHash = b.prevHash,
                    timestamp = b.timestamp,
                    creatorPubkey = b.creatorPubkey,
                    blockType = b.blockType,
                    payloadJson = b.payloadJson,
                    signature = b.signature,
                    isVerified = b.isVerified
                )
            )
        }

        // 3. Re-register restored groups with P2P Sync Manager
        try {
            val syncManager = com.peersplit.app.PeerSplitApp.instance.syncManager
            payload.groups.forEach { g ->
                syncManager.monitorGroup(g.id)
            }
        } catch (_: Exception) {
            // Ignored in unit tests where PeerSplitApp singleton is not initialized
        }

        return true
    }
}
