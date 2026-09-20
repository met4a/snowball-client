package dev.snowballclient.client.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.snowballclient.client.module.ModuleRegistry;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Applies the Zoom module to the field of view, exactly like changing the FOV setting would. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
	@ModifyReturnValue(method = "getFov", at = @At("RETURN"))
	private float snowball$zoom(float fov) {
		return ModuleRegistry.ZOOM == null ? fov : ModuleRegistry.ZOOM.modifyFov(fov);
	}
}
