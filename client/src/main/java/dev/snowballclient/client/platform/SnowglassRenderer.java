package dev.snowballclient.client.platform;

import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuSampler;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.gui.snowglass.GlassPanel;
import dev.snowballclient.client.gui.snowglass.GlassTuning;
import dev.snowballclient.client.gui.snowglass.MaterialLevel;
import dev.snowballclient.client.gui.theme.Theme;
import dev.snowballclient.client.hud.NotificationCenter;
import dev.snowballclient.client.mixin.GuiStateAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.resources.Identifier;
//? if >=26.1 {
import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.BindGroupLayout;
import com.mojang.blaze3d.pipeline.ColorTargetState;
import net.minecraft.client.renderer.BindGroupLayouts;
import java.util.Optional;
//?} else {
/*import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.textures.TextureFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.RenderPipelines;
import java.util.OptionalInt;
*///?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Real Snowglass on the modern versions. Once a frame, just before the interface is drawn, the world
 * in the main colour target is shrunk into a small blur pyramid (dual-filter Kawase: a half, a
 * quarter and an eighth of the screen, and for Full a quarter blown back up from the eighth). Every
 * glass panel that frame is one quad of the snowball glass pipeline sampling that pyramid, so all of
 * them batch into one draw and nothing is ever blurred per panel. Frames with no glass on screen skip
 * all of it.
 *
 * <p>Everything goes through Blaze3D (pipelines, render passes, textures), so it runs the same on
 * OpenGL and, on 26.2, on Vulkan. If the shaders cannot be used, glass is drawn solid (the Off
 * material) for the rest of the session; while an Iris shader pack is in use, Full drops to Lite.
 * Render thread only.
 */
public final class SnowglassRenderer {
	private static final Logger LOGGER = LoggerFactory.getLogger("SnowballClient/Snowglass");
	private static final int TEXTURE_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING;

	// Flags packed with the base tint into the second short of UV2; snowglass.fsh unpacks them.
	static final int FLAG_FULL = 1 << 8;
	static final int FLAG_SHADOW = 1 << 9;
	static final int FLAG_FOCUSED = 1 << 10;
	static final int FLAG_CLEAR_TEXT = 1 << 11;

	private static final RenderPipeline DOWN = blurPipeline("snowglass_down");
	private static final RenderPipeline UP = blurPipeline("snowglass_up");
	private static RenderPipeline glass;
	private static int glassRevision = Integer.MIN_VALUE;

	private static boolean failed;
	/** Whether the pipelines were checked this frame. */
	private static boolean checked;
	private static boolean wantsGlass;
	private static boolean wantsFull;

	private static GpuTexture half;
	private static GpuTexture quarter;
	private static GpuTexture eighth;
	private static GpuTexture quarterUp;
	private static GpuTextureView halfView;
	private static GpuTextureView quarterView;
	private static GpuTextureView eighthView;
	private static GpuTextureView quarterUpView;
	private static int textureWidth;
	private static int textureHeight;
	private static TextureSetup liteTextures;
	private static TextureSetup fullTextures;

	/** Glass panels are reused from frame to frame rather than made anew. */
	private static final List<GlassElement> POOL = new ArrayList<>();
	private static int used;

	private static boolean irisLooked;
	private static Object irisApi;
	private static Method irisShaderPackInUse;
	/** Whether an Iris shader pack is in use, asked at most once a frame. */
	private static Boolean irisThisFrame;

	private SnowglassRenderer() {
	}

	/** Whether glass of this quality is drawn as real glass right now, rather than solid. */
	public static boolean accepts(String quality) {
		if (failed || Theme.GLASS_OFF.equals(quality)) return false;
		if (!checked) {
			checked = true;
			if (!pipelinesValid()) disable("the glass shaders could not be compiled");
		}
		return !failed;
	}

	/** Adds a glass panel to what the interface draws this frame. Call only when {@link #accepts} says so. */
	public static void submit(GuiGraphicsExtractor graphics, GlassPanel panel) {
		if (!ensureTextures()) return;
		boolean full = Theme.GLASS_FULL.equals(panel.quality()) && !shaderPackInUse();
		MaterialLevel material = GlassTuning.material(panel.glassLevel());
		int baseTint = Math.round(Math.max(0f, Math.min(1f, material.tint())) * 255f);
		int flags = baseTint | (full ? FLAG_FULL : 0) | (panel.shadow() ? FLAG_SHADOW : 0) | (panel.focused() ? FLAG_FOCUSED : 0)
				| (panel.glassLevel() < MaterialLevel.REGULAR ? FLAG_CLEAR_TEXT : 0);
		GlassElement element = next();
		element.set(graphics.pose(), panel, material, flags, full ? fullTextures : liteTextures);
		//? if >=26.1 {
		((GuiStateAccessor) graphics).snowball$guiRenderState().addGuiElement(element);
		//?} else {
		/*((GuiStateAccessor) graphics).snowball$guiRenderState().submitGuiElement(element);
		*///?}
		wantsGlass = true;
		wantsFull |= full;
	}

	/** The pipeline glass panels are drawn with, rebuilt when the Glass Lab changes a compiled-in value. */
	static RenderPipeline glassPipeline() {
		if (glass == null || glassRevision != GlassTuning.shaderRevision()) {
			glassRevision = GlassTuning.shaderRevision();
			glass = buildGlassPipeline();
		}
		return glass;
	}

	/**
	 * Called as the interface is about to be drawn (the point in the frame where Minecraft applies its
	 * own menu blur): turns the finished world into the blur pyramid the frame's glass samples.
	 */
	public static void beforeInterfaceDrawn() {
		if (!wantsGlass || failed || halfView == null) return;
		RenderTarget main = mainTarget();
		if (main.width != textureWidth || main.height != textureHeight) return;
		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		blur(encoder, DOWN, main.getColorTextureView(), halfView);
		blur(encoder, DOWN, halfView, quarterView);
		blur(encoder, DOWN, quarterView, eighthView);
		if (wantsFull) blur(encoder, UP, eighthView, quarterUpView);
	}

	/** Called once the interface is drawn: this frame's glass panels can be reused. */
	public static void afterInterfaceDrawn() {
		used = 0;
		wantsGlass = false;
		wantsFull = false;
		checked = false;
		irisThisFrame = null;
	}

	/** Lets go of the pyramid's textures (the game is closing). */
	public static void close() {
		closeTextures();
	}

	// ---- pyramid ----

	private static void blur(CommandEncoder encoder, RenderPipeline pipeline, GpuTextureView from, GpuTextureView to) {
		//? if >=26.1 {
		try (RenderPass pass = encoder.createRenderPass(() -> "Snowglass blur", to, Optional.empty())) {
		//?} else {
		/*try (RenderPass pass = encoder.createRenderPass(() -> "Snowglass blur", to, OptionalInt.empty())) {
		*///?}
			pass.setPipeline(pipeline);
			pass.bindTexture("InSampler", from, sampler());
			//? if >=26.1 {
			pass.draw(3, 1, 0, 0);
			//?} else {
			/*pass.draw(0, 3);
			*///?}
		}
	}

	/** Makes the pyramid's textures for the current window size; false when they cannot be made. */
	private static boolean ensureTextures() {
		RenderTarget main = mainTarget();
		if (halfView != null && main.width == textureWidth && main.height == textureHeight) return true;
		closeTextures();
		try {
			GpuDevice device = RenderSystem.getDevice();
			int w = main.width;
			int h = main.height;
			half = texture(device, "Snowglass half", Math.max(1, w / 2), Math.max(1, h / 2));
			quarter = texture(device, "Snowglass quarter", Math.max(1, w / 4), Math.max(1, h / 4));
			eighth = texture(device, "Snowglass eighth", Math.max(1, w / 8), Math.max(1, h / 8));
			quarterUp = texture(device, "Snowglass quarter up", Math.max(1, w / 4), Math.max(1, h / 4));
			halfView = device.createTextureView(half);
			quarterView = device.createTextureView(quarter);
			eighthView = device.createTextureView(eighth);
			quarterUpView = device.createTextureView(quarterUp);
			GpuSampler linear = sampler();
			liteTextures = new TextureSetup(halfView, quarterView, eighthView, linear, linear, linear);
			fullTextures = new TextureSetup(halfView, quarterUpView, eighthView, linear, linear, linear);
			textureWidth = w;
			textureHeight = h;
			return true;
		} catch (RuntimeException e) {
			LOGGER.error("Snowglass could not make its blur textures", e);
			closeTextures();
			disable("its blur textures could not be made");
			return false;
		}
	}

	private static GpuTexture texture(GpuDevice device, String label, int width, int height) {
		//? if >=26.1 {
		return device.createTexture(label, TEXTURE_USAGE, GpuFormat.RGBA8_UNORM, width, height, 1, 1);
		//?} else {
		/*return device.createTexture(label, TEXTURE_USAGE, TextureFormat.RGBA8, width, height, 1, 1);
		*///?}
	}

	private static void closeTextures() {
		for (GpuTextureView view : new GpuTextureView[]{halfView, quarterView, eighthView, quarterUpView}) {
			if (view != null) view.close();
		}
		for (GpuTexture texture : new GpuTexture[]{half, quarter, eighth, quarterUp}) {
			if (texture != null) texture.close();
		}
		half = quarter = eighth = quarterUp = null;
		halfView = quarterView = eighthView = quarterUpView = null;
		liteTextures = fullTextures = null;
		textureWidth = textureHeight = 0;
	}

	private static GpuSampler sampler() {
		return RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}

	private static RenderTarget mainTarget() {
		//? if >=26.1 {
		return Minecraft.getInstance().gameRenderer.mainRenderTarget();
		//?} else {
		/*return Minecraft.getInstance().getMainRenderTarget();
		*///?}
	}

	// ---- pipelines ----

	private static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, path);
	}

	/** A full-screen pass of the pyramid, drawn with vanilla's full-screen vertex shader. */
	private static RenderPipeline blurPipeline(String name) {
		//? if >=26.1 {
		return RenderPipeline.builder()
				.withLocation(id("pipeline/" + name))
				.withVertexShader("core/screenquad")
				.withFragmentShader(id("core/" + name))
				.withBindGroupLayout(BindGroupLayout.builder().withSampler("InSampler").build())
				.withPrimitiveTopology(PrimitiveTopology.TRIANGLES)
				.build();
		//?} else {
		/*return RenderPipeline.builder(RenderPipelines.POST_PROCESSING_SNIPPET)
				.withLocation(id("pipeline/" + name))
				.withVertexShader("core/screenquad")
				.withFragmentShader(id("core/" + name))
				.withSampler("InSampler")
				.build();
		*///?}
	}

	/** The glass pipeline: vanilla's GUI set-up (transforms, translucent blend) with the entity vertex layout and three samplers. */
	private static RenderPipeline buildGlassPipeline() {
		RenderPipeline.Builder builder = RenderPipeline.builder()
				.withLocation(id("pipeline/snowglass"))
				.withVertexShader(id("core/snowglass"))
				.withFragmentShader(id("core/snowglass"))
				.withShaderDefine("LENS_BEZEL", GlassTuning.lensBezel)
				.withShaderDefine("LENS_STRENGTH", GlassTuning.lensStrength)
				.withShaderDefine("DISPERSION", GlassTuning.dispersion)
				.withShaderDefine("GRAIN", GlassTuning.grain)
				.withShaderDefine("SHADOW", GlassTuning.shadow);
		//? if >=26.1 {
		builder = builder.withBindGroupLayout(BindGroupLayouts.MATRICES_PROJECTION)
				.withBindGroupLayout(BindGroupLayouts.SAMPLER0_SAMPLER1_SAMPLER2)
				.withColorTargetState(new ColorTargetState(BlendFunction.TRANSLUCENT))
				.withVertexBinding(0, DefaultVertexFormat.ENTITY)
				.withPrimitiveTopology(PrimitiveTopology.QUADS);
		//?} else {
		/*builder = builder.withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
				.withUniform("Projection", UniformType.UNIFORM_BUFFER)
				.withSampler("Sampler0")
				.withSampler("Sampler1")
				.withSampler("Sampler2")
				.withBlend(BlendFunction.TRANSLUCENT)
				.withVertexFormat(DefaultVertexFormat.NEW_ENTITY, VertexFormat.Mode.QUADS)
				.withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST);
		*///?}
		return builder.build();
	}

	private static boolean pipelinesValid() {
		// Game tests prove the fallback works by pretending the shaders failed: -PglassFail.
		if (Boolean.getBoolean("snowball.glass.failForTest")) return false;
		try {
			GpuDevice device = RenderSystem.getDevice();
			return device.precompilePipeline(glassPipeline()).isValid()
					&& device.precompilePipeline(DOWN).isValid()
					&& device.precompilePipeline(UP).isValid();
		} catch (RuntimeException e) {
			LOGGER.error("Snowglass could not compile its shaders", e);
			return false;
		}
	}

	/** Glass is drawn solid for the rest of the session; said once, in the log and on screen. */
	private static void disable(String why) {
		if (failed) return;
		failed = true;
		closeTextures();
		LOGGER.warn("Snowglass is drawing solid panels instead of glass: {}", why);
		NotificationCenter.post("Snowglass", "Glass is off: " + why);
	}

	// ---- panels ----

	private static GlassElement next() {
		if (used == POOL.size()) POOL.add(new GlassElement());
		return POOL.get(used++);
	}

	/** Whether an Iris shader pack is in use, through Iris's public API when Iris is installed. */
	private static boolean shaderPackInUse() {
		if (irisThisFrame != null) return irisThisFrame;
		if (!irisLooked) {
			irisLooked = true;
			if (FabricLoader.getInstance().isModLoaded("iris")) {
				try {
					Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
					irisApi = api.getMethod("getInstance").invoke(null);
					irisShaderPackInUse = api.getMethod("isShaderPackInUse");
				} catch (ReflectiveOperationException | LinkageError e) {
					LOGGER.warn("Iris is installed but its API was not found; Snowglass will not adapt to shader packs ({})", e.toString());
				}
			}
		}
		boolean inUse = false;
		if (irisApi != null) {
			try {
				inUse = Boolean.TRUE.equals(irisShaderPackInUse.invoke(irisApi));
			} catch (ReflectiveOperationException | RuntimeException e) {
				irisApi = null;
			}
		}
		irisThisFrame = inUse;
		return inUse;
	}
}
