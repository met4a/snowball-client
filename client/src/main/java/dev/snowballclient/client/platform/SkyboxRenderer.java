package dev.snowballclient.client.platform;

//? if >=26.1 {
import com.mojang.blaze3d.PrimitiveTopology;
//?}
import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.snowballclient.client.SnowballClient;
import dev.snowballclient.client.module.ModuleRegistry;
import dev.snowballclient.client.module.render.CustomSky;
import dev.snowballclient.client.sky.SkyPainter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.resources.Identifier;
import org.joml.Matrix4f;
import org.joml.Vector4f;
//? if <26.1 {
/*import org.joml.Vector3f;
*///?}

import java.util.Optional;
import java.util.OptionalDouble;
//? if <26.1 {
/*import java.util.OptionalInt;
*///?}

/**
 * Draws Custom Sky's painted sky box on this version, the way the game draws the End's sky: a cube
 * around the camera with the six painted faces, in the sky pass, right after the sky colour.
 */
public final class SkyboxRenderer {
	private static final String TEXTURE = "dynamic/custom_sky";
	private static final Identifier ID = Identifier.fromNamespaceAndPath(SnowballClient.MOD_ID, TEXTURE);
	private static final float SIZE = 100f;
	/** Each face from both sides, so it shows whichever way the pipeline culls. */
	private static final int QUADS = SkyPainter.FACES * 2;

	private static GpuBuffer cube;
	private static boolean uploaded;

	private SkyboxRenderer() {
	}

	/** Whether the painted sky replaces the vanilla one right now. */
	public static boolean active() {
		CustomSky sky = ModuleRegistry.CUSTOM_SKY;
		return sky != null && sky.isEnabled();
	}

	/** Whether the sun, moon and vanilla stars are hidden under the painted sky. */
	public static boolean hidesCelestials() {
		return active() && !ModuleRegistry.CUSTOM_SKY.sunAndMoon.isOn();
	}

	/** In the sky pass, after the sky colour: the painted sky over it, into these targets. */
	public static void draw(GpuTextureView color, GpuTextureView depth) {
		CustomSky sky = ModuleRegistry.CUSTOM_SKY;
		if (sky == null || !sky.isEnabled()) return;
		SkyPainter.Painted next = sky.takePainted();
		if (next != null) {
			MinecraftTextures.INSTANCE.upload(TEXTURE, next.width(), next.height(), next.argb());
			uploaded = true;
		}
		if (!uploaded) return;
		Minecraft mc = Minecraft.getInstance();
		AbstractTexture texture = mc.getTextureManager().getTexture(ID);
		if (texture == null || texture.getTextureView() == null) return;
		if (cube == null) cube = buildCube();
		float rain = mc.level == null ? 0f : mc.level.getRainLevel(1f);
		float b = sky.brightness(rain);
		Vector4f tint = new Vector4f(b, b, b, 1f);
		//? if >=26.1 {
		Matrix4f view = RenderSystem.getModelViewMatrixCopy().rotateY((float) Math.toRadians(sky.turn(System.currentTimeMillis())));
		GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(view, tint);
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS);
		//?} else {
		/*Matrix4f view = new Matrix4f(RenderSystem.getModelViewMatrix()).rotateY((float) Math.toRadians(sky.turn(System.currentTimeMillis())));
		GpuBufferSlice transform = RenderSystem.getDynamicUniforms().writeTransform(view, tint, new Vector3f(), new Matrix4f());
		RenderSystem.AutoStorageIndexBuffer indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
		*///?}
		GpuBuffer indexBuffer = indices.getBuffer(QUADS * 6);
		//? if >=26.1 {
		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Snowball custom sky", color, Optional.empty(), depth, OptionalDouble.empty())) {
		//?} else {
		/*try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Snowball custom sky", color, OptionalInt.empty(), depth, OptionalDouble.empty())) {
		*///?}
			pass.setPipeline(RenderPipelines.END_SKY);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", transform);
			// Smooth sampling: the faces are seen far larger than they are painted.
			pass.bindTexture("Sampler0", texture.getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
			pass.setIndexBuffer(indexBuffer, indices.type());
			//? if >=26.1 {
			pass.setVertexBuffer(0, cube.slice());
			pass.drawIndexed(QUADS * 6, 1, 0, 0, 0);
			//?} else {
			/*pass.setVertexBuffer(0, cube);
			pass.drawIndexed(0, 0, QUADS * 6, 1);
			*///?}
		}
	}

	/** The six faces, each corner where the painter says that face looks, mapped to its square of the image. */
	private static GpuBuffer buildCube() {
		VertexFormat format = DefaultVertexFormat.POSITION_TEX_COLOR;
		try (ByteBufferBuilder bytes = ByteBufferBuilder.exactlySized(QUADS * 4 * format.getVertexSize())) {
			//? if >=26.1 {
			BufferBuilder builder = new BufferBuilder(bytes, PrimitiveTopology.QUADS, format);
			//?} else {
			/*BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, format);
			*///?}
			// Half a pixel in from each face's edge, so smooth sampling never reaches the next face.
			float inset = 0.5f;
			double[][] corners = {{-1, -1}, {-1, 1}, {1, 1}, {1, -1}};
			for (int face = 0; face < SkyPainter.FACES; face++) {
				float u0 = (SkyPainter.faceX(face) + inset) / SkyPainter.WIDTH;
				float u1 = (SkyPainter.faceX(face) + SkyPainter.FACE - inset) / SkyPainter.WIDTH;
				float v0 = (SkyPainter.faceY(face) + inset) / SkyPainter.HEIGHT;
				float v1 = (SkyPainter.faceY(face) + SkyPainter.FACE - inset) / SkyPainter.HEIGHT;
				for (int side = 0; side < 2; side++) {
					for (int k = 0; k < 4; k++) {
						double[] c = corners[side == 0 ? k : 3 - k];
						double[] d = SkyPainter.direction(face, c[0], c[1]);
						builder.addVertex((float) d[0] * SIZE, (float) d[1] * SIZE, (float) d[2] * SIZE)
								.setUv(c[0] < 0 ? u0 : u1, c[1] < 0 ? v0 : v1)
								.setColor(0xFFFFFFFF);
					}
				}
			}
			try (MeshData mesh = builder.buildOrThrow()) {
				return RenderSystem.getDevice().createBuffer(() -> "Snowball custom sky", GpuBuffer.USAGE_VERTEX, mesh.vertexBuffer());
			}
		}
	}
}
