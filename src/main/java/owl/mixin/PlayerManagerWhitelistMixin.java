package owl.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.PlayerManager;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import owl.OfflineWhitelistLogin;

import java.net.SocketAddress;

@Mixin(PlayerManager.class)
public class PlayerManagerWhitelistMixin {
    @Inject(method = "isWhitelisted", at = @At("RETURN"), cancellable = true)
    private void owl$allowOwlWhitelist(GameProfile profile, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            return;
        }
        if (OfflineWhitelistLogin.INSTANCE.isUserAuthorized(profile.getName())) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "checkCanJoin", at = @At("RETURN"), cancellable = true)
    private void owl$enforceOwlWhitelist(SocketAddress address, GameProfile profile, CallbackInfoReturnable<Text> cir) {
        if (!OfflineWhitelistLogin.INSTANCE.isConfigLoaded()) {
            return;
        }
        if (cir.getReturnValue() != null) {
            return;
        }
        if (!OfflineWhitelistLogin.INSTANCE.isUserAuthorized(profile.getName())) {
            cir.setReturnValue(Text.literal(OfflineWhitelistLogin.UNAUTHORIZED_JOIN_MESSAGE));
        }
    }
}
