package dev.compilin.mc.shulkershop

import net.minecraft.entity.ai.goal.GoalSelector
import net.minecraft.entity.ai.goal.PrioritizedGoal
import java.util.*

interface ShulkerShopEntity {
    var shopId: UUID?
    var shop: ShulkerShop?
    fun clearShulkerAIGoals(): GoalSelector
}

interface GoalSelectorAccess {
    val goals: Set<PrioritizedGoal>
}