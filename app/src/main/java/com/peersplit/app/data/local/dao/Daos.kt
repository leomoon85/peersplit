package com.peersplit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.peersplit.app.data.local.entity.BalanceProjectionEntity
import com.peersplit.app.data.local.entity.BlockEntity
import com.peersplit.app.data.local.entity.ExpenseProjectionEntity
import com.peersplit.app.data.local.entity.GroupEntity
import com.peersplit.app.data.local.entity.GroupProtocolEntity
import com.peersplit.app.data.local.entity.MemberEntity
import com.peersplit.app.data.local.entity.OperationDependencyEntity
import com.peersplit.app.data.local.entity.OutboxOperationEntity
import com.peersplit.app.data.local.entity.RelayDeliveryEntity
import com.peersplit.app.data.local.entity.SignedOperationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GroupDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroup(group: GroupEntity)

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    fun getAllGroups(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY createdAt DESC")
    suspend fun getGroupsSync(): List<GroupEntity>

    @Query("SELECT * FROM groups WHERE id = :groupId LIMIT 1")
    fun getGroupById(groupId: String): Flow<GroupEntity?>

    @Query("SELECT * FROM groups WHERE id = :groupId LIMIT 1")
    suspend fun getGroupByIdSync(groupId: String): GroupEntity?

    @Query("UPDATE groups SET monthlyBudget = :budget WHERE id = :groupId")
    suspend fun updateGroupBudget(groupId: String, budget: Double)

    @Query("UPDATE groups SET isArchived = :isArchived WHERE id = :groupId")
    suspend fun setGroupArchived(groupId: String, isArchived: Boolean)

    @Query("DELETE FROM groups WHERE id = :groupId")
    suspend fun deleteGroup(groupId: String)
}

@Dao
interface MemberDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMember(member: MemberEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMembers(members: List<MemberEntity>)

    @Query("SELECT * FROM members WHERE groupId = :groupId")
    fun getMembersForGroup(groupId: String): Flow<List<MemberEntity>>

    @Query("SELECT * FROM members WHERE groupId = :groupId")
    suspend fun getMembersForGroupSync(groupId: String): List<MemberEntity>

    @Query("SELECT * FROM members")
    fun getAllMembersFlow(): Flow<List<MemberEntity>>

    @Query("SELECT COUNT(*) FROM members")
    fun getAllMembersCountFlow(): Flow<Long>

    @Query("UPDATE members SET upiId = :upiId WHERE groupId = :groupId AND pubkey = :pubkey")
    suspend fun updateMemberUpiId(groupId: String, pubkey: String, upiId: String)

    @Query("UPDATE members SET phone = :phone, upiId = :upiId WHERE groupId = :groupId AND pubkey = :pubkey")
    suspend fun updateMemberContact(groupId: String, pubkey: String, phone: String?, upiId: String?)

    @Query("DELETE FROM members WHERE groupId = :groupId AND pubkey = :pubkey")
    suspend fun deleteMember(groupId: String, pubkey: String)

    @Query("DELETE FROM members WHERE groupId = :groupId")
    suspend fun deleteMembersForGroup(groupId: String)
}

@Dao
interface BlockDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlock(block: BlockEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlocks(blocks: List<BlockEntity>)

    @Query("SELECT * FROM blocks WHERE groupId = :groupId ORDER BY `index` ASC")
    fun getBlocksForGroup(groupId: String): Flow<List<BlockEntity>>

    @Query("SELECT * FROM blocks WHERE groupId = :groupId ORDER BY `index` ASC")
    suspend fun getBlocksForGroupSync(groupId: String): List<BlockEntity>

    @Query("SELECT * FROM blocks ORDER BY groupId, `index` ASC")
    fun getAllBlocksFlow(): Flow<List<BlockEntity>>

    @Query("SELECT * FROM blocks WHERE groupId = :groupId ORDER BY `index` DESC LIMIT 1")
    suspend fun getLatestBlockForGroup(groupId: String): BlockEntity?

    @Query("SELECT COUNT(*) FROM blocks WHERE groupId = :groupId")
    suspend fun getBlockCount(groupId: String): Long

    @Query("SELECT COUNT(*) FROM blocks")
    fun getAllBlocksCountFlow(): Flow<Long>

    @Query("SELECT * FROM blocks WHERE isSynced = 0 ORDER BY timestamp ASC")
    suspend fun getUnsyncedBlocksSync(): List<BlockEntity>

    @Query("UPDATE blocks SET isSynced = 1 WHERE blockHash = :blockHash")
    suspend fun markBlockSynced(blockHash: String)

    @Query("DELETE FROM blocks WHERE groupId = :groupId AND `index` >= :fromIndex")
    suspend fun deleteBlocksFromIndex(groupId: String, fromIndex: Long)

    @Query("DELETE FROM blocks WHERE groupId = :groupId")
    suspend fun deleteBlocksForGroup(groupId: String)
}

@Dao
interface SignedOperationDao {
    /**
     * Inserts a signed operation using IGNORE conflict strategy so original signed bytes
     * are strictly immutable once stored.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperationIgnore(operation: SignedOperationEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOperationsIgnore(operations: List<SignedOperationEntity>): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDependenciesIgnore(dependencies: List<OperationDependencyEntity>)

    @Query("SELECT * FROM signed_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun getOperationByIdSync(operationId: String): SignedOperationEntity?

    @Query("SELECT * FROM signed_operations WHERE groupId = :groupId ORDER BY logicalClock ASC, operationId ASC")
    fun getOperationsForGroupFlow(groupId: String): Flow<List<SignedOperationEntity>>

    @Query("SELECT * FROM signed_operations WHERE groupId = :groupId ORDER BY logicalClock ASC, operationId ASC")
    suspend fun getOperationsForGroupSync(groupId: String): List<SignedOperationEntity>

    @Query("SELECT * FROM signed_operations ORDER BY groupId, logicalClock ASC, operationId ASC")
    fun getAllOperationsFlow(): Flow<List<SignedOperationEntity>>

    @Query("UPDATE signed_operations SET status = :status, rejectionReason = :rejectionReason WHERE operationId = :operationId")
    suspend fun updateOperationStatus(operationId: String, status: String, rejectionReason: String?)

    @Query("DELETE FROM operation_dependencies WHERE groupId = :groupId")
    suspend fun deleteDependenciesForGroup(groupId: String)

    @Query("DELETE FROM signed_operations WHERE groupId = :groupId")
    suspend fun deleteOperationsForGroup(groupId: String)
}

@Dao
interface ProjectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertExpenseProjections(projections: List<ExpenseProjectionEntity>)

    @Query("DELETE FROM expense_projections WHERE groupId = :groupId")
    suspend fun deleteExpenseProjectionsForGroup(groupId: String)

    @Query("SELECT * FROM expense_projections WHERE groupId = :groupId ORDER BY expenseDateMillis DESC")
    fun getExpenseProjectionsForGroupFlow(groupId: String): Flow<List<ExpenseProjectionEntity>>

    @Query("SELECT * FROM expense_projections WHERE groupId = :groupId ORDER BY expenseDateMillis DESC")
    suspend fun getExpenseProjectionsForGroupSync(groupId: String): List<ExpenseProjectionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBalanceProjections(projections: List<BalanceProjectionEntity>)

    @Query("DELETE FROM balance_projections WHERE groupId = :groupId")
    suspend fun deleteBalanceProjectionsForGroup(groupId: String)

    @Query("SELECT * FROM balance_projections WHERE groupId = :groupId")
    fun getBalanceProjectionsForGroupFlow(groupId: String): Flow<List<BalanceProjectionEntity>>

    @Query("SELECT * FROM balance_projections WHERE groupId = :groupId")
    suspend fun getBalanceProjectionsForGroupSync(groupId: String): List<BalanceProjectionEntity>

    @Query("SELECT * FROM balance_projections")
    fun getAllBalanceProjectionsFlow(): Flow<List<BalanceProjectionEntity>>

    @Query("SELECT * FROM expense_projections")
    fun getAllExpenseProjectionsFlow(): Flow<List<ExpenseProjectionEntity>>
}

@Dao
interface OutboxDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOutboxOperation(entry: OutboxOperationEntity): Long

    @Query("SELECT * FROM outbox_operations WHERE operationId = :operationId LIMIT 1")
    suspend fun getOutboxByOperationIdSync(operationId: String): OutboxOperationEntity?

    @Query("SELECT * FROM outbox_operations WHERE nostrEventId = :nostrEventId LIMIT 1")
    suspend fun getOutboxByEventIdSync(nostrEventId: String): OutboxOperationEntity?

    @Query("SELECT * FROM outbox_operations WHERE status IN ('QUEUED', 'ACKED_PARTIAL') AND nextAttemptAtMillis <= :nowMillis ORDER BY createdAtMillis ASC LIMIT :limit")
    suspend fun getDueOutboxOperationsSync(nowMillis: Long, limit: Int = 64): List<OutboxOperationEntity>

    @Query("SELECT * FROM outbox_operations WHERE groupId = :groupId ORDER BY createdAtMillis ASC")
    suspend fun getOutboxForGroupSync(groupId: String): List<OutboxOperationEntity>

    @Query("UPDATE outbox_operations SET attemptCount = :attemptCount, nextAttemptAtMillis = :nextAttemptAtMillis, status = :status WHERE operationId = :operationId")
    suspend fun updateOutboxRetryState(
        operationId: String,
        attemptCount: Int,
        nextAttemptAtMillis: Long,
        status: String
    )

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRelayDelivery(delivery: RelayDeliveryEntity)

    @Query("SELECT * FROM relay_deliveries WHERE operationId = :operationId")
    suspend fun getRelayDeliveriesForOperationSync(operationId: String): List<RelayDeliveryEntity>

    @Query("SELECT COUNT(*) FROM relay_deliveries WHERE operationId = :operationId AND accepted = 1")
    suspend fun countAcceptedRelaysForOperationSync(operationId: String): Int

    @Query("DELETE FROM outbox_operations WHERE groupId = :groupId")
    suspend fun deleteOutboxForGroup(groupId: String)
}

@Dao
interface GroupProtocolDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroupProtocol(metadata: GroupProtocolEntity)

    @Query("SELECT * FROM group_protocol_metadata WHERE groupId = :groupId LIMIT 1")
    suspend fun getGroupProtocolSync(groupId: String): GroupProtocolEntity?

    @Query("SELECT * FROM group_protocol_metadata WHERE groupId = :groupId LIMIT 1")
    fun getGroupProtocolFlow(groupId: String): Flow<GroupProtocolEntity?>

    @Query("SELECT * FROM group_protocol_metadata")
    fun getAllGroupProtocolsFlow(): Flow<List<GroupProtocolEntity>>

    @Query("DELETE FROM group_protocol_metadata WHERE groupId = :groupId")
    suspend fun deleteGroupProtocol(groupId: String)
}
