package dev.snowballclient.client.mixin;

import dev.snowballclient.client.platform.SkinOverrides;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The last word on how a player is drawn: once Minecraft (and any other mod) has filled in what it
 * will draw of them, the Snowball cape and a just-equipped skin go on top. Other mods' cape hooks
 * on the skin lookup can no longer win just by running first.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarSkinMixin {
	// Written in two halves: the build's rename rules would otherwise turn "extractRenderState("
	// into "render(" for 1.21.11, where this method has the same name as on 26.x.
	@Unique
	private static final String FILL = "extract" + "RenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V";

	@Inject(method = FILL, at = @At("TAIL"))
	private void snowball$skin(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (state.skin != null) state.skin = SkinOverrides.apply(avatar.getUUID(), state.skin);
	}
}
