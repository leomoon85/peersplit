package com.peersplit.app.data.model

import com.peersplit.app.blockchain.SplitItem

data class Group(
    val id: String,
    val name: String,
    val currency: String,
    val creatorPubkey: String,
    val groupKeyBase64: String,
    val memberCount: Int = 0,
    val userNetBalance: Double = 0.0,
    val blockCount: Long = 0,
    val isLedgerValid: Boolean = true,
    val isSecret: Boolean = false,
    val secretPinHash: String? = null,
    val isPersonalSolo: Boolean = false,
    val monthlyBudget: Double = 0.0,
    val isArchived: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

data class Member(
    val pubkey: String,
    val displayName: String,
    val isCurrentUser: Boolean = false,
    val netBalance: Double = 0.0,
    val upiId: String? = null
)

data class ExpenseItem(
    val id: String,
    val description: String,
    val totalAmount: Double,
    val currency: String,
    val payerPubkey: String,
    val payerName: String,
    val timestamp: Long,
    val userShare: Double, // what current user paid or owes for this expense
    val blockIndex: Long,
    val category: String = "General",
    val note: String = "",
    val splitType: String = "EQUAL",
    val splits: List<SplitItem> = emptyList(),
    val payers: List<SplitItem> = emptyList(),
    val blockHash: String = "",
    val signature: String = "",
    val isVoided: Boolean = false
)
