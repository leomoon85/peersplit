package com.peersplit.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.peersplit.app.data.local.entity.BlockEntity
import com.peersplit.app.data.local.entity.GroupEntity
import com.peersplit.app.data.local.entity.MemberEntity
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

    @Query("SELECT COUNT(*) FROM members")
    fun getAllMembersCountFlow(): Flow<Long>

    @Query("UPDATE members SET upiId = :upiId WHERE groupId = :groupId AND pubkey = :pubkey")
    suspend fun updateMemberUpiId(groupId: String, pubkey: String, upiId: String)

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

    @Query("SELECT * FROM blocks WHERE groupId = :groupId ORDER BY `index` DESC LIMIT 1")
    suspend fun getLatestBlockForGroup(groupId: String): BlockEntity?

    @Query("SELECT COUNT(*) FROM blocks WHERE groupId = :groupId")
    suspend fun getBlockCount(groupId: String): Long

    @Query("SELECT COUNT(*) FROM blocks")
    fun getAllBlocksCountFlow(): Flow<Long>

    @Query("DELETE FROM blocks WHERE groupId = :groupId")
    suspend fun deleteBlocksForGroup(groupId: String)
}
