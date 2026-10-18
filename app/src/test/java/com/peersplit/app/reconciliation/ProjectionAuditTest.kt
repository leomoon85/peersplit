package com.peersplit.app.reconciliation

import com.peersplit.app.crypto.KeyManager
import com.peersplit.app.data.local.entity.BalanceProjectionEntity
import com.peersplit.app.protocol.OperationPayload
import com.peersplit.app.protocol.OperationSigner
import com.peersplit.app.protocol.ProtocolMemberInfo
import org.junit.Assert.*
import org.junit.Test

class ProjectionAuditTest {
    @Test fun staleBalanceAndDigestCannotReceiveVerifiedStatus() {
        val identity = KeyManager.generateKeyPair()
        val group = "projection-audit-regression"
        val genesis = OperationSigner.createAndSign(groupId = group, authorPublicKey = identity.publicKeyHex,
            privateKeyHex = identity.privateKeyHex, parents = emptyList(), logicalClock = 1,
            payload = OperationPayload.GenesisCreated(group, "Audit", "INR", identity.publicKeyHex,
                listOf(ProtocolMemberInfo(identity.publicKeyHex, "Creator", ProtocolMemberInfo.ROLE_ADMIN))))
        val result = DefaultLedgerReconciliationEngine.reconcile(
            TrustedGenesis(group, "Audit", "INR", identity.publicKeyHex, genesis.operationId), listOf(genesis))
        val balances = result.memberBalancesMinor.map { (key, amount) -> BalanceProjectionEntity(group, key, amount, "INR", result.stateDigest) }
        assertTrue(ProjectionAudit.matches(result, emptyList(), balances))
        assertFalse(ProjectionAudit.matches(result, emptyList(), balances.map { it.copy(balanceMinor = 1) }))
        assertFalse(ProjectionAudit.matches(result, emptyList(), balances.map { it.copy(stateDigest = "stale") }))
        assertFalse(ProjectionAudit.matches(result, emptyList(), emptyList()))
    }
}
