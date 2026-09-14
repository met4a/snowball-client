package dev.snowballclient.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.GuiDraw;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Util;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Screenshot gallery: thumbnails decoded off-thread, click to open, right-click twice to delete. */
public final class ScreenshotsScreen extends PanelScreen {
	private static final int GAP = 6;
	private static final int THUMB_MAX = 192;
	private static final ExecutorService DECODER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Snowball Client screenshot thumbnails");
		t.setDaemon(true);
		return t;
	});

	private record Thumb(Identifier id, int width, int height) {
	}

	private final Path directory;
	private final List<Path> files = new ArrayList<>();
	private final Map<Path, Thumb> thumbs = new HashMap<>();
	private final Map<Path, CompletableFuture<NativeImage>> pending = new HashMap<>();
	private int columns;
	private int cellW;
	private int cellH;
	private int scrollRow;
	private Path pendingDelete;

	public ScreenshotsScreen(Screen parent, SnowballClient client) {
		super(Component.literal("SCREENSHOTS"), parent, client, 460);
		this.directory = client.gameDirectory().resolve("screenshots");
		reload();
	}

	private void reload() {
		files.clear();
		if (!Files.isDirectory(directory)) return;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.png")) {
			for (Path p : stream) files.add(p);
		} catch (IOException e) {
			SnowballClient.LOGGER.warn("Could not list screenshots", e);
		}
		files.sort((a, b) -> Long.compare(lastModified(b), lastModified(a)));
	}

	private static long lastModified(Path p) {
		try {
			return Files.getLastModifiedTime(p).toMillis();
		} catch (IOException e) {
			return 0;
		}
	}

	@Override
	protected int desiredHeight() {
		return (int) (height * 0.8f);
	}

	@Override
	protected void initContent() {
		columns = Math.max(2, (panelW - 20) / 110);
		cellW = (panelW - 20 - GAP * (columns - 1)) / columns;
		cellH = cellW * 9 / 16 + 12;
	}

	private int gridTop() {
		return panelY + HEADER_H + 6;
	}

	private int visibleGridRows() {
		return Math.max(1, (panelY + panelH - 30 - gridTop()) / (cellH + GAP));
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		UiText.drawRight(g, font, files.size() + " files", UiText.UI_SMALL, panelX + panelW - 12, panelY + 11, theme.mutedText());
		if (files.isEmpty()) {
			UiText.draw(g, font, "No screenshots yet. Press F2 in game to take one.", UiText.UI, panelX + 12, gridTop() + 6, theme.mutedText());
		}
		int rows = visibleGridRows();
		scrollRow = Math.max(0, Math.min(scrollRow, (files.size() + columns - 1) / columns - rows));
		int started = 0;
		for (int i = scrollRow * columns; i < Math.min(files.size(), (scrollRow + rows) * columns); i++) {
			Path file = files.get(i);
			int col = i % columns;
			int x = panelX + 10 + col * (cellW + GAP);
			int y = gridTop() + (i / columns - scrollRow) * (cellH + GAP);
			boolean hover = inside(x, y, cellW, cellH, mouseX, mouseY);
			GuiDraw.roundedRect(g, x, y, cellW, cellH, 4, Theme.lerpColor(theme.surface(1f), theme.highlight(), hover ? 0.1f : 0.04f));
			int imgH = cellH - 12;
			Thumb thumb = thumbs.get(file);
			if (thumb == null) {
				// Start at most two decodes per frame so opening the gallery never stalls.
				if (started < 2 && !pending.containsKey(file)) {
					pending.put(file, CompletableFuture.supplyAsync(() -> decode(file), DECODER));
					started++;
				}
				CompletableFuture<NativeImage> job = pending.get(file);
				if (job != null && job.isDone()) {
					NativeImage image = job.getNow(null);
					pending.remove(file);
					thumb = image == null ? new Thumb(null, 0, 0) : upload(file, image);
					thumbs.put(file, thumb);
				}
			}
			if (thumb != null && thumb.id() != null) {
				float fit = Math.min((cellW - 4f) / thumb.width(), (imgH - 4f) / thumb.height());
				int w = Math.round(thumb.width() * fit);
				int h = Math.round(thumb.height() * fit);
				g.blit(RenderPipelines.GUI_TEXTURED, thumb.id(), x + (cellW - w) / 2, y + 2 + (imgH - 4 - h) / 2, 0f, 0f, w, h, w, h, 0xFFFFFFFF);
			}
			String label = file.equals(pendingDelete) ? "Right-click again to delete" : file.getFileName().toString();
			UiText.draw(g, font, UiText.fit(font, label, UiText.UI_SMALL, cellW - 6), UiText.UI_SMALL, x + 3, y + cellH - 10, file.equals(pendingDelete) ? 0xFFFF6B6B : theme.mutedText());
			if (hover) GuiDraw.roundedOutline(g, x, y, cellW, cellH, 4, theme.accent());
		}
		button(g, panelX + 10, panelY + panelH - 24, 96, 16, "OPEN FOLDER", true, mouseX, mouseY);
		button(g, panelX + 112, panelY + panelH - 24, 64, 16, "REFRESH", false, mouseX, mouseY);
	}

	private static NativeImage decode(Path file) {
		try (InputStream in = Files.newInputStream(file); NativeImage full = NativeImage.read(in)) {
			int step = Math.max(1, Math.max(full.getWidth(), full.getHeight()) / THUMB_MAX);
			int w = Math.max(1, full.getWidth() / step);
			int h = Math.max(1, full.getHeight() / step);
			NativeImage small = new NativeImage(w, h, false);
			for (int y = 0; y < h; y++) {
				for (int x = 0; x < w; x++) small.setPixel(x, y, full.getPixel(x * step, y * step));
			}
			return small;
		} catch (IOException | RuntimeException e) {
			SnowballClient.LOGGER.warn("Could not read screenshot {}", file.getFileName(), e);
			return null;
		}
	}

	private Thumb upload(Path file, NativeImage image) {
		Identifier id = Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, "dynamic/screenshot_" + Integer.toHexString(file.toString().hashCode()));
		minecraft.getTextureManager().register(id, new DynamicTexture(id::toString, image));
		return new Thumb(id, image.getWidth(), image.getHeight());
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mx = event.x();
		double my = event.y();
		if (inside(panelX + 10, panelY + panelH - 24, 96, 16, mx, my)) {
			try {
				Files.createDirectories(directory);
				Util.getPlatform().openPath(directory);
			} catch (IOException ignored) {
				// Folder could not be created; nothing to open.
			}
			return true;
		}
		if (inside(panelX + 112, panelY + panelH - 24, 64, 16, mx, my)) {
			releaseTextures();
			reload();
			return true;
		}
		int rows = visibleGridRows();
		for (int i = scrollRow * columns; i < Math.min(files.size(), (scrollRow + rows) * columns); i++) {
			int x = panelX + 10 + (i % columns) * (cellW + GAP);
			int y = gridTop() + (i / columns - scrollRow) * (cellH + GAP);
			if (!inside(x, y, cellW, cellH, mx, my)) continue;
			Path file = files.get(i);
			if (event.buttonInfo().button() == 1) {
				if (file.equals(pendingDelete)) {
					try {
						Files.deleteIfExists(file);
					} catch (IOException e) {
						SnowballClient.LOGGER.warn("Could not delete {}", file.getFileName(), e);
					}
					pendingDelete = null;
					releaseTextures();
					reload();
				} else {
					pendingDelete = file;
				}
			} else {
				pendingDelete = null;
				Util.getPlatform().openPath(file);
			}
			return true;
		}
		pendingDelete = null;
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
		if (scrollY != 0) {
			scrollRow = Math.max(0, scrollRow - (int) Math.signum(scrollY));
			return true;
		}
		return super.mouseScrolled(x, y, scrollX, scrollY);
	}

	private void releaseTextures() {
		for (Thumb t : thumbs.values()) if (t.id() != null) minecraft.getTextureManager().release(t.id());
		thumbs.clear();
		for (CompletableFuture<NativeImage> job : pending.values()) {
			job.thenAccept(image -> {
				if (image != null) image.close();
			});
		}
		pending.clear();
	}

	@Override
	public void removed() {
		releaseTextures();
		super.removed();
	}
}
