package dev.snowballclient.client.mixin;

import dev.snowballclient.client.emote.EmoteHolder;
import dev.snowballclient.client.emote.Pose;
import net.minecraft.client.renderer.entity.state.AvatarRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/** Lets a player's render state carry the emote pose from when it is filled to when the model is posed. */
@Mixin(AvatarRenderState.class)
public class AvatarRenderStateMixin implements EmoteHolder {
	@Unique
	private Pose snowball$pose;
	@Unique
	private boolean snowball$emoting;

	@Override
	public Pose snowball$pose() {
		if (snowball$pose == null) snowball$pose = new Pose();
		return snowball$pose;
	}

	@Override
	public boolean snowball$emoting() {
		return snowball$emoting;
	}

	@Override
	public void snowball$setEmoting(boolean emoting) {
		snowball$emoting = emoting;
	}
}
