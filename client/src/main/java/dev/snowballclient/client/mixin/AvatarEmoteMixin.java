package dev.snowballclient.client.mixin;

import dev.snowballclient.client.emote.EmoteHolder;
import dev.snowballclient.client.emote.Emotes;
import dev.snowballclient.client.emote.Pose;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Works out a player's emote while Minecraft fills in what it will draw of them. A spin turns the
 * whole body: the player's body yaw is turned for the moment the state is filled, then put back,
 * so nothing outside the drawing ever sees it.
 */
@Mixin(AvatarRenderer.class)
public abstract class AvatarEmoteMixin {
	// Written in two halves: the build's rename rules would otherwise turn "extractRenderState("
	// into "render(" for 1.21.11, where this method has the same name as on 26.x.
	@Unique
	private static final String FILL = "extract" + "RenderState(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;F)V";
	@Unique
	private float snowball$spun;

	@Inject(method = FILL, at = @At("HEAD"))
	private void snowball$emoteBefore(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		EmoteHolder holder = (EmoteHolder) (Object) state;
		Pose pose = holder.snowball$pose();
		boolean emoting = Emotes.pose(avatar.getUUID(), System.currentTimeMillis(), pose);
		holder.snowball$setEmoting(emoting);
		snowball$spun = emoting ? (float) Math.toDegrees(pose.spin * pose.weight) : 0f;
		if (snowball$spun != 0f) {
			avatar.yBodyRot += snowball$spun;
			avatar.yBodyRotO += snowball$spun;
		}
	}

	@Inject(method = FILL, at = @At("TAIL"))
	private void snowball$emoteAfter(Avatar avatar, AvatarRenderState state, float partialTick, CallbackInfo ci) {
		if (snowball$spun != 0f) {
			avatar.yBodyRot -= snowball$spun;
			avatar.yBodyRotO -= snowball$spun;
			snowball$spun = 0f;
		}
	}
}
