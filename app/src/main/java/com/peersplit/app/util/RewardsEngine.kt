package com.peersplit.app.util

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.TimeUnit

data class RewardBadge(
    val id: String,
    val emoji: String,
    val title: String,
    val description: String,
    val xpReward: Int,
    val isUnlocked: Boolean,
    val progressText: String
)

data class RewardProfile(
    val totalXp: Int,
    val level: Int,
    val levelTitle: String,
    val levelEmoji: String,
    val currentLevelBaseXp: Int,
    val nextLevelXp: Int,
    val levelProgress: Float,
    val streakDays: Int,
    val unlockedBadgesCount: Int,
    val badges: List<RewardBadge>
)

object RewardsEngine {

    private const val PREFS_REWARDS = "peersplit_rewards_prefs"
    private const val KEY_BONUS_XP = "bonus_xp"
    private const val KEY_STREAK_DAYS = "streak_days"
    private const val KEY_LAST_ACTIVE_DAY = "last_active_day"
    private const val KEY_SETTLEMENTS_DONE = "settlements_done"
    private const val KEY_EXPENSES_ADDED = "expenses_added"
    private const val KEY_BILLS_PAID = "bills_paid"

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    @Volatile
    private var isCacheLoaded = false
    private var cachedStreakDays = 1
    private var cachedBonusXp = 0
    private var cachedExpensesAdded = 0
    private var cachedBillsPaid = 0
    private var cachedSettlementsDone = 0

    @Synchronized
    private fun ensureCacheLoaded(context: Context) {
        if (isCacheLoaded) return
        val prefs = context.getSharedPreferences(PREFS_REWARDS, Context.MODE_PRIVATE)
        cachedStreakDays = prefs.getInt(KEY_STREAK_DAYS, 1).coerceAtLeast(1)
        cachedBonusXp = prefs.getInt(KEY_BONUS_XP, 0)
        cachedExpensesAdded = prefs.getInt(KEY_EXPENSES_ADDED, 0)
        cachedBillsPaid = prefs.getInt(KEY_BILLS_PAID, 0)
        cachedSettlementsDone = prefs.getInt(KEY_SETTLEMENTS_DONE, 0)
        isCacheLoaded = true
    }

    /**
     * Records a user action, updates the daily activity streak, awards XP, and triggers UI refresh.
     */
    fun recordAction(
        context: Context,
        xpEarned: Int,
        isExpense: Boolean = false,
        userPaidBill: Boolean = false,
        isSettlement: Boolean = false
    ): Int {
        ensureCacheLoaded(context)
        val prefs = context.getSharedPreferences(PREFS_REWARDS, Context.MODE_PRIVATE)
        val todayEpochDay = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())
        val lastDay = prefs.getLong(KEY_LAST_ACTIVE_DAY, -1L)
        var streak = cachedStreakDays

        if (lastDay == -1L) {
            streak = 1
        } else if (todayEpochDay == lastDay + 1L) {
            streak += 1
        } else if (todayEpochDay > lastDay + 1L) {
            streak = 1
        }

        val currentBonus = cachedBonusXp + xpEarned
        val expCount = cachedExpensesAdded + if (isExpense) 1 else 0
        val paidCount = cachedBillsPaid + if (userPaidBill) 1 else 0
        val stlCount = cachedSettlementsDone + if (isSettlement) 1 else 0

        cachedStreakDays = streak
        cachedBonusXp = currentBonus
        cachedExpensesAdded = expCount
        cachedBillsPaid = paidCount
        cachedSettlementsDone = stlCount

        prefs.edit()
            .putInt(KEY_BONUS_XP, currentBonus)
            .putInt(KEY_STREAK_DAYS, streak)
            .putLong(KEY_LAST_ACTIVE_DAY, todayEpochDay)
            .putInt(KEY_EXPENSES_ADDED, expCount)
            .putInt(KEY_BILLS_PAID, paidCount)
            .putInt(KEY_SETTLEMENTS_DONE, stlCount)
            .apply()

        _version.value++
        return xpEarned
    }

    /**
     * Ensures daily streak is initialized when opening the app.
     */
    fun touchDailyVisit(context: Context) {
        ensureCacheLoaded(context)
        val prefs = context.getSharedPreferences(PREFS_REWARDS, Context.MODE_PRIVATE)
        val todayEpochDay = TimeUnit.MILLISECONDS.toDays(System.currentTimeMillis())
        val lastDay = prefs.getLong(KEY_LAST_ACTIVE_DAY, -1L)
        if (lastDay == todayEpochDay) return

        var streak = cachedStreakDays
        streak = when {
            lastDay == -1L -> 1
            todayEpochDay == lastDay + 1L -> streak + 1
            todayEpochDay > lastDay + 1L -> 1
            else -> streak
        }
        cachedStreakDays = streak
        prefs.edit()
            .putInt(KEY_STREAK_DAYS, streak)
            .putLong(KEY_LAST_ACTIVE_DAY, todayEpochDay)
            .apply()
        _version.value++
    }

    fun getRewardProfile(
        context: Context,
        groupsCount: Int,
        totalBlocksCount: Int,
        allBalancesSquaredUp: Boolean
    ): RewardProfile {
        ensureCacheLoaded(context)
        val streakDays = cachedStreakDays
        val bonusXp = cachedBonusXp
        val recordedExpenses = cachedExpensesAdded
        val recordedBillsPaid = cachedBillsPaid
        val recordedSettlements = cachedSettlementsDone

        // Estimate activity from ledger blocks as well so existing users get full credit immediately
        val nonGenesisBlocks = (totalBlocksCount - groupsCount).coerceAtLeast(0)
        val effectiveExpenses = maxOf(recordedExpenses, nonGenesisBlocks)
        val effectiveBillsPaid = maxOf(recordedBillsPaid, (effectiveExpenses + 1) / 2)

        val baseActivityXp = 50 + // Welcome starter XP
            (groupsCount * 50) +
            (effectiveExpenses * 25) +
            (recordedSettlements * 100) +
            (streakDays * 30) +
            bonusXp

        val preliminaryBadges = listOf(
            RewardBadge(
                id = "welcome",
                emoji = "🎯",
                title = "Early Adopter",
                description = "Joined PeerSplit encrypted P2P network",
                xpReward = 50,
                isUnlocked = true,
                progressText = "Unlocked"
            ),
            RewardBadge(
                id = "first_group",
                emoji = "🚀",
                title = "Group Pioneer",
                description = "Create or join your first expense group",
                xpReward = 50,
                isUnlocked = groupsCount >= 1,
                progressText = "${groupsCount.coerceAtMost(1)}/1 group"
            ),
            RewardBadge(
                id = "first_split",
                emoji = "⚡",
                title = "First Split",
                description = "Log your first shared expense",
                xpReward = 50,
                isUnlocked = effectiveExpenses >= 1,
                progressText = "${effectiveExpenses.coerceAtMost(1)}/1 expense"
            ),
            RewardBadge(
                id = "generous_host",
                emoji = "👑",
                title = "Generous Host",
                description = "Pay for 3 or more group bills",
                xpReward = 100,
                isUnlocked = effectiveBillsPaid >= 3,
                progressText = "${effectiveBillsPaid.coerceAtMost(3)}/3 bills paid"
            ),
            RewardBadge(
                id = "debt_crusher",
                emoji = "🤝",
                title = "Debt Crusher",
                description = "Settle up a balance with a friend",
                xpReward = 100,
                isUnlocked = recordedSettlements >= 1,
                progressText = "${recordedSettlements.coerceAtMost(1)}/1 settled"
            ),
            RewardBadge(
                id = "streak_master",
                emoji = "🔥",
                title = "On Fire",
                description = "Maintain a 3-day app streak",
                xpReward = 150,
                isUnlocked = streakDays >= 3,
                progressText = "${streakDays.coerceAtMost(3)}/3 days"
            ),
            RewardBadge(
                id = "zero_drama",
                emoji = "🛡️",
                title = "Zero Drama",
                description = "Have 0 pending debt across active groups",
                xpReward = 100,
                isUnlocked = groupsCount >= 1 && allBalancesSquaredUp,
                progressText = if (groupsCount >= 1 && allBalancesSquaredUp) "All squared up!" else "Settle pending debts"
            ),
            RewardBadge(
                id = "squad_builder",
                emoji = "🌐",
                title = "Squad Builder",
                description = "Be part of 3 or more expense groups",
                xpReward = 150,
                isUnlocked = groupsCount >= 3,
                progressText = "${groupsCount.coerceAtMost(3)}/3 groups"
            )
        )

        val badgeBonusXp = preliminaryBadges.filter { it.isUnlocked }.sumOf { it.xpReward }
        val totalXp = baseActivityXp + badgeBonusXp

        data class LevelTier(val level: Int, val title: String, val emoji: String, val minXp: Int, val maxXp: Int)
        val tiers = listOf(
            LevelTier(1, "Split Rookie", "🌱", 0, 250),
            LevelTier(2, "Quick Splitter", "⚡", 250, 600),
            LevelTier(3, "Fair Play Pro", "🛡️", 600, 1200),
            LevelTier(4, "Squad Treasurer", "🔥", 1200, 2200),
            LevelTier(5, "Split Legend", "👑", 2200, 5000)
        )

        val currentTier = tiers.lastOrNull { totalXp >= it.minXp } ?: tiers.first()
        val span = (currentTier.maxXp - currentTier.minXp).coerceAtLeast(1)
        val progress = ((totalXp - currentTier.minXp).toFloat() / span.toFloat()).coerceIn(0.05f, 1f)

        return RewardProfile(
            totalXp = totalXp,
            level = currentTier.level,
            levelTitle = currentTier.title,
            levelEmoji = currentTier.emoji,
            currentLevelBaseXp = currentTier.minXp,
            nextLevelXp = currentTier.maxXp,
            levelProgress = progress,
            streakDays = streakDays,
            unlockedBadgesCount = preliminaryBadges.count { it.isUnlocked },
            badges = preliminaryBadges
        )
    }
}
