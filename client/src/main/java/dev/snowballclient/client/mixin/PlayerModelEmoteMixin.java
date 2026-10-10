package dev.snowballclient.client.mixin;

import dev.snowballclient.client.emote.EmoteHolder;
import dev.snowballclient.client.emote.Pose;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Poses the player model for an emote, after Minecraft has posed it for walking, looking and
 * swinging: each limb the emote uses is blended towards the emote's pose, the others keep moving
 * as they would. Sleeves, jacket and trousers follow, being parts of the limbs they cover.
 */
@Mixin(PlayerModel.class)
public abstract class PlayerModelEmoteMixin {
	@Inject(method = "setupAnim(Lnet/minecraft/client/renderer/entity/state/AvatarRenderState;)V", at = @At("RETURN"))
	private void snowball$emotePose(AvatarRenderState state, CallbackInfo ci) {
		if (!(((Object) state) instanceof EmoteHolder holder) || !holder.snowball$emoting()) return;
		Pose pose = holder.snowball$pose();
		HumanoidModel<?> model = (HumanoidModel<?>) (Object) this;
		snowball$apply(model.head, pose, Pose.HEAD);
		snowball$apply(model.body, pose, Pose.BODY);
		snowball$apply(model.rightArm, pose, Pose.RIGHT_ARM);
		snowball$apply(model.leftArm, pose, Pose.LEFT_ARM);
		snowball$apply(model.rightLeg, pose, Pose.RIGHT_LEG);
		snowball$apply(model.leftLeg, pose, Pose.LEFT_LEG);
	}

	@Unique
	private static void snowball$apply(ModelPart part, Pose pose, int index) {
		part.xRot = pose.blend(index, 0, part.xRot);
		part.yRot = pose.blend(index, 1, part.yRot);
		part.zRot = pose.blend(index, 2, part.zRot);
		part.x += pose.shift(index, 0);
		part.y += pose.shift(index, 1);
		part.z += pose.shift(index, 2);
	}
}
