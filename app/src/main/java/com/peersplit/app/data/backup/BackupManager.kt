package com.peersplit.app.data.backup

import android.content.Context
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.AppDatabase
import com.peersplit.app.data.local.entity.BlockEntity
import com.peersplit.app.data.local.entity.GroupEntity
import com.peersplit.app.data.local.entity.GroupKeyEntity
import com.peersplit.app.data.local.entity.GroupProtocolEntity
import com.peersplit.app.data.local.entity.MemberEntity
import com.peersplit.app.data.local.entity.OperationDependencyEntity
import com.peersplit.app.data.local.entity.SignedOperationEntity
import androidx.room.withTransaction
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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
    val upiId: String? = null,
    val phone: String? = null
)

@Serializable
data class BackupSignedOperation(
    val operationId: String,
    val protocolVersion: Int,
    val groupId: String,
    val authorPublicKey: String,
    val parentsJson: String,
    val logicalClock: Long,
    val payloadType: String,
    val payloadJson: String,
    val signature: String,
    val keyEpoch: Int = 0,
    val createdAtMillis: Long = 0L,
    val receivedAtMillis: Long = 0L,
    val status: String = "ACCEPTED",
    val rejectionReason: String? = null
)

@Serializable
data class BackupGroupKey(
    val groupId: String,
    val keyEpoch: Int,
    val groupKeyBase64: String,
    val distributedToPubkeysJson: String = "[]",
    val createdAtMillis: Long = 0L
)

@Serializable
data class BackupGroupProtocol(
    val groupId: String,
    val protocolVersion: Int,
    val genesisOperationId: String? = null,
    val currentKeyEpoch: Int = 0,
    val latestStateDigest: String = "",
    val maxLogicalClock: Long = 0L,
    val headOperationIdsJson: String = "[]",
    val v1MigrationStatus: String = "NONE",
    val v1MigratedTipHash: String? = null,
    val updatedAtMillis: Long = 0L
)

@Serializable
data class BackupPayload(
    val version: Int = 2,
    val timestamp: Long = System.currentTimeMillis(),
    val identity: BackupIdentity,
    val groups: List<BackupGroup>,
    val members: List<BackupMember>,
    val blocks: List<BackupBlock>,
    val signedOperations: List<BackupSignedOperation> = emptyList(),
    val groupKeys: List<BackupGroupKey> = emptyList(),
    val groupProtocols: List<BackupGroupProtocol> = emptyList()
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
    val monthlyBudget: Double = 0.0,
    val isArchived: Boolean = false,
    val createdAt: Long
)

@Serializable
data class BackupMember(
    val groupId: String,
    val pubkey: String,
    val displayName: String,
    val upiId: String? = null,
    val phone: String? = null
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
                monthlyBudget = it.monthlyBudget,
                isArchived = it.isArchived,
                createdAt = it.createdAt
            )
        }

        val allMembers = mutableListOf<BackupMember>()
        val allBlocks = mutableListOf<BackupBlock>()
        val allSignedOperations = mutableListOf<BackupSignedOperation>()
        val allGroupKeys = mutableListOf<BackupGroupKey>()
        val allGroupProtocols = mutableListOf<BackupGroupProtocol>()

        for (g in groups) {
            val members = database.memberDao().getMembersForGroupSync(g.id).map {
                BackupMember(
                    groupId = it.groupId,
                    pubkey = it.pubkey,
                    displayName = it.displayName,
                    upiId = it.upiId,
                    phone = it.phone
                )
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

            val signedOps = database.signedOperationDao().getOperationsForGroupSync(g.id).map {
                BackupSignedOperation(
                    operationId = it.operationId,
                    protocolVersion = it.protocolVersion,
                    groupId = it.groupId,
                    authorPublicKey = it.authorPublicKey,
                    parentsJson = it.parentsJson,
                    logicalClock = it.logicalClock,
                    payloadType = it.payloadType,
                    payloadJson = it.payloadJson,
                    signature = it.signature,
                    keyEpoch = it.keyEpoch,
                    createdAtMillis = it.createdAtMillis,
                    receivedAtMillis = it.receivedAtMillis,
                    status = it.status,
                    rejectionReason = it.rejectionReason
                )
            }
            allSignedOperations.addAll(signedOps)

            val keys = database.groupKeyDao().getAllKeysForGroup(g.id).map {
                BackupGroupKey(
                    groupId = it.groupId,
                    keyEpoch = it.keyEpoch,
                    groupKeyBase64 = it.groupKeyBase64,
                    distributedToPubkeysJson = it.distributedToPubkeysJson,
                    createdAtMillis = it.createdAtMillis
                )
            }
            allGroupKeys.addAll(keys)

            val proto = database.groupProtocolDao().getGroupProtocolSync(g.id)
            if (proto != null) {
                allGroupProtocols.add(
                    BackupGroupProtocol(
                        groupId = proto.groupId,
                        protocolVersion = proto.protocolVersion,
                        genesisOperationId = proto.genesisOperationId,
                        currentKeyEpoch = proto.currentKeyEpoch,
                        latestStateDigest = proto.latestStateDigest,
                        maxLogicalClock = proto.maxLogicalClock,
                        headOperationIdsJson = proto.headOperationIdsJson,
                        v1MigrationStatus = proto.v1MigrationStatus,
                        v1MigratedTipHash = proto.v1MigratedTipHash,
                        updatedAtMillis = proto.updatedAtMillis
                    )
                )
            }
        }

        val payload = BackupPayload(
            version = 2,
            timestamp = System.currentTimeMillis(),
            identity = BackupIdentity(
                privateKeyHex = privKeyHex,
                publicKeyHex = identity.publicKeyHex,
                displayName = identity.displayName,
                upiId = identity.upiId,
                phone = identity.phone
            ),
            groups = groups,
            members = allMembers,
            blocks = allBlocks,
            signedOperations = allSignedOperations,
            groupKeys = allGroupKeys,
            groupProtocols = allGroupProtocols
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

        // 1. Restore Database Entities atomically within a transaction
        database.withTransaction {
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
                        monthlyBudget = g.monthlyBudget,
                        isArchived = g.isArchived,
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
                        upiId = m.upiId,
                        phone = m.phone
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

            // Restore V2 Group Keys
            payload.groupKeys.forEach { k ->
                database.groupKeyDao().insertKey(
                    GroupKeyEntity(
                        groupId = k.groupId,
                        keyEpoch = k.keyEpoch,
                        groupKeyBase64 = k.groupKeyBase64,
                        distributedToPubkeysJson = k.distributedToPubkeysJson,
                        createdAtMillis = k.createdAtMillis
                    )
                )
            }

            // Restore V2 Group Protocols
            payload.groupProtocols.forEach { p ->
                database.groupProtocolDao().upsertGroupProtocol(
                    GroupProtocolEntity(
                        groupId = p.groupId,
                        protocolVersion = p.protocolVersion,
                        genesisOperationId = p.genesisOperationId,
                        currentKeyEpoch = p.currentKeyEpoch,
                        latestStateDigest = p.latestStateDigest,
                        maxLogicalClock = p.maxLogicalClock,
                        headOperationIdsJson = p.headOperationIdsJson,
                        v1MigrationStatus = p.v1MigrationStatus,
                        v1MigratedTipHash = p.v1MigratedTipHash,
                        updatedAtMillis = p.updatedAtMillis
                    )
                )
            }

            // Restore V2 Signed Operations and Dependencies
            if (payload.signedOperations.isNotEmpty()) {
                val operationEntities = payload.signedOperations.map { op ->
                    SignedOperationEntity(
                        operationId = op.operationId,
                        protocolVersion = op.protocolVersion,
                        groupId = op.groupId,
                        authorPublicKey = op.authorPublicKey,
                        parentsJson = op.parentsJson,
                        logicalClock = op.logicalClock,
                        payloadType = op.payloadType,
                        payloadJson = op.payloadJson,
                        signature = op.signature,
                        keyEpoch = op.keyEpoch,
                        createdAtMillis = op.createdAtMillis,
                        receivedAtMillis = if (op.receivedAtMillis > 0L) op.receivedAtMillis else System.currentTimeMillis(),
                        status = op.status,
                        rejectionReason = op.rejectionReason
                    )
                }
                database.signedOperationDao().insertOperationsIgnore(operationEntities)

                val allDeps = mutableListOf<OperationDependencyEntity>()
                payload.signedOperations.forEach { op ->
                    try {
                        val parents = json.decodeFromString(ListSerializer(String.serializer()), op.parentsJson)
                        parents.forEach { parentId ->
                            allDeps.add(
                                OperationDependencyEntity(
                                    groupId = op.groupId,
                                    childOperationId = op.operationId,
                                    parentOperationId = parentId
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
                if (allDeps.isNotEmpty()) {
                    database.signedOperationDao().insertDependenciesIgnore(allDeps)
                }
            }
        }

        // 2. Restore Identity ONLY after DB transaction successfully commits
        KeyManager.restoreIdentity(
            context = context,
            privHex = payload.identity.privateKeyHex,
            pubHex = payload.identity.publicKeyHex,
            displayName = payload.identity.displayName,
            upiId = payload.identity.upiId,
            phone = payload.identity.phone
        )

        // 3. Re-register and reconcile restored groups with P2P Sync Manager
        try {
            val syncManager = com.peersplit.app.PeerSplitApp.instance.syncManager
            payload.groups.forEach { g ->
                syncManager.reconcileV2Group(g.id)
                syncManager.monitorGroup(g.id)
            }
        } catch (_: Exception) {
            // Ignored in unit tests where PeerSplitApp singleton is not initialized
        }

        return true
    }
}
