package dev.snowballclient.client.module.storage;

import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.module.ModuleCategory;
import dev.snowballclient.client.platform.ViewScreen;
import dev.snowballclient.client.ui.Host;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.packs.PackSelectionScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Resource pack and shader pack shortcuts in the STORAGE category. */
public final class PackModules {
	private PackModules() {
	}

	public static final class ResourcePacks extends ActionModule {
		public ResourcePacks() {
			super("resource_packs", "Resource Packs", "Open resource packs", ModuleCategory.STORAGE);
		}

		@Override
		public void openSettings(Host host) {
			// On this version every view is shown by ViewScreen, which can open Minecraft's own screens.
			((ViewScreen) host).openScreen(parent -> {
				Minecraft mc = Minecraft.getInstance();
				return new PackSelectionScreen(mc.getResourcePackRepository(), repository -> {
					mc.options.updateResourcePacks(repository);
					mc.gui.setScreen(parent);
				}, mc.getResourcePackDirectory(), Component.translatable("resourcePack.title"));
			});
		}
	}

	public static final class ShaderPacks extends ActionModule {
		public ShaderPacks() {
			super("shader_packs", "Shader Packs", "Open shader settings", ModuleCategory.STORAGE);
		}

		@Override
		public String statusText() {
			return FabricLoader.getInstance().isModLoaded("iris") ? null : "NO IRIS";
		}

		@Override
		public void openSettings(Host host) {
			((ViewScreen) host).openScreen(this::shaderScreen);
		}

		private Screen shaderScreen(Screen parent) {
			if (FabricLoader.getInstance().isModLoaded("iris")) {
				try {
					// Iris' public API; accessed reflectively so Iris stays an optional dependency.
					Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
					Object instance = api.getMethod("getInstance").invoke(null);
					Object screen = api.getMethod("openMainIrisScreenObj", Object.class).invoke(instance, parent);
					if (screen instanceof Screen s) return s;
				} catch (ReflectiveOperationException | RuntimeException e) {
					SnowballClient.LOGGER.warn("Could not open the Iris shader screen; opening the folder instead", e);
				}
			}
			Path dir = Minecraft.getInstance().gameDirectory.toPath().resolve("shaderpacks");
			try {
				Files.createDirectories(dir);
				Util.getPlatform().openPath(dir);
				NotificationCenter.post("Shader Packs", FabricLoader.getInstance().isModLoaded("iris") ? "Opened shaderpacks folder" : "Install Iris to use shaders");
			} catch (IOException e) {
				NotificationCenter.post("Shader Packs", "Could not open folder");
			}
			return null;
		}
	}
}
