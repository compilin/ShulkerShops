package dev.compilin.mc.shulkershop.mixins;

import dev.compilin.mc.shulkershop.ShulkerShop;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.village.Merchant;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Objects;

@Mixin(MerchantScreenHandler.class)
public class MerchantScreenHandlerMixin {
    @Final
    @Shadow
    private Merchant merchant;

    /**
     * Prevents a cast error because ShulkerShop isn't an Entity
     * @param ci callback info
     */
    @Inject(at = @At("HEAD"), method = "playYesSound", cancellable = true)
    void playYesSoundCheck(CallbackInfo ci) {
        if (!merchant.getMerchantWorld().isClient && merchant instanceof ShulkerShop) {
            BlockPos pos = Objects.requireNonNull(((ShulkerShop) merchant).getShulkerPos()).getPos();
            this.merchant.getMerchantWorld().playSound(pos.getX(), pos.getY(), pos.getZ(),
                    this.merchant.getYesSound(), SoundCategory.NEUTRAL, 1.0F, 1.0F, false);
            ci.cancel();
        }
    }
}
