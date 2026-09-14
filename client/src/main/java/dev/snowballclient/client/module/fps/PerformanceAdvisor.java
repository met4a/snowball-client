package dev.snowballclient.client.module.fps;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.perf.PerformanceCompat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Reports active optimisation mods and known conflicts in chat once per game session. */
public final class PerformanceAdvisor extends Module {
	public final BooleanSetting showTips = setting(new BooleanSetting("tips", "Show tips", "Suggest missing optimisation mods", false));
	private boolean reported;

	public PerformanceAdvisor() {
		super("performance_advisor", "Performance Advisor", "Warns about mod conflicts", ModuleCategory.FPS_BOOST, true);
	}

	@Override
	public void onTick() {
		Minecraft mc = Minecraft.getInstance();
		if (reported || mc.level == null || mc.player == null) return;
		reported = true;
		Set<String> ids = FabricLoader.getInstance().getAllMods().stream().map(m -> m.getMetadata().getId()).collect(Collectors.toSet());
		List<String> active = PerformanceCompat.KNOWN.stream().filter(k -> ids.contains(k[0])).map(k -> k[1]).toList();
		var chat = mc.gui.hud.getChat();
		if (!active.isEmpty()) chat.addClientSystemMessage(prefix().append(Component.literal("Optimisation mods: " + String.join(", ", active)).withStyle(ChatFormatting.GRAY)));
		for (PerformanceCompat.Finding f : PerformanceCompat.analyze(ids)) {
			if (f.severity() == PerformanceCompat.Severity.TIP && !showTips.isOn()) continue;
			ChatFormatting color = switch (f.severity()) {
				case ERROR -> ChatFormatting.RED;
				case WARNING -> ChatFormatting.YELLOW;
				case TIP -> ChatFormatting.GRAY;
			};
			String tag = f.severity() == PerformanceCompat.Severity.TIP ? "Tip: " : "Warning: ";
			chat.addClientSystemMessage(prefix().append(Component.literal(tag + f.message()).withStyle(color)));
		}
	}

	private static net.minecraft.network.chat.MutableComponent prefix() {
		return Component.literal("[Snowball] ").withStyle(ChatFormatting.WHITE, ChatFormatting.BOLD).append(Component.literal("").withStyle(ChatFormatting.RESET));
	}
}
