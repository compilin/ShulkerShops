package dev.compilin.mc.shulkershop.mixins;

import dev.compilin.mc.shulkershop.GoalSelectorAccess;
import dev.compilin.mc.shulkershop.SShopEventListener;
import dev.compilin.mc.shulkershop.ShulkerShop;
import dev.compilin.mc.shulkershop.ShulkerShopEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.passive.GolemEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;

/**
 * Allows attaching a shopId (for persistent saving) and a shop reference (for easier access) to ShulkerEntity's
 */
@Mixin(ShulkerEntity.class)
public class ShulkerShopEntityMixin extends GolemEntity implements ShulkerShopEntity {
    private static final String tagKey = "shulkershops:shopid";
    private @Nullable UUID shopId = null;
    private @Nullable ShulkerShop shop = null;

    public ShulkerShopEntityMixin(EntityType<? extends ShulkerEntity> entityType, World world) {
        super(entityType, world);
    }

    @Override
    public @Nullable UUID getShopId() {
        return shopId;
    }

    @Override
    public void setShopId(@Nullable UUID shopId) {
        this.shopId = shopId;
    }

    @Nullable
    @Override
    public ShulkerShop getShop() {
        return shop;
    }

    @Override
    public void setShop(@Nullable ShulkerShop shop) {
        this.shop = shop;
    }

    @Inject(at = @At("TAIL"), method = "writeCustomDataToTag")
    private void writeShopIdToTag(CompoundTag tag, CallbackInfo ci) {
        if (!world.isClient && shopId != null) {
            tag.putUuid(tagKey, shopId);
            /* We set NoAI on save and remove it on load in case the world gets loaded without the mod at any point, the shulker won't move or attack
             * players */
            tag.putBoolean("NoAI", true);
        }
    }

    @Inject(at = @At("HEAD"), method = "readCustomDataFromTag")
    private void readShopIdFromTag(CompoundTag tag, CallbackInfo ci) {
        if (!world.isClient && tag.containsUuid(tagKey)) {
            shopId = tag.getUuid(tagKey);
            tag.remove("NoAI");
            SShopEventListener.INSTANCE.onShulkerSpawn((ShulkerEntity) (Object)this);
        }
    }

    @Inject(at = @At("HEAD"), method = "damage", cancellable = true)
    private void checkDamage(DamageSource source, float amount, CallbackInfoReturnable<Boolean> cir) {
        if (!world.isClient && shopId != null) {
            cir.setReturnValue(false);
        }
    }

    @Override
    public @NotNull GoalSelector clearShulkerAIGoals() {
        if (world.isClient) throw new AssertionError();
        ((GoalSelectorAccess) targetSelector).getGoals().clear();
        ((GoalSelectorAccess) goalSelector).getGoals().clear();
        return goalSelector;
    }
}
