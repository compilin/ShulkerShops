package dev.compilin.mc.shulkershop

import com.google.common.base.Preconditions
import dev.compilin.mc.shulkershop.SShopEventListener.tickUpdateInterval
import net.fabricmc.fabric.api.util.NbtType
import net.minecraft.entity.EntityType
import net.minecraft.entity.SpawnReason
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.inventory.SimpleInventory
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.NbtOps
import net.minecraft.nbt.StringTag
import net.minecraft.screen.GenericContainerScreenHandler
import net.minecraft.screen.SimpleNamedScreenHandlerFactory
import net.minecraft.server.world.ServerWorld
import net.minecraft.sound.SoundEvent
import net.minecraft.sound.SoundEvents
import net.minecraft.text.LiteralText
import net.minecraft.text.Text
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Direction
import net.minecraft.util.registry.RegistryKey
import net.minecraft.village.Merchant
import net.minecraft.village.TradeOffer
import net.minecraft.village.TradeOfferList
import net.minecraft.world.World
import java.lang.ref.WeakReference
import java.util.*
import java.util.function.Consumer
import java.util.function.Predicate
import kotlin.collections.HashMap
import kotlin.math.min

class ShulkerShop : Merchant {
    // Persistent data
    val uuid: UUID
    val ownerId: UUID

    var shulkerId: UUID? = null
        private set
    var shulkerPos: BlockDimPos? = null
        private set
    private val offers: MutableList<ShulkerShopOffer> = ArrayList<ShulkerShopOffer>()
    val inventory = ShopInventory()

    // Non-persistent
    private var shulker: WeakReference<ShulkerEntity?> = WeakReference(null)
    private var customerWindowID: Int? = null
    private var customer: PlayerEntity? = null
    private var selectingPlayer: PlayerEntity? = null
    var isDeleted = false
        private set

    /**
     * @return a shallow copy of the shop's display name
     */
    var name: Text
        set(name) {
            field = name.shallowCopy()
            shulker.get()?.customName = name
        }
    val shulkerOffers: List<ShulkerShopOffer>
        get() = Collections.unmodifiableList(offers)

    constructor(owner: PlayerEntity) {
        name = LiteralText(owner.name.asString() + "'s shop")
        uuid = UUID.randomUUID()
        ownerId = owner.uuid
    }

    constructor(nbt: CompoundTag) {
        uuid = nbt.getUuid("UUID")
        ownerId = nbt.getUuid("OwnerUUID")
        shulkerId = nbt.getUuid("ShulkerUUID")
        shulkerPos = BlockDimPos.fromTag(nbt.getCompound("ShulkerPos"))
        name = Text.Serializer.fromJson(nbt.getString("Name"))!!
        inventory.readTags(nbt.getList("Inventory", NbtType.COMPOUND))
        nbt.getList("Offers", NbtType.COMPOUND)
            .forEach { off ->
                try {
                    offers.add(ShulkerShopOffer.readNBT(this, off as CompoundTag))
                } catch (ex: Exception) {
                    log.error(
                        String.format("Exception while trying to instanciate shop offer in %s", this),
                        ex
                    )
                }
            }
        updateOffersStock()
    }

    private fun setShulkerAIGoals() {
        try {
            val goalSelector = (shulker.get() as ShulkerShopEntity).clearShulkerAIGoals()
            goalSelector.add(1, ShulkerLookAtCustomer(this))
            goalSelector.add(7, ShulkerLookAtPlayer(shulker.get()!!, 7f))
        } catch (e: IllegalAccessException) {
            throw RuntimeException("Got an error tryin to disable shulker attack AI", e)
        }
    }

    fun openShop(player: PlayerEntity) {
        currentCustomer = player
        val guiTitle: Text = name.shallowCopy()
        val optionalint: OptionalInt =
            player.openHandledScreen(SimpleNamedScreenHandlerFactory({ i, playerInventory, _ ->
                ShulkerShopContainer(
                    i,
                    playerInventory,
                    this
                )
            }, guiTitle))
        if (optionalint.isPresent) {
            customerWindowID = optionalint.asInt
            updateOffersStock()
            sendCustomerOffers()
        }
    }

    @Synchronized
    fun updateOffersStock() {
        checkNotDeleted()
        val content = HashMap<HashStack, Int>()
        val freeSpace = HashMap<HashStack, Int>()
        var emptySlots = 0
        for (i in 0 until inventory.size()) {
            val hash = HashStack(inventory.getStack(i))
            if (hash.stack.isEmpty) {
                emptySlots++
            } else {
                content[hash] = content.getOrDefault(hash, 0) + hash.stack.count
                if (hash.stack.count < hash.stack.maxCount) {
                    freeSpace[hash] = freeSpace.getOrDefault(hash, 0) + hash.stack.maxCount - hash.stack.count
                }
            }
        }
        var changed = false
        for (offer in offers) {
            changed = changed or offer.updateStock(content, freeSpace, emptySlots)
        }
        if (changed) {
            onOfferChanged()
        }
    }

    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    private fun sendCustomerOffers() {
        Preconditions.checkNotNull(customer, "Can't update offers for null customer")
        Preconditions.checkNotNull(customerWindowID, "Customer is set but window id is null!")
        customer!!.sendTradeOffers(
            customerWindowID!!, getOffers(), 1, 0,
            isLeveledMerchant, canRefreshTrades()
        )
    }

    fun openInventory(player: PlayerEntity) {
        player.openHandledScreen(SimpleNamedScreenHandlerFactory({ windowId, playerInventory, _ ->
            GenericContainerScreenHandler.createGeneric9x3(
                windowId,
                playerInventory,
                inventory
            )
        }, name))
    }

    fun addOffer(
        priceStack: ItemStack, sellStack: ItemStack,
        maxUses: Int
    ): Pair<Int, ShulkerShopOffer> {
        val offer = ShulkerShopOffer(this, priceStack, sellStack, 0, maxUses)
        offers.add(offer)
        updateOffersStock()
        return Pair(offers.size, offer)
    }

    fun deleteOffer(id: Int): ShulkerShopOffer {
        return offers.removeAt(id)
    }

    fun getShulker(): ShulkerEntity? {
        return shulker.get()
    }

    fun setShulker(shulker: ShulkerEntity?) {
        if (this.shulker.get() === shulker) {
            return
        }
        log.debug(
            String.format(
                "Setting ShulkerShop %s's shulkerId to %s", uuid.toString(),
                shulker?.uuid?.toString() ?: "null"
            )
        )
        this.shulker = WeakReference<ShulkerEntity?>(shulker)
        if (shulker != null) {
            (shulker as ShulkerShopEntity).shop = this
            shulkerId = shulker.uuid
            shulkerPos = BlockDimPos(BlockPos(shulker.pos), shulker.world.registryKey)
            setShulkerAIGoals()
        } else {
            shulkerId = null
            shulkerPos = null
        }
    }

    /**
     * @return The player that is currently selecting the given shop
     */
    fun getSelectingPlayer(): PlayerEntity? {
        if (selectingPlayer != null && !selectingPlayer!!.isAlive) {
            setSelectingPlayer(null)
        }
        return selectingPlayer
    }

    /**
     * @param selecting the player currently selecting the shop
     */
    fun setSelectingPlayer(selecting: PlayerEntity?) {
        if (selecting != null) {
            checkNotDeleted()
        }
        selectingPlayer = selecting
        currentCustomer = null // Close the trading interface for any eventual customer
    }

    fun spawnShulker(world: ServerWorld, player: PlayerEntity, pos: BlockPos, face: Direction): Boolean {
        getShulker()?.remove()
        val nbt = CompoundTag()
        val entityTag = CompoundTag()
        entityTag.putInt("Invulnerable", 1)
        entityTag.putByte("AttachFace", face.id.toByte())
        nbt.put("EntityTag", entityTag)
        val shulker: ShulkerEntity? = EntityType.SHULKER.spawn(
            world, nbt, name, player, pos, SpawnReason.COMMAND,
            false, false
        )
        if (shulker != null) {
            try {
                (shulker as ShulkerShopEntity).shopId = uuid
                shulker.customName = name
                setShulker(shulker)
                return true
            } catch (ex: Throwable) { // If anything goes wrong
                log.error("Got an exception during shulker spawning, removing the mob", ex)
                shulker.remove()
            }
        } else {
            log.error("Failed to spawn shulker")
        }
        return false
    }

    fun delete() {
        SShopMod.shopRegistry!!.deleteShop(this)
        isDeleted = true
        customer = null
        shulker.get()?.remove()
    }

    fun checkNotDeleted() {
        check(!isDeleted) { "Tried to use a deleted shop" }
    }

    fun onOfferChanged() {
        if (currentCustomer != null) {
            sendCustomerOffers()
        }
    }

    /**
     * Called periodically
     */
    fun onTickUpdate() {
        val shulker: ShulkerEntity? = getShulker()
        if (shulker != null) {
            shulker.ambientSoundChance = -2 * tickUpdateInterval // Silence, mob
        }
    }

    /* *****
		IMerchant overrides
	****** */
    override fun setCurrentCustomer(player: PlayerEntity?) {
        if (player != null && selectingPlayer != null &&
            player.uuid != selectingPlayer!!.uuid
        ) {
            checkNotDeleted()
            throw IllegalStateException("setCustomer called while shop is being edited")
        }
        customer = player
        if (player == null) {
            customerWindowID = null
        }
    }

    override fun getCurrentCustomer(): PlayerEntity? {
        if (customer != null && !customer!!.isAlive) {
            currentCustomer = null
        }
        return customer
    }

    override fun getOffers(): TradeOfferList {
        checkNotDeleted()
        val offers = TradeOfferList()
        this.offers.forEach(Consumer { offers.add(it.getTradeOffer()) })
        return offers
    }

    override fun canRefreshTrades(): Boolean {
        return true
    }

    /**
     * This is @Dist(Client) so we don't care
     */
    override fun setOffersFromServer(list: TradeOfferList?) {}

    override fun trade(offer: TradeOffer?) {
        throw RuntimeException("This should not get called")
    }

    override fun onSellingItem(stack: ItemStack?) {}

    override fun getMerchantWorld(): World {
        val shulkerEntity: ShulkerEntity = shulker.get()
            ?: throw NullPointerException("Tried to get ShulkerShop's World before while it was assigned")
        return shulkerEntity.world
    }

    /**
     * Gets the XP to be delivered upon closing the merchant GUI
     */
    override fun getExperience(): Int = 0

    /**
     * Client-only
     */
    override fun setExperienceFromServer(experience: Int) {}

    /**
     * checks whether the merchant has a level
     */
    override fun isLeveledMerchant(): Boolean {
        return false
    }

    override fun getYesSound(): SoundEvent {
        return SoundEvents.ENTITY_SHULKER_AMBIENT
    }

    override fun toString(): String {
        return java.lang.String.format(
            "[ShulkerShop %s, owner: %s, name: %s]",
            uuid.toString(),
            ownerId.toString(),
            name.asString()
        )
    }

    fun writeNBT(): CompoundTag {
        val nbt = CompoundTag()
        nbt.putUuid("UUID", uuid)
        nbt.putUuid("OwnerUUID", ownerId)
        nbt.putUuid("ShulkerUUID", shulkerId)
        nbt.put("ShulkerPos", shulkerPos!!.toTag())
        nbt.putString("Name", Text.Serializer.toJson(name))
        nbt.put("Inventory", inventory.tags)
        val offers = ListTag()
        this.offers.forEach { off: ShulkerShopOffer -> offers.add(off.writeNBT()) }
        nbt.put("Offers", offers)
        return nbt
    }

    /**
     * Simple data class to keep track of both position and dimension
     */
    class BlockDimPos internal constructor(val pos: BlockPos, val dim: RegistryKey<World>) {

        fun toTag(): CompoundTag {
            val nbt = CompoundTag()
            nbt.putInt("X", pos.x)
            nbt.putInt("Y", pos.y)
            nbt.putInt("Z", pos.z)
            val dimTag = World.CODEC.encodeStart(NbtOps.INSTANCE, dim)
                .resultOrPartial { log.error("Error in BlockDimPos#toTag : $it") }
                .orElse(StringTag.of("minecraft:overworld"))
            nbt.put("Dim", dimTag)
            return nbt
        }

        companion object {
            fun fromTag(tag: CompoundTag): BlockDimPos {
                return BlockDimPos(
                    BlockPos(tag.getInt("X"), tag.getInt("Y"), tag.getInt("Z")),
                    World.CODEC.parse(NbtOps.INSTANCE, tag.get("Dim"))
                        .resultOrPartial { log.error("Error in BlockDimPos#fromTag : $it") }
                        .orElse(World.OVERWORLD)
                )
            }
        }
    }

    inner class ShopInventory internal constructor() : SimpleInventory(INVENTORY_SIZE) {
        /**
         * Holds a backup of all the slots that were modified during the last trade.
         * Gets cleared on new trades
         */
        private var backup: Array<ItemStack?> = emptyArray()

        /**
         * Copy-pasted from [net.minecraft.inventory.EnderChestInventory.readTags]
         *
         * @param nbt an NBT list of the content of this inventory
         */
        override fun readTags(nbt: ListTag) {
            for (j in 0 until size()) {
                setStack(j, ItemStack.EMPTY)
            }

            for (j in 0 until tags.size) {
                val compoundTag = tags.getCompound(j)
                val k: Int = compoundTag.getByte("Slot").toInt() and 255
                if (k >= 0 && k < size()) {
                    setStack(k, ItemStack.fromTag(compoundTag))
                }
            }
        }

        /**
         * Copy-pasted from [net.minecraft.inventory.EnderChestInventory.getTags]
         *
         * @return an NBT list of the content of this inventory
         */
        override fun getTags(): ListTag {
            val tags = ListTag()

            for (i in 0 until size()) {
                val itemStack = getStack(i)
                if (!itemStack.isEmpty) {
                    val compoundTag = CompoundTag()
                    compoundTag.putByte("Slot", i.toByte())
                    itemStack.toTag(compoundTag)
                    tags.add(compoundTag)
                }
            }

            return tags
        }

        fun processTrade(offer: ShulkerShopOffer, firstDeposit: ItemStack, secondDeposit: ItemStack): ItemStack {
            backup = arrayOfNulls(size())
            return try {
                if (!mergeItemStack(firstDeposit, backup) || !mergeItemStack(secondDeposit, backup)) {
                    log.error("processTrade: couldn't fit buying stacks into the shop's inventory")
                    rollbackLastTrade()
                    return ItemStack.EMPTY
                }
                val outStack = gatherItemStack(
                    { areItemsAndTagsEqual(it, offer.getSellingStack()) },
                    offer.getSellingStack().count
                )
                if (outStack.count < offer.getSellingStack().count) {
                    log.error("processTrade: couldn't find enough in the shop's inventory to fulfill the sellingStack")
                    rollbackLastTrade()
                    return ItemStack.EMPTY
                }
                outStack
            } catch (e: Throwable) {
                rollbackLastTrade()
                throw e
            }
        }

        private fun mergeItemStack(stack: ItemStack, backup: Array<ItemStack?>): Boolean {
            // Reverse order: we put items in towards the end first
            run {
                var i: Int = size() - 1
                while (i >= 0 && !stack.isEmpty) {
                    val slotContent: ItemStack = getStack(i)
                    if (!slotContent.isEmpty) {
                        if (areItemsAndTagsEqual(slotContent, stack) &&
                            slotContent.count < slotContent.maxCount
                        ) {
                            val moveCount = min(
                                slotContent.maxCount - slotContent.count,
                                stack.count
                            )
                            if (backup[i] == null) {
                                backup[i] = slotContent.copy()
                            }
                            slotContent.increment(moveCount)
                            stack.decrement(moveCount)
                        }
                    }
                    i--
                }
            }
            if (stack.isEmpty) {
                return true
            }
            var i: Int = size() - 1
            while (i >= 0 && !stack.isEmpty) {
                if (getStack(i).isEmpty) {
                    setStack(i, stack)
                    return true
                }
                i--
            }
            return false
        }

        private fun gatherItemStack(target: Predicate<ItemStack>, targetCount: Int): ItemStack {
            var result = ItemStack.EMPTY
            var i: Int = size() - 1
            while (i >= 0 && result.count < targetCount) {
                var slotContent: ItemStack = getStack(i)
                if (target.test(slotContent)) {
                    val draw = min(targetCount - result.count, slotContent.count)
                    if (result.isEmpty) {
                        result = slotContent.split(draw)
                        if (slotContent.isEmpty) {
                            slotContent = ItemStack.EMPTY
                        }
                    } else {
                        result.increment(draw)
                        slotContent.decrement(draw)
                    }
                    setStack(i, slotContent)
                }
                i--
            }
            return result
        }

        fun rollbackLastTrade() {
            for (i in 0 until size()) {
                if (backup[i] != null) {
                    setStack(i, backup[i])
                    backup[i] = null
                }
            }
        }

        override fun canPlayerUse(player: PlayerEntity?): Boolean {
            return player == selectingPlayer // Closes the inventory if selection times out
        }
    }

    /**
     * This is a wrapper around ItemStack to work with Hash(Set|Map)s. Two HashStacks will be considered equal and return the smae hashCode if they are equal or stackable,
     * i.e if they hold the same type of item with the same tags
     */
    class HashStack internal constructor(val stack: ItemStack) {
        val hash: Int = Objects.hash(
            stack.item,
            if (stack.tag != null) stack.tag.hashCode() else null
        )

        override fun equals(other: Any?): Boolean {
            if (other is HashStack) {
                return areItemsAndTagsEqual(stack, other.stack)
            }
            return super.equals(other)
        }

        override fun hashCode(): Int {
            return hash
        }

    }

    companion object {
        const val UNLIMITED = 1 shl 20 // Highest allowed value for a shop offer's maxUses
        const val INVENTORY_SIZE = 27 // Double chest equivalent

        /* 	***********************
		 NBT (de)serialization
		*********************** */
        fun fromNBT(nbt: CompoundTag): ShulkerShop {
            return ShulkerShop(nbt)
        }
    }
}