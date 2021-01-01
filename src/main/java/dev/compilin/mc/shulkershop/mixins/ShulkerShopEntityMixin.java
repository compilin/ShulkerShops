package dev.compilin.mc.shulkershop.mixins;

import dev.compilin.mc.shulkershop.GoalSelectorAccess;
import dev.compilin.mc.shulkershop.SShopEventListener;
import dev.compilin.mc.shulkershop.ShulkerShop;
import dev.compilin.mc.shulkershop.ShulkerShopEntity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.MovementType;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.mob.ShulkerEntity;
import net.minecraft.entity.passive.GolemEntity;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.math.*;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.UUID;

/**
 * Allows attaching a shopId (for persistent saving) and a shop reference (for easier access) to ShulkerEntity's
 */
@Mixin(ShulkerEntity.class)
public abstract class ShulkerShopEntityMixin extends GolemEntity implements ShulkerShopEntity {
    private static final String tagKey = "shulkershops:shopid";
    private @Nullable UUID shopId = null;
    private @Nullable ShulkerShop shop = null;

    @Shadow @Final protected static TrackedData<Optional<BlockPos>> ATTACHED_BLOCK;
    @Shadow private float prevOpenProgress;
    @Shadow private float openProgress;

    @Shadow
    public abstract int getPeekAmount();

    @Shadow
    public abstract Direction getAttachedFace();

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

    @Inject(at = @At("HEAD"), method = "move", cancellable = true)
    public void dontMove(MovementType type, Vec3d movement, CallbackInfo ci) {
        if (shopId != null) {
            ci.cancel();
        }
    }

    @Inject(at = @At("HEAD"), method = "tick", cancellable = true)
    public void tickShop(CallbackInfo ci) {
        if (this.world.isClient || shopId == null) {
            return; // Go back to vanilla tick
        }
        ci.cancel();
        super.tick();
        @Nullable BlockPos blockPos = this.dataTracker.get(ATTACHED_BLOCK).orElse(null);

        float g = (float) this.getPeekAmount() * 0.01F;
        this.prevOpenProgress = this.openProgress;
        if (this.openProgress > g) {
            this.openProgress = MathHelper.clamp(this.openProgress - 0.05F, g, 1.0F);
        } else if (this.openProgress < g) {
            this.openProgress = MathHelper.clamp(this.openProgress + 0.05F, 0.0F, g);
        }

        if (blockPos != null) {
            this.resetPosition((double)blockPos.getX() + 0.5D, blockPos.getY(), (double)blockPos.getZ() + 0.5D);
            // Never fully close the bounding box so that players can't place blocks "above" the shulker box
            double d = MathHelper.clamp(0.5D - (double) MathHelper.sin((0.5F + this.openProgress) * 3.1415927F) * 0.5D, 0.001f, 1.0f);
            Direction direction5 = this.getAttachedFace().getOpposite();
            this.setBoundingBox((new Box(this.getX() - 0.5D, this.getY(), this.getZ() - 0.5D, this.getX() + 0.5D, this.getY() + 1.0D, this.getZ() + 0.5D)).stretch((double)direction5.getOffsetX() * d, (double)direction5.getOffsetY() * d, (double)direction5.getOffsetZ() * d));
        }
    }
}
