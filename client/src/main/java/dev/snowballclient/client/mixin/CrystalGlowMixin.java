package dev.snowballclient.client.mixin;

import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.render.CrystalGlow;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.state.EndCrystalRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Crystal Glow: an end crystal's light is set once Minecraft has worked it out, so with the glow
 * on it is drawn at full brightness. Optional: if this ever stops matching, crystals are simply lit
 * as usual rather than the game failing to start.
 */
@Mixin(EntityRenderer.class)
public abstract class CrystalGlowMixin {
	// Built from parts so the version generator's render( rename leaves it alone.
	@Unique
	private static final String EXTRACT = "extract" + "RenderState(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/client/renderer/entity/state/EntityRenderState;F)V";

	@Inject(method = EXTRACT, at = @At("TAIL"), require = 0)
	private void snowball$crystalGlow(Entity entity, EntityRenderState state, float partialTick, CallbackInfo ci) {
		if (state instanceof EndCrystalRenderState && ModuleRegistry.CRYSTAL_GLOW != null && ModuleRegistry.CRYSTAL_GLOW.glows()) {
			state.lightCoords = CrystalGlow.FULL_BRIGHT;
		}
	}
}
