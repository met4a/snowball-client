package dev.snowballclient.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.snowballclient.client.platform.CrashRecovery;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
//? if <26.1 {
/*import net.minecraft.CrashReport;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.function.Supplier;
*///?}

/**
 * Anti-Crash's hook in the game loop. Minecraft runs one frame at a time (ticking, input, screens,
 * drawing) and closes the game when a frame throws; here a throwing frame is handed to
 * {@link CrashRecovery} first, and only crashes it turns down reach Minecraft's own handler.
 *
 * <p>The method's name is {@link dev.snowballclient.client.crash.CrashCulprit#GUARD_METHOD}, which
 * keeps this frame, present under every crash, from being taken for the cause.
 */
@Mixin(Minecraft.class)
public class MinecraftCrashMixin {
	@WrapOperation(method = "run", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;runTick(Z)V"))
	private void snowball$antiCrash(Minecraft game, boolean advanceGameTime, Operation<Void> frame) {
		try {
			frame.call(game, advanceGameTime);
		} catch (Throwable error) {
			if (!CrashRecovery.recover(game, error)) throw error;
			return;
		}
		CrashRecovery.frameDone(game);
	}

	//? if <26.1 {
	/*// A crash on another thread (the integrated server, a worker) is parked here and turned into a
	// crash at the top of the next loop, outside the frame above.
	@Shadow
	private Supplier<CrashReport> delayedCrash;

	@Inject(method = "handleDelayedCrash", at = @At("HEAD"), cancellable = true)
	private void snowball$antiCrashDelayed(CallbackInfo ci) {
		Supplier<CrashReport> pending = delayedCrash;
		if (pending == null) return;
		CrashReport report = pending.get();
		delayedCrash = null;
		if (CrashRecovery.recover((Minecraft) (Object) this, report.getException(), report)) ci.cancel();
		else delayedCrash = pending;
	}
	*///?}
}
