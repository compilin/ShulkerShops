package dev.compilin.mc.shulkershop

import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.player.PlayerInventory
import net.minecraft.item.ItemStack
import net.minecraft.screen.MerchantScreenHandler
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.screen.slot.TradeOutputSlot
import net.minecraft.village.MerchantInventory
import java.util.*

class ShulkerShopContainer(syncId: Int, playerInventory: PlayerInventory, private val shop: ShulkerShop) :
    MerchantScreenHandler(syncId, playerInventory, shop) {
    private val inventory: MerchantInventory = getSlot(0).inventory as MerchantInventory

//    /**
//     * Handle when the stack in slot `index` is shift-clicked. Normally this moves the stack between the player
//     * inventory and the other inventory(s).
//     * Copy of [MerchantScreenHandler#transferSlot] with the call to playYesSound removed to avoid a cast class exception
//     * TO/DO use a Mixin instead - Done
//     */
//    override fun transferSlot(player: PlayerEntity?, index: Int): ItemStack {
//        var itemStack = ItemStack.EMPTY
//        val slot = slots[index]
//        if (slot != null && slot.hasStack()) {
//            val itemStack2 = slot.stack
//            itemStack = itemStack2.copy()
//            if (index == 2) {
//                if (!insertItem(itemStack2, 3, 39, true)) {
//                    return ItemStack.EMPTY
//                }
//                slot.onStackChanged(itemStack2, itemStack)
//            } else if (index != 0 && index != 1) {
//                if (index in 3..29) {
//                    if (!insertItem(itemStack2, 30, 39, false)) {
//                        return ItemStack.EMPTY
//                    }
//                } else if (index in 30..38 && !insertItem(itemStack2, 3, 30, false)) {
//                    return ItemStack.EMPTY
//                }
//            } else if (!insertItem(itemStack2, 3, 39, false)) {
//                return ItemStack.EMPTY
//            }
//            if (itemStack2.isEmpty) {
//                slot.stack = ItemStack.EMPTY
//            } else {
//                slot.markDirty()
//            }
//            if (itemStack2.count == itemStack.count) {
//                return ItemStack.EMPTY
//            }
//            slot.onTakeItem(player, itemStack2)
//        }
//        return itemStack
//    }

    override fun onSlotClick(slotId: Int, dragType: Int, actionType: SlotActionType, player: PlayerEntity): ItemStack {
        return try {
            val playerinventory: PlayerInventory = player.inventory
            // Result slot
            if (slotId == 2 && (actionType === SlotActionType.PICKUP || actionType === SlotActionType.QUICK_MOVE) && dragType == 0) {
                if (this.slots[slotId] !is ResultSlot) {
                    log.warn(
                        String.format(
                            "slotClick called with slotId == 2 but slot isn't a ShulkerShopContainer.ResultSlot (%s)",
                            if (this.slots[slotId] == null) "null" else this.slots[slotId].javaClass.canonicalName
                        )
                    )
                    return ItemStack.EMPTY
                }
                val resultSlot = this.slots[slotId] as ResultSlot
                val resultStack: ItemStack = resultSlot.stack
                val heldStack: ItemStack = playerinventory.cursorStack
                if (!resultStack.isEmpty) {
                    val offer: ShulkerShopOffer.ShulkerTradeOffer = Objects.requireNonNull(
                        inventory.tradeOffer as ShulkerShopOffer.ShulkerTradeOffer,
                        "offer was null despite resultSlot not being empty"
                    )
                    if (actionType === SlotActionType.PICKUP) { // Simple click
                        if (heldStack.isEmpty) {
                            val soldStack: ItemStack = processTrade(resultSlot, offer, false)
                            playerinventory.cursorStack = soldStack
                            return soldStack
                        } else if (heldStack.maxCount > 1 &&
                            areItemsAndTagsEqual(offer.shulkerOffer.getSellingStack(), heldStack)
                            && !resultStack.isEmpty
                        ) {
                            val resountCOunt: Int = resultStack.count
                            if (resountCOunt + heldStack.count <= heldStack.maxCount) {
                                val soldStack: ItemStack = processTrade(resultSlot, offer, false)
                                heldStack.increment(resountCOunt)
                                return soldStack
                            }
                        }
                    } else { // SlotActionTypeIn == QUICK_MOVE = Shift-click
                        return if (!resultSlot.canTakeItems(player)) {
                            ItemStack.EMPTY
                        } else processTrade(resultSlot, offer, true)
                    }
                }
                ItemStack.EMPTY
            } else {
                super.onSlotClick(slotId, dragType, actionType, player)
            }
        } catch (e: IllegalAccessException) {
            log.error("Couldn't access Container#dragEvent variable through reflection", e)
            ItemStack.EMPTY
        }
    }

    fun processTrade(
        resultSlot: ResultSlot, offer: ShulkerShopOffer.ShulkerTradeOffer,
        repeat: Boolean
    ): ItemStack {
        val buyCount0: Int = offer.shulkerOffer.buyingStackFirst.count
        val buyCount1: Int = offer.shulkerOffer.buyingStackSecond.count
        val stack0: ItemStack = inventory.getStack(0).copy()
        val stack1: ItemStack = inventory.getStack(1).copy()
        val buyingStack0: ItemStack = stack0.copy()
        val buyingStack1: ItemStack = stack1.copy()
        buyingStack0.count = buyCount0
        buyingStack1.count = buyCount1
        var outStack: ItemStack
        var tradeCount = 0
        val limit: Int = offer.shulkerOffer.remainingUses
        do {
            outStack = shop.inventory.processTrade(
                offer.shulkerOffer,
                buyingStack0.copy(), buyingStack1.copy()
            )
            if (outStack.isEmpty ||
                repeat && !this.insertItem(outStack, 3, 39, true)
            ) {
                shop.inventory.rollbackLastTrade()
                break
            }
            offer.use() // Increment uses
            tradeCount++
        } while (repeat && tradeCount < limit && stack0.count >= buyCount0 * (tradeCount + 1) && stack1.count >= buyCount1 * (tradeCount + 1))
        if (tradeCount > 0) {
            resultSlot.stack = ItemStack.EMPTY
            stack0.decrement(tradeCount * buyCount0)
            stack1.decrement(tradeCount * buyCount1)
            inventory.setStack(0, stack0)
            inventory.setStack(1, stack1)
        }
        shop.updateOffersStock()
        return outStack
    }

    override fun canUse(playerIn: PlayerEntity?): Boolean {
        return super.canUse(playerIn) && !shop.isDeleted
    }

    inner class ResultSlot internal constructor(
        player: PlayerEntity?,
        merchantInventory: MerchantInventory?,
        slotIndex: Int,
        xPosition: Int,
        yPosition: Int
    ) : TradeOutputSlot(player, shop, merchantInventory, slotIndex, xPosition, yPosition)

    init {
        slots[2] = ResultSlot(playerInventory.player, inventory, 2, 220, 37)
    }
}