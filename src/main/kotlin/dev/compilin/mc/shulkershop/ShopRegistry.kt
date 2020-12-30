package dev.compilin.mc.shulkershop

import net.fabricmc.fabric.api.util.NbtType
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.ListTag
import net.minecraft.world.PersistentState
import java.util.*
import java.util.stream.Collectors
import kotlin.collections.HashMap

class ShopRegistry : PersistentState(ID) {
    private val registry: MutableMap<UUID, ShulkerShop> = Collections.synchronizedMap(HashMap())

    val allShops: List<ShulkerShop>
        get() = registry.values.toList()

    fun getShopById(id: UUID): ShulkerShop? {
        return registry[id]
    }

    fun newShulkerShop(owner: PlayerEntity): ShulkerShop {
        val shop = ShulkerShop(owner)
        registry[shop.uuid] = shop
        markDirty()
        return shop
    }

    fun deleteShop(shop: ShulkerShop) {
        SShopMod.getSelectionByShop(shop)
            .ifPresent { sel: SShopMod.PlayerShopSelection -> SShopMod.unsetPlayerSelection(sel.player.uuid) }
        if (registry.remove(shop.uuid) == null) {
            log.warn("SShopMod#deleteShop called twice!")
        }
        markDirty()
    }
    fun getShopsByOwnerID(ownerId: UUID): List<ShulkerShop> {
        return registry.values.stream()
            .filter { m: ShulkerShop -> m.ownerId == ownerId }
            .collect(Collectors.toList())
    }

    override fun fromTag(tag: CompoundTag) {
        synchronized(registry) {
            val shops: ListTag = tag.getList("shops", NbtType.COMPOUND)
            registry.clear()
            shops.forEach { shopNbt ->
                val shop: ShulkerShop = ShulkerShop.fromNBT(shopNbt as CompoundTag)
                registry[shop.uuid] = shop
            }
            log.info(String.format("Loaded %d shulker shops", registry.size))
        }
    }

    override fun toTag(tag: CompoundTag): CompoundTag {
        val shops = ListTag()

        registry.values.forEach {
            shops.add(it.writeNBT())
        }

        log.debug(java.lang.String.format("Saved %d shops to world data", shops.size))
        tag.put("shops", shops)
        return tag
    }

    companion object {
        const val ID = "shulkershops"
    }
}