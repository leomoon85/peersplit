package com.peersplit.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockType

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey val id: String,
    val name: String,
    val currency: String,
    val creatorPubkey: String,
    val groupKeyBase64: String, // AES-256 group encryption key
    val isSecret: Boolean = false,
    val secretPinHash: String? = null,
    val isPersonalSolo: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "members",
    indices = [Index(value = ["groupId", "pubkey"], unique = true)]
)
data class MemberEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val groupId: String,
    val pubkey: String,
    val displayName: String
)

@Entity(
    tableName = "blocks",
    indices = [
        Index(value = ["groupId", "index"], unique = true),
        Index(value = ["groupId", "blockHash"], unique = true)
    ]
)
data class BlockEntity(
    @PrimaryKey val blockHash: String,
    val index: Long,
    val groupId: String,
    val prevHash: String,
    val timestamp: Long,
    val creatorPubkey: String,
    val blockType: String,
    val payloadJson: String,
    val signature: String,
    val isVerified: Boolean = true
) {
    fun toDomainBlock(): Block {
        return Block(
            index = index,
            groupId = groupId,
            prevHash = prevHash,
            timestamp = timestamp,
            creatorPubkey = creatorPubkey,
            blockType = BlockType.valueOf(blockType),
            payloadJson = payloadJson,
            blockHash = blockHash,
            signature = signature
        )
    }

    companion object {
        fun fromDomainBlock(block: Block, isVerified: Boolean = true): BlockEntity {
            return BlockEntity(
                blockHash = block.blockHash,
                index = block.index,
                groupId = block.groupId,
                prevHash = block.prevHash,
                timestamp = block.timestamp,
                creatorPubkey = block.creatorPubkey,
                blockType = block.blockType.name,
                payloadJson = block.payloadJson,
                signature = block.signature,
                isVerified = isVerified
            )
        }
    }
}
