package dev.snowballclient.client.module.qol;

import dev.snowballclient.client.module.HoldableModule;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.ChoiceSetting;
import net.minecraft.client.MinecraftClient;

import java.util.List;

/** Hold to switch to a third-person view, then return to the previous camera on release. */
public final class Snaplook extends Module implements HoldableModule {
	private static final int KEY_V = 86;
	// Minecraft 1.8.9 stores the camera as 0 = first person, 1 = behind, 2 = in front.
	private static final int FIRST_PERSON = 0;
	private static final int BEHIND = 1;
	private static final int IN_FRONT = 2;

	public final ChoiceSetting view = setting(new ChoiceSetting("view", "View", "Look at yourself from the front, or from behind", "front", List.of("front", "back")));

	private boolean held;
	private int previous = -1;

	public Snaplook() {
		super("snaplook", "Snaplook", "Hold to see yourself", ModuleCategory.QOL);
		setKeybind(KEY_V);
	}

	@Override
	public void setHeld(boolean down) {
		if (down == held) return;
		MinecraftClient client = MinecraftClient.getInstance();
		if (down) {
			if (client.player == null) return;
			previous = client.options.perspective;
			client.options.perspective = "front".equals(view.get()) ? IN_FRONT : BEHIND;
		} else if (previous >= 0) {
			client.options.perspective = previous;
			previous = -1;
		}
		held = down;
	}

	@Override
	public boolean isHeld() {
		return held;
	}

	@Override
	protected void onDisable() {
		setHeld(false);
	}

	/** True while the player is looking at themselves. */
	public boolean isActive() {
		return held && MinecraftClient.getInstance().options.perspective != FIRST_PERSON;
	}
}
