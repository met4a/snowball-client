package dev.snowballclient.client.emote;

/**
 * Added to Minecraft's player render state: the emote pose worked out while the state was filled,
 * read again when the model is posed. One pose object per state, reused frame to frame.
 */
public interface EmoteHolder {
	Pose snowball$pose();

	boolean snowball$emoting();

	void snowball$setEmoting(boolean emoting);
}
