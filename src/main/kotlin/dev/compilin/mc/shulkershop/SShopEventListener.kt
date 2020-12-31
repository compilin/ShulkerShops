package dev.compilin.mc.shulkershop

import dev.compilin.mc.shulkershop.Config.CREATE_ITEM
import dev.compilin.mc.shulkershop.Config.SELECT_ITEM
import dev.compilin.mc.shulkershop.SShopMod.PlayerShopSelection
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.block.BlockState
import net.minecraft.entity.Entity
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.entity.mob.ShulkerLidCollisions
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.server.MinecraftServer
import net.minecraft.server.network.ServerPlayerEntity
import net.minecraft.server.world.ServerWorld
import net.minecraft.text.LiteralText
import net.minecraft.text.MutableText
import net.minecraft.util.ActionResult
import net.minecraft.util.Formatting
import net.minecraft.util.Hand
import net.minecraft.util.hit.BlockHitResult
import net.minecraft.util.hit.EntityHitResult
import net.minecraft.world.World
import java.util.*
import kotlin.collections.ArrayList

object SShopEventListener {
    const val tickUpdateInterval = 20
    private var lastTickUpdate: Long = 0
    private val preLoadSpawnedShulkers: MutableList<ShulkerEntity> = ArrayList()

    fun initializeListeners() {
        ServerTickEvents.START_SERVER_TICK.register(this::onTick)
        UseEntityCallback.EVENT.register(this::onEntityInteract)
        UseBlockCallback.EVENT.register(this::onRightClickBlock)
        log.debug("Initialized event listeners")
    }

    fun onTick(server: MinecraftServer) {
        if (server.ticks - lastTickUpdate >= tickUpdateInterval) {
            SShopMod.shopRegistry!!.allShops.forEach { it.onTickUpdate() }
            lastTickUpdate = server.ticks.toLong()
        }
    }

    /* ***********************
		Player related events
	   *********************** */
    @Suppress("UNUSED_PARAMETER")
    fun onEntityInteract(
        player: PlayerEntity, world: World, hand: Hand, entity: Entity, result: EntityHitResult?
    ): ActionResult {
        if (hand === Hand.MAIN_HAND && entity is ShulkerEntity) {
            val shop = (entity as ShulkerShopEntity).shop
            if (shop != null) {
                onShopInteract(player, shop)
                return ActionResult.SUCCESS
            }
        }
        return ActionResult.PASS
    }

    private fun onShopInteract(player: PlayerEntity, shop: ShulkerShop) {
        log.debug("Player $player interacted with a shulker shop")
        val otherPlayerIsSelecting: Boolean = SShopMod.getSelectionByShop(shop)
            .map { sel -> sel.player.uuid != player.uuid }
            .orElse(false)
        val selectItem = SELECT_ITEM()
        if (selectItem.test(player.mainHandStack) ||
            player.mainHandStack.isEmpty && selectItem.test(player.offHandStack)
        ) {
            val sneak = player.isSneaking
            if (shop.ownerId == player.uuid) { // Shop belongs to interacting player
                if (!SShopMod.Permission.EDIT_OWN_SHOP.check(player) && !SShopMod.Permission.DELETE_OWN_SHOP.check(
                        player
                    )
                ) {
                    player.sendMessage(
                        LiteralText(
                            "You do not have permission to edit or delete shops"
                        ).formatted(Formatting.RED)
                    )
                    return
                }
                if (!sneak && !SShopMod.Permission.ACCESS_OWN_SHOP_INVENTORY.checkOrSendError(player)) {
                    return
                }
            } else {
                if (!SShopMod.Permission.EDIT_OTHERS_SHOP.check(player) && !SShopMod.Permission.DELETE_OTHERS_SHOP.check(
                        player
                    )
                ) {
                    player.sendMessage(
                        LiteralText(
                            "You do not have permission to edit or delete other players's shops"
                        ).formatted(Formatting.RED)
                    )
                    return
                }
                if (!sneak && !SShopMod.Permission.ACCESS_OTHERS_SHOP_INVENTORY.checkOrSendError(player)) {
                    return
                }
            }
            if (otherPlayerIsSelecting) {
                player.sendMessage(
                    LiteralText(
                        "Can't " + (if (sneak) "select" else "open inventory")
                                + ", someone else is currently editing this shop"
                    ).formatted(Formatting.RED)
                )
                return
            }
            val prevSelection: Optional<PlayerShopSelection> = SShopMod.getSelectionByPlayer(player.uuid)
            if (prevSelection.map { sel: PlayerShopSelection -> sel.shop != shop }.orElse(true)) {
                // Previous selection was empty or a different shop
                check(SShopMod.setPlayerSelection(player, shop)) { "Couldn't add selection" }
                if (sneak) {
                    val message: MutableText = LiteralText(
                        java.lang.String.format(
                            "§aShulker shop \"§l%s§r§a\" selected§r", // TODO figure out if this syntax has a reason to be
                            shop.name.string
                        )
                    )
                    if (shop.ownerId != player.uuid) {
                        message.append(LiteralText(" (this is someone else's shop. Edit responsibly)"))
                    }
                    if (prevSelection.isPresent) {
                        message.append(". Previous shop unselected")
                    }
                    message.append(
                        LiteralText(
                            ". Players can't shop while you're editting so remember to unselect it afterward"
                        )
                            .formatted(Formatting.WHITE)
                    )
                    player.sendMessage(message)
                }
            } else if (sneak) {
                SShopMod.unsetPlayerSelection(player.uuid)
                player.sendMessage(LiteralText("Shulker shop unselected"))
            }
            if (!sneak) {
                shop.openInventory(player)
            }
        } else {
            when {
                otherPlayerIsSelecting -> player.sendMessage(
                    LiteralText("Can't trade while someone is editing this shop"), true
                )
                shop.currentCustomer != null -> player.sendMessage(LiteralText("Someone else is trading"), true)
                else -> shop.openShop(player)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    fun onRightClickBlock(player: PlayerEntity, world: World, hand: Hand, hit: BlockHitResult): ActionResult {
        val holdingHand = if (!player.mainHandStack.isEmpty) Hand.MAIN_HAND else Hand.OFF_HAND
        val held: ItemStack = player.getStackInHand(holdingHand)
        if (hand == Hand.MAIN_HAND && CREATE_ITEM().test(held)) {
            val blockState: BlockState = world.getBlockState(hit.blockPos)
            if (!blockState.isSolidBlock(world, hit.blockPos)) {
                player.sendMessage(
                    LiteralText("Target position needs to be on a solid block").formatted(Formatting.RED)
                )
            } else if (!world.isSpaceEmpty(ShulkerLidCollisions.getLidCollisionBox(hit.blockPos, hit.side))) {
                player.sendMessage(
                    LiteralText("Target position needs empty space for the shulker").formatted(Formatting.RED)
                )
            } else {
                if (!player.abilities.creativeMode) {
                    held.decrement(1)
                    player.setStackInHand(holdingHand, if (held.isEmpty) ItemStack.EMPTY else held)
                }
                val shop = SShopMod.shopRegistry!!.newShulkerShop(player)
                if (shop.spawnShulker(
                        world as ServerWorld, player, hit.blockPos.offset(hit.side), hit.side.opposite
                    )
                ) {
                    SShopMod.setPlayerSelection(player, shop)
                    player.sendMessage(LiteralText("Successfully created shulker shop!"))
                } else {
                    player.sendMessage(
                        LiteralText("Error while trying to create the shop")
                            .formatted(Formatting.RED)
                    )
                }
            }
            return ActionResult.SUCCESS
        }
        return ActionResult.PASS
    }

    fun onPlayerLoggedOut(player: ServerPlayerEntity) {
        SShopMod.unsetPlayerSelection(player.uuid)
    }

    /* ***********************************
		Non-player related shulker events
	   *********************************** */

    fun onShulkerSpawn(shulker: ShulkerEntity) {
        if (SShopMod.areShopsLoaded()) {
            val shopID: UUID? = (shulker as ShulkerShopEntity).shopId
            if (shopID != null) {
                val shop: ShulkerShop? = SShopMod.shopRegistry!!.getShopById(shopID)
                if (shop != null) {
                    shop.setShulker(shulker)
                } else {
                    log.info("Shulker spawned with an attached shop ID but shop doesn't exist. Removing shulker")
                    shulker.remove()
                }
            }
        } else {
            preLoadSpawnedShulkers.add(shulker)
        }
    }

    fun onShopsLoaded() {
        log.debug(
            String.format(
                "Running onSpawn hook for shulker spawned before shops were loaded (%d)",
                preLoadSpawnedShulkers.size
            )
        )
        preLoadSpawnedShulkers.forEach { onShulkerSpawn(it) }
        preLoadSpawnedShulkers.clear()
    }

    /* ***********
		Utilities
	   *********** */
    private fun getShopByShulker(shulker: ShulkerEntity): ShulkerShop? {
        val shopID: UUID? = (shulker as ShulkerShopEntity).shopId
        if (shopID != null) {
            val shop: ShulkerShop? = SShopMod.shopRegistry!!.getShopById(shopID)
            if (shop != null) {
                return shop
            }
            log.info("Shulker spawned with an attached shop ID but shop doesn't exist. Removing shulker")
            shulker.remove()
        }
        return null
    }

    private fun logError(msg: String, player: PlayerEntity) {
        log.error(msg)
        player.sendMessage(LiteralText("An error occured!").formatted(Formatting.RED), false)
    }
}