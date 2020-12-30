package dev.compilin.mc.shulkershop.mixins;

import dev.compilin.mc.shulkershop.SShopEventListener;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayerEntity.class)
public class ServerPlayerEntityMixin {

    /**
     * Adds a hook for player logout events
     * @param ci callback info
     */
    @Inject(at = @At("TAIL"), method = "onDisconnect")
    void disconnectHook(CallbackInfo ci) {
        SShopEventListener.INSTANCE.onPlayerLoggedOut((ServerPlayerEntity) (Object) this);
    }
}
