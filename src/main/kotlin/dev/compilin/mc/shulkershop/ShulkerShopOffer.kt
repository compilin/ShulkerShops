package dev.compilin.mc.shulkershop

import com.google.common.base.Preconditions
import dev.compilin.mc.shulkershop.ShulkerShop.HashStack
import net.fabricmc.fabric.api.util.NbtType
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.StringTag
import net.minecraft.text.LiteralText
import net.minecraft.text.MutableText
import net.minecraft.util.Formatting
import net.minecraft.village.TradeOffer
import java.util.*
import kotlin.math.min

class ShulkerShopOffer internal constructor(
    val shop: ShulkerShop,
    var buyingStackFirst: ItemStack,
    var buyingStackSecond: ItemStack,
    private var sellingStack: ItemStack,
    /**
     * Number of times this offer has been used
     */
    var uses: Int,
    /**
     * Max uses as defined by the user, renamed to useLimit to avoid ambiguity with TradeOffer.maxUses
     */
    private var useLimit: Int
) {
    /**
     * Max uses as defined by the stock for the sold item
     */
    var stock = -1
        private set

    /**
     * Max uses as defined by the free space for the bought item(s)
     */
    var freeSpace = -1
        private set

    /**
     * TradeOffer mirroring this offer's characteristics, to be regenerated on getTradeOffer() if useLimit doesn't match anymore
     */
    private var tradeOffer: TradeOffer? = null

    internal constructor(
        shop: ShulkerShop,
        buyingStack: ItemStack,
        sellingStack: ItemStack,
        uses: Int,
        useLimit: Int
    ) : this(
        shop,
        splitOverstackedFirst(buyingStack, true),
        splitOverstackedFirst(buyingStack, false),
        sellingStack,
        uses,
        useLimit
    )

    fun writeNBT(): CompoundTag {
        shop.checkNotDeleted()
        val nbt = CompoundTag()
        nbt.put("buyingStackFirst", buyingStackFirst.toTag(CompoundTag()))
        nbt.put("buyingStackSecond", buyingStackSecond.toTag(CompoundTag()))
        nbt.put("sellingStack", getSellingStack().toTag(CompoundTag()))
        nbt.putInt("useLimit", useLimit)
        nbt.putInt("uses", uses)
        return nbt
    }

    fun getSellingStack(): ItemStack {
        return sellingStack
    }

    fun getUseLimit(): Int {
        return useLimit
    }

    val isUnlimited: Boolean
        get() = useLimit >= ShulkerShop.UNLIMITED
    val remainingUses: Int
        get() {
            if (stock < 0 || freeSpace < 0) {
                log.warn("Accessed getRemainingUses before stockedUses and freeSpaceUses was set!")
            }
            return min(
                if (isUnlimited) ShulkerShop.UNLIMITED else useLimit - uses,
                min(stock, freeSpace)
            ).coerceAtLeast(0)
        }

    fun getTradeOffer(): TradeOffer {
        if (tradeOffer == null) {
            log.warn("getTradeOffer called before tradeOffer was generated")
            refreshTradeOffer()
        }
        return tradeOffer!!
    }

    fun updateStock(content: HashMap<HashStack, Int>, freeSpace: HashMap<HashStack, Int>, emptySlts: Int): Boolean {
        var emptySlots = emptySlts
        val buying1 = HashStack(buyingStackFirst)
        val buying2 = HashStack(buyingStackSecond)
        val selling = HashStack(getSellingStack())
        val stock = content.getOrDefault(selling, 0) / selling.stack.count
        var space: Int
        if (buying2.stack.isEmpty || buying1 == buying2) {
            space = if (buying1.stack.isStackable) (freeSpace.getOrDefault(
                buying1,
                0
            ) + emptySlots * buying1.stack.maxCount) / (buying1.stack.count + buying2.stack.count) else emptySlots
        } else {
            var freeSpace1 = freeSpace.getOrDefault(buying1, 0)
            var freeSpace2 = freeSpace.getOrDefault(buying2, 0)
            // If the buying stacks are different we don't have much choice but to "simulate" buying to check the stock
            space = 0
            while (true) {
                freeSpace1 -= buying1.stack.count
                if (freeSpace1 < 0) {
                    if (emptySlots > 0) {
                        freeSpace1 += buying1.stack.maxCount
                        emptySlots--
                    } else {
                        break
                    }
                }
                freeSpace2 -= buying2.stack.count
                if (freeSpace2 < 0) {
                    if (emptySlots > 0) {
                        freeSpace2 += buying2.stack.maxCount
                        emptySlots--
                    } else {
                        break
                    }
                }
                space++
            }
        }
        log.debug(
            String.format(
                "Offer %s, stock: %d => %d, space: %d => %d", this.toString(),
                this.stock, stock, this.freeSpace, space
            )
        )
        if (stock != this.stock || space != this.freeSpace) {
            this.stock = stock
            this.freeSpace = space
            refreshTradeOffer()
            return true
        }
        return false
    }

    fun setBuyingStack(stack: ItemStack) {
        setBuyingStack(splitOverstackedFirst(stack, true), splitOverstackedFirst(stack, false))
    }

    fun setBuyingStack(stackFirst: ItemStack, stackSecond: ItemStack) {
        shop.checkNotDeleted()
        Preconditions.checkArgument(!stackFirst.isEmpty, "setsellingStack: empty first stack given")
        buyingStackFirst = stackFirst
        buyingStackSecond = stackSecond
        refreshTradeOffer()
    }

    fun setSellingStack(stack: ItemStack) {
        shop.checkNotDeleted()
        Preconditions.checkArgument(!stack.isEmpty, "setsellingStack: empty stack given")
        sellingStack = stack
        refreshTradeOffer()
    }

    fun setUseLimit(useLimit: Int) {
        shop.checkNotDeleted()
        Preconditions.checkArgument(useLimit >= 0, "setUseLimit: useLimit is negative: %d", useLimit)
        this.useLimit = useLimit
        refreshTradeOffer()
    }

    private fun refreshTradeOffer() {
        tradeOffer = ShulkerTradeOffer()
        shop.onOfferChanged()
    }

    fun toTextComponent(): MutableText {
        shop.checkNotDeleted()
        val buying1 = buyingStackFirst
        val buying2 = buyingStackSecond
        val text: MutableText = if (buying2.isEmpty || areItemsAndTagsEqual(buying1, buying2)) {
            LiteralText("${buying1.count + buying2.count}x")
                .append(buying1.toHoverableText())
        } else {
            LiteralText(buying1.count.toString() + "x").append(buying1.toHoverableText())
                .append(" + " + buying2.count + "x").append(buying2.toHoverableText())
        }
        text.append(" → " + getSellingStack().count + "x")
            .append(getSellingStack().toHoverableText())
        val count: MutableText = LiteralText(
            String.format(
                " (%d / %s, stock: %d, space: %d)", uses,
                if (isUnlimited) "∞" else useLimit.toString(), stock, freeSpace
            )
        )
        if (useLimit in 0..uses) {
            count.formatted(Formatting.RED)
        }
        return text.append(count)
    }

    override fun toString(): String {
        return toTextComponent().string
    }

    fun addStockInfo(stck: ItemStack): ItemStack {
        var stack = stck
        var tag: CompoundTag? = stack.getSubTag("display")
        if (tag == null) {
            tag = CompoundTag()
        }

        // Note: returns an empty list if nonexistent
        val lore: ListTag = tag.getList("Lore", NbtType.COMPOUND)
        if (!tag.contains("Lore")) {
            tag.put("Lore", lore)
        }
        lore.add(StringTag.of(String.format("\"Stock: %d\"", stock)))
        lore.add(StringTag.of(String.format("\"Free space: %d\"", freeSpace)))
        stack = stack.copy()
        stack.putSubTag("display", tag)
        return stack
    }

    inner class ShulkerTradeOffer internal constructor() : TradeOffer(
        buyingStackFirst, buyingStackSecond, addStockInfo(
            sellingStack
        ), 0, remainingUses, 0, 0f
    ) {
        //	/**
        //	 * @return the ItemStack to be added to the player's inventory on use of the offer
        //	 */
        //	@Override
        //	public ItemStack func_222206_f() {
        //		return super.func_222206_f();
        //	}
        val shulkerOffer: ShulkerShopOffer
            get() = this@ShulkerShopOffer

        /**
         * This increments the offer's uses counter
         */
        override fun use() {
            shop.checkNotDeleted()
            check(uses + 1 <= useLimit) { "Tried to increment ShulkerShop offer past useLimit" }
            this@ShulkerShopOffer.uses++
            super.use()
        }

        override fun toString(): String {
            val buying1: ItemStack = buyingStackFirst
            val buying2: ItemStack = buyingStackSecond
            val sb = StringBuilder()
            if (buying2.isEmpty || areItemsAndTagsEqual(buying1, buying2)) {
                sb.append(buying1.count)
                    .append(buying2.count).append("x").append(buying1.toHoverableText().string)
            } else {
                sb.append(buying1.count).append("x").append(buying1.toHoverableText().string)
                    .append(" + ").append(buying2.count).append("x")
                    .append(buying2.toHoverableText().string)
            }
            sb.append(" → ").append(getSellingStack().count).append("x")
                .append(getSellingStack().toHoverableText().string)
            sb.append(" (${uses} / ${maxUses})")
            return sb.toString()
        } //	/**
        //	 * Checks whether the buying stacks match what the offer expects
        //	 *
        //	 * @param p_222204_1_ the content of the first buying stack of the merchant interface
        //	 * @param p_222204_2_ the content of the second buying stack of the merchant interface
        //	 * @return True if the offer is fulfilled and the sellingStack can be dispensed
        //	 */
        //	@Override
        //	public boolean func_222204_a(ItemStack p_222204_1_, ItemStack p_222204_2_) {
        //		return super.func_222204_a(p_222204_1_, p_222204_2_);
        //	}
        //	@Override
        //	public boolean func_222215_b(ItemStack p_222215_1_, ItemStack p_222215_2_) {
        //		return super.func_222215_b(p_222215_1_, p_222215_2_);
        //	}
    }

    companion object {
        fun readNBT(shop: ShulkerShop, nbt: CompoundTag): ShulkerShopOffer {
            val buyingStackFirst: ItemStack = ItemStack.fromTag(nbt.getCompound("buyingStackFirst"))
            val buyingStackSecond: ItemStack = ItemStack.fromTag(nbt.getCompound("buyingStackSecond"))
            val sellingStack: ItemStack = ItemStack.fromTag(nbt.getCompound("sellingStack"))
            val useLimit: Int = nbt.getInt("useLimit")
            val uses: Int = nbt.getInt("uses")
            return ShulkerShopOffer(shop, buyingStackFirst, buyingStackSecond, sellingStack, uses, useLimit)
        }

        private fun splitOverstackedFirst(stack: ItemStack, first: Boolean): ItemStack {
            return if (stack.count > stack.maxCount) {
                val copy = stack.copy()
                copy.count = if (first) stack.maxCount else stack.count - stack.maxCount
                copy
            } else {
                if (first) stack else ItemStack.EMPTY
            }
        }
    }
}