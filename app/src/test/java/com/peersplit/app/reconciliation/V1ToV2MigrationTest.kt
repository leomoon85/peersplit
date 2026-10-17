package com.peersplit.app.reconciliation

import com.peersplit.app.blockchain.Block
import com.peersplit.app.blockchain.BlockchainEngine
import com.peersplit.app.blockchain.DebtSettlementEngine
import com.peersplit.app.blockchain.ExpensePayload
import com.peersplit.app.blockchain.SettlementPayload
import com.peersplit.app.blockchain.SplitItem
import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.protocol.MoneyMath
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.ProtocolMemberInfo
import com.peersplit.app.protocol.ProtocolVersion
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class V1ToV2MigrationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testV1ChainToV2CheckpointMigration_preservesIdenticalBalances() {
        val alice = KeyManager.generateKeyPair()
        val bob = KeyManager.generateKeyPair()
        val charlie = KeyManager.generateKeyPair()

        // 1. Build a synthetic V1 linear blockchain: Genesis -> MemberJoins -> Expense -> Settlement
        val (genesisGroupId, genesisBlock) = BlockchainEngine.createGenesisBlock(
            groupName = "Trip to Goa",
            currency = "INR",
            creatorPubkey = alice.publicKeyHex,
            creatorDisplayName = "Alice",
            privateKeyHex = alice.privateKeyHex
        )

        val bobJoin = BlockchainEngine.createMemberJoinBlock(
            groupId = genesisGroupId,
            prevBlock = genesisBlock,
            newMemberPubkey = bob.publicKeyHex,
            newMemberName = "Bob",
            authorPubkey = alice.publicKeyHex,
            authorPrivateKeyHex = alice.privateKeyHex
        )

        val charlieJoin = BlockchainEngine.createMemberJoinBlock(
            groupId = genesisGroupId,
            prevBlock = bobJoin,
            newMemberPubkey = charlie.publicKeyHex,
            newMemberName = "Charlie",
            authorPubkey = alice.publicKeyHex,
            authorPrivateKeyHex = alice.privateKeyHex
        )

        // Alice pays ₹300 for dinner split equally (Alice: 100, Bob: 100, Charlie: 100)
        val exp1 = BlockchainEngine.createExpenseBlock(
            groupId = genesisGroupId,
            prevBlock = charlieJoin,
            description = "Dinner at Fisherman's Wharf",
            totalAmount = 300.0,
            currency = "INR",
            payerPubkey = alice.publicKeyHex,
            splits = listOf(
                SplitItem(alice.publicKeyHex, 100.0),
                SplitItem(bob.publicKeyHex, 100.0),
                SplitItem(charlie.publicKeyHex, 100.0)
            ),
            category = "Food",
            note = "Dinner",
            authorPubkey = alice.publicKeyHex,
            authorPrivateKeyHex = alice.privateKeyHex
        )

        // Bob settles ₹50 to Alice
        val stl1 = BlockchainEngine.createSettlementBlock(
            groupId = genesisGroupId,
            prevBlock = exp1,
            payerPubkey = bob.publicKeyHex,
            payeePubkey = alice.publicKeyHex,
            amount = 50.0,
            currency = "INR",
            note = "Settle dinner",
            authorPubkey = bob.publicKeyHex,
            authorPrivateKeyHex = bob.privateKeyHex
        )

        val v1Blocks = listOf(genesisBlock, bobJoin, charlieJoin, exp1, stl1)
        assertTrue("V1 linear chain must verify structurally", BlockchainEngine.verifyStructuralChain(v1Blocks))

        // Calculate legacy V1 balances via DebtSettlementEngine
        val v1Expenses = listOf(json.decodeFromString<ExpensePayload>(exp1.payloadJson))
        val v1Settlements = listOf(json.decodeFromString<SettlementPayload>(stl1.payloadJson))
        val v1Balances = DebtSettlementEngine.calculateNetBalances(
            expenses = v1Expenses,
            settlements = v1Settlements,
            voidedExpenseIds = emptySet()
        )

        // In V1: Alice paid 300 - 100 split - 50 received = +150.0
        // Bob paid 50 settlement - 100 split = -50.0
        // Charlie owed 100 = -100.0
        assertEquals(150.0, v1Balances[alice.publicKeyHex] ?: 0.0, 0.001)
        assertEquals(-50.0, v1Balances[bob.publicKeyHex] ?: 0.0, 0.001)
        assertEquals(-100.0, v1Balances[charlie.publicKeyHex] ?: 0.0, 0.001)

        // 2. Synthesize V1 Checkpoint Operation
        val v2Expenses = v1Expenses.map { exp ->
            OperationPayload.ExpenseCreated(
                expenseId = exp.expenseId,
                amountMinor = MoneyMath.toMinorUnits(exp.totalAmount),
                currency = exp.currency,
                paidBy = exp.payerPubkey,
                splits = exp.splits.associate { it.userPubkey to MoneyMath.toMinorUnits(it.amount) },
                description = exp.description,
                expenseDateMillis = exp.expenseDateMillis ?: 0L
            )
        }
        val v2Settlements = v1Settlements.map { stl ->
            OperationPayload.SettlementRecorded(
                settlementId = stl.settlementId,
                fromMember = stl.payerPubkey,
                toMember = stl.payeePubkey,
                amountMinor = MoneyMath.toMinorUnits(stl.amount),
                currency = stl.currency
            )
        }
        val protocolMembers = listOf(
            ProtocolMemberInfo(alice.publicKeyHex, "Alice", ProtocolMemberInfo.ROLE_ADMIN),
            ProtocolMemberInfo(bob.publicKeyHex, "Bob", ProtocolMemberInfo.ROLE_MEMBER),
            ProtocolMemberInfo(charlie.publicKeyHex, "Charlie", ProtocolMemberInfo.ROLE_MEMBER)
        )

        val checkpointPayload = OperationPayload.LegacyV1Checkpoint(
            v1TipBlockHash = stl1.blockHash,
            v1BlockCount = v1Blocks.size.toLong(),
            isLegacyChainFullyVerified = true,
            currency = "INR",
            members = protocolMembers,
            activeExpenses = v2Expenses,
            voidedExpenseIds = emptyList(),
            settlements = v2Settlements
        )

        val checkpointOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = genesisGroupId,
            authorPublicKey = alice.publicKeyHex,
            privateKeyHex = alice.privateKeyHex,
            parents = emptyList(),
            logicalClock = 1L,
            payload = checkpointPayload
        )

        // 3. Reconcile in V2 Engine
        val trustedGenesis = TrustedGenesis(
            groupId = genesisGroupId,
            groupName = "Trip to Goa",
            currency = "INR",
            creatorPublicKey = alice.publicKeyHex,
            genesisOperationId = checkpointOp.operationId
        )

        val v2Result = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(checkpointOp)
        )

        assertEquals("Checkpoint op must be accepted", 1, v2Result.acceptedOperations.size)
        assertEquals("0 rejected ops", 0, v2Result.rejectedOperations.size)

        // Compare balances: 150.0 -> 15000 paise, -50.0 -> -5000 paise, -100.0 -> -10000 paise
        assertEquals(15000L, v2Result.memberBalancesMinor[alice.publicKeyHex])
        assertEquals(-5000L, v2Result.memberBalancesMinor[bob.publicKeyHex])
        assertEquals(-10000L, v2Result.memberBalancesMinor[charlie.publicKeyHex])

        // Verify zero-sum invariant
        val totalNet = v2Result.memberBalancesMinor.values.sum()
        assertEquals(0L, totalNet)

        // 4. Create a subsequent V2 expense on top of the checkpoint
        val nextV2Expense = OperationPayload.ExpenseCreated(
            expenseId = "v2_exp_lunch",
            amountMinor = 15000L, // ₹150.00
            currency = "INR",
            paidBy = bob.publicKeyHex,
            splits = mapOf(
                alice.publicKeyHex to 5000L,
                bob.publicKeyHex to 5000L,
                charlie.publicKeyHex to 5000L
            ),
            description = "Lunch at Curlies",
            expenseDateMillis = System.currentTimeMillis()
        )

        val nextOp = OperationSigner.createAndSign(
            protocolVersion = ProtocolVersion.V2_SIGNED_OPERATIONS,
            groupId = genesisGroupId,
            authorPublicKey = bob.publicKeyHex,
            privateKeyHex = bob.privateKeyHex,
            parents = listOf(checkpointOp.operationId),
            logicalClock = 2L,
            payload = nextV2Expense
        )

        val updatedResult = DefaultLedgerReconciliationEngine.reconcile(
            trustedGenesis = trustedGenesis,
            operations = listOf(checkpointOp, nextOp)
        )

        assertEquals(2, updatedResult.acceptedOperations.size)
        // Alice balance: 15000 - 5000 = 10000 paise (₹100)
        // Bob balance: -5000 + 15000 (paid) - 5000 (split) = +5000 paise (₹50)
        // Charlie balance: -10000 - 5000 = -15000 paise (-₹150)
        assertEquals(10000L, updatedResult.memberBalancesMinor[alice.publicKeyHex])
        assertEquals(5000L, updatedResult.memberBalancesMinor[bob.publicKeyHex])
        assertEquals(-15000L, updatedResult.memberBalancesMinor[charlie.publicKeyHex])
        assertEquals(0L, updatedResult.memberBalancesMinor.values.sum())
    }
}
