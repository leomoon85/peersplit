package com.peersplit.app.blockchain

import com.peersplit.app.crypto.KeyManager
import org.junit.Assert.*
import org.junit.Test

class BlockchainEngineTest {

    @Test
    fun testGenesisBlockCreation() {
        val aliceKey = KeyManager.generateKeyPair()
        val (groupId, genesis) = BlockchainEngine.createGenesisBlock(
            groupName = "Trip to Tokyo",
            currency = "JPY",
            creatorPubkey = aliceKey.publicKeyHex,
            creatorDisplayName = "Alice",
            privateKeyHex = aliceKey.privateKeyHex
        )

        assertEquals(0L, genesis.index)
        assertEquals(Block.GENESIS_PREV_HASH, genesis.prevHash)
        assertEquals(BlockType.GENESIS, genesis.blockType)
        assertTrue("Genesis block must have valid hash", genesis.hasValidHash())
        assertTrue("Genesis block must have valid signature", genesis.hasValidSignature())
        assertTrue("Genesis block must pass full integrity check", genesis.verifyIntegrity())
    }

    @Test
    fun testChainSequentialAppendAndValidation() {
        val alice = KeyManager.generateKeyPair()
        val bob = KeyManager.generateKeyPair()

        // 1. Genesis
        val (groupId, genesis) = BlockchainEngine.createGenesisBlock(
            groupName = "Apartment Expenses",
            currency = "USD",
            creatorPubkey = alice.publicKeyHex,
            creatorDisplayName = "Alice",
            privateKeyHex = alice.privateKeyHex
        )

        // 2. Member join
        val memberJoinBlock = BlockchainEngine.createMemberJoinBlock(
            groupId = groupId,
            prevBlock = genesis,
            newMemberPubkey = bob.publicKeyHex,
            newMemberName = "Bob",
            authorPubkey = alice.publicKeyHex,
            authorPrivateKeyHex = alice.privateKeyHex
        )

        // 3. Expense block
        val splits = listOf(
            SplitItem(userPubkey = alice.publicKeyHex, amount = 30.0),
            SplitItem(userPubkey = bob.publicKeyHex, amount = 30.0)
        )
        val expenseBlock = BlockchainEngine.createExpenseBlock(
            groupId = groupId,
            prevBlock = memberJoinBlock,
            description = "Groceries",
            totalAmount = 60.0,
            currency = "USD",
            payerPubkey = alice.publicKeyHex,
            splits = splits,
            category = "Food",
            note = "",
            authorPubkey = alice.publicKeyHex,
            authorPrivateKeyHex = alice.privateKeyHex
        )

        val chain = listOf(genesis, memberJoinBlock, expenseBlock)
        val validation = BlockchainEngine.validateChain(chain)
        assertTrue("Chain should be completely valid", validation is ValidationResult.Valid)
    }

    @Test
    fun testTamperDetection() {
        val alice = KeyManager.generateKeyPair()
        val (groupId, genesis) = BlockchainEngine.createGenesisBlock(
            groupName = "Road Trip",
            currency = "USD",
            creatorPubkey = alice.publicKeyHex,
            creatorDisplayName = "Alice",
            privateKeyHex = alice.privateKeyHex
        )

        val splits = listOf(SplitItem(userPubkey = alice.publicKeyHex, amount = 50.0))
        val expenseBlock = BlockchainEngine.createExpenseBlock(
            groupId = groupId,
            prevBlock = genesis,
            description = "Gasoline",
            totalAmount = 50.0,
            currency = "USD",
            payerPubkey = alice.publicKeyHex,
            splits = splits,
            category = "Transport",
            note = "",
            authorPubkey = alice.publicKeyHex,
            authorPrivateKeyHex = alice.privateKeyHex
        )

        // Malicious user attempts to edit expense block payload locally
        val tamperedPayload = expenseBlock.payloadJson.replace("50.0", "150.0")
        val tamperedBlock = expenseBlock.copy(payloadJson = tamperedPayload)

        val tamperedChain = listOf(genesis, tamperedBlock)
        val validation = BlockchainEngine.validateChain(tamperedChain)

        assertTrue("Tampered chain must be flagged as Invalid", validation is ValidationResult.Invalid)
        val invalidResult = validation as ValidationResult.Invalid
        assertEquals(1L, invalidResult.blockIndex)
    }
}
