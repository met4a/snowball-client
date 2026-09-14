package dev.snowballclient.client.module.storage;

import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.mixin.AbstractContainerScreenAccessor;
import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.module.setting.BooleanSetting;
import dev.snowballclient.client.module.setting.NumberSetting;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/** Adds a search box above container screens and dims slots that do not match. */
public final class ContainerSearch extends Module {
	private static final int KEY_ESCAPE = 256;

	public final NumberSetting dim = setting(new NumberSetting("dim", "Dim strength", "How dark non-matching slots become", 0.7, 0.2, 0.95, 0.05));
	public final BooleanSetting remember = setting(new BooleanSetting("remember", "Remember search", "Keep the last search when reopening", false));
	private String lastQuery = "";

	public ContainerSearch() {
		super("container_search", "Container Search", "Find items in chests", ModuleCategory.STORAGE, true);
	}

	/** Registers the screen hook once at start-up; the hook checks the enabled state per screen. */
	public void install() {
		ScreenEvents.AFTER_INIT.register(this::onScreenInit);
	}

	private void onScreenInit(Minecraft client, Screen screen, int width, int height) {
		if (!isEnabled() || !(screen instanceof AbstractContainerScreen<?> container) || screen instanceof CreativeModeInventoryScreen) return;
		AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) container;
		int x = accessor.snowballclient$getLeftPos();
		int y = Math.max(2, accessor.snowballclient$getTopPos() - 16);

		SearchState state = new SearchState();
		EditBox box = new EditBox(client.font, x, y, 110, 14, Component.translatable("snowballclient.container_search.hint"));
		box.setHint(Component.translatable("snowballclient.container_search.hint").withStyle(ChatFormatting.DARK_GRAY));
		box.setMaxLength(48);
		if (remember.isOn() && !lastQuery.isEmpty()) {
			box.setValue(lastQuery);
			state.setQuery(lastQuery);
		}
		box.setResponder(query -> {
			state.setQuery(query);
			lastQuery = query;
		});
		Screens.getWidgets(screen).add(box);

		// While typing, keys must not trigger inventory shortcuts (e.g. E closing the screen).
		ScreenKeyboardEvents.allowKeyPress(screen).register((s, event) -> {
			if (!box.isFocused()) return true;
			if (event.key() == KEY_ESCAPE) box.setFocused(false);
			else box.keyPressed(event);
			return false;
		});
		ScreenEvents.afterExtract(screen).register((s, graphics, mouseX, mouseY, tick) -> drawOverlay(container, accessor, state, graphics));
	}

	private void drawOverlay(AbstractContainerScreen<?> container, AbstractContainerScreenAccessor accessor, SearchState state, GuiGraphicsExtractor g) {
		if (state.query.isEmpty()) return;
		int left = accessor.snowballclient$getLeftPos();
		int top = accessor.snowballclient$getTopPos();
		int dimColor = Theme.withAlpha(0xFF000000, dim.floatValue());
		g.nextStratum();
		for (Slot slot : container.getMenu().slots) {
			if (!slot.isActive()) continue;
			int sx = left + slot.x;
			int sy = top + slot.y;
			if (state.matches(slot.getItem())) {
				g.fill(sx - 1, sy - 1, sx + 17, sy, 0xFFFFFFFF);
				g.fill(sx - 1, sy + 16, sx + 17, sy + 17, 0xFFFFFFFF);
				g.fill(sx - 1, sy, sx, sy + 16, 0xFFFFFFFF);
				g.fill(sx + 16, sy, sx + 17, sy + 16, 0xFFFFFFFF);
			} else {
				g.fill(sx, sy, sx + 16, sy + 16, dimColor);
			}
		}
	}

	static final class SearchState {
		String query = "";
		private final Map<ItemStack, Boolean> cache = new IdentityHashMap<>();

		void setQuery(String q) {
			query = q == null ? "" : q.trim().toLowerCase(Locale.ROOT);
			cache.clear();
		}

		boolean matches(ItemStack stack) {
			if (stack.isEmpty()) return false;
			Boolean cached = cache.get(stack);
			if (cached != null) return cached;
			if (cache.size() > 1024) cache.clear();
			boolean match = stack.getHoverName().getString().toLowerCase(Locale.ROOT).contains(query)
					|| BuiltInRegistries.ITEM.getKey(stack.getItem()).getPath().contains(query);
			cache.put(stack, match);
			return match;
		}
	}
}
