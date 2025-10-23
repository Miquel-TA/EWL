package owl.mixin;

import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import owl.command.CommandGate;

@Mixin(CommandManager.class)
public class CommandManagerMixin {
    @Inject(method = "executeWithPrefix", at = @At("HEAD"), cancellable = true)
    private void owl$guardUnauthenticated(ServerCommandSource source, String command, CallbackInfoReturnable<Integer> cir) {
        if (CommandGate.shouldBlock(source, command)) {
            cir.setReturnValue(1);
        }
    }
}
