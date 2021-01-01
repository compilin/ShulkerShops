package dev.compilin.mc.shulkershop

import net.minecraft.entity.ai.TargetPredicate
import net.minecraft.entity.ai.goal.Goal
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.server.network.ServerPlayerEntity
import java.util.*

class ShulkerLookAtCustomer internal constructor(private val shop: ShulkerShop) : Goal() {
    private val shulker: ShulkerEntity = shop.getShulker()!!

    init {
        controls = EnumSet.of(Control.LOOK)
    }

    /**
     * Returns whether the EntityAIBase should begin execution.
     */
    override fun canStart(): Boolean {
        return shop.currentCustomer != null || shop.getSelectingPlayer() != null
    }

    /**
     * Returns whether an in-progress EntityAIBase should continue executing
     */
    override fun shouldContinue(): Boolean {
        return shop.currentCustomer != null || shop.getSelectingPlayer() != null
    }

    /**
     * Execute a one shot task or start executing a continuous task
     */
    override fun start() {
        shulker.peekAmount = 100
    }

    /**
     * Reset the task's internal state. Called when this task is interrupted by another one
     */
    override fun stop() {
        shulker.peekAmount = 0
    }

    /**
     * Keep ticking a continuous task that has already been started
     */
    override fun tick() {
        val player: PlayerEntity? = shop.currentCustomer ?: shop.getSelectingPlayer()
        if (player != null) {
            shulker.lookControl.lookAt(player.x, player.eyeY, player.z)
        }
    }
}

class ShulkerLookAtPlayer internal constructor(private val shulker: ShulkerEntity, private val maxDistance: Float) : Goal() {
    private val playerPredicate: TargetPredicate = TargetPredicate().setBaseMaxDistance(maxDistance.toDouble())
        .includeTeammates().includeInvulnerable().ignoreEntityTargetRules()
    private var closestPlayer: ServerPlayerEntity? = null
    private var recheckTime = 0

    init {
        controls = EnumSet.of(Control.LOOK)
    }

    /**
     * Returns whether the EntityAIBase should begin execution.
     */
    override fun canStart(): Boolean {
        closestPlayer = shulker.world.getClosestPlayer(playerPredicate, shulker) as ServerPlayerEntity?
        recheckTime = 20
        return closestPlayer != null
    }

    /**
     * Returns whether an in-progress EntityAIBase should continue executing
     */
    override fun shouldContinue(): Boolean {
        return closestPlayer?.isAlive == true && shulker.squaredDistanceTo(closestPlayer) <= (maxDistance * maxDistance).toDouble()
    }

    /**
     * Execute a one shot task or start executing a continuous task
     */
    override fun start() {
        shulker.peekAmount = 30
    }

    /**
     * Reset the task's internal state. Called when this task is interrupted by another one
     */
    override fun stop() {
        shulker.peekAmount = 0
    }

    /**
     * Keep ticking a continuous task that has already been started
     */
    override fun tick() {
        if (recheckTime-- <= 0) { // every 20 ticks, check if another player is closest
            recheckTime = 20
            val newClosest: ServerPlayerEntity? = shulker.world.getClosestPlayer(playerPredicate, shulker) as ServerPlayerEntity?
            if (newClosest != null) {
                closestPlayer = newClosest
            }
        }
        shulker.lookControl.lookAt(closestPlayer!!.x, closestPlayer!!.eyeY, closestPlayer!!.z)
    }
}