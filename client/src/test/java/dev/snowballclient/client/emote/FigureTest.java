package dev.snowballclient.client.emote;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class FigureTest {
	@Test
	void theFaceIsReadFromWhereSkinsKeepIt() {
		Figure.Box head = Figure.boxes(null).get(0);
		float[] st = new float[16];
		// Face 4 looks towards the viewer: the 8x8 square at (8, 8).
		assertArrayEquals(new float[]{8, 8, 16, 16}, Figure.uv(head, 4, st));
		assertEquals(8f, st[0]);
		assertEquals(8f, st[1]);
		assertEquals(16f, st[7 * 2]);
		assertEquals(16f, st[7 * 2 + 1]);
		// The top of the head sits above the face.
		assertArrayEquals(new float[]{8, 0, 16, 8}, Figure.uv(head, 2, st));
	}

	@Test
	void oldSkinsMirrorTheRightLimbsForTheLeft() {
		int[] pixels = new int[64 * 32];
		Arrays.fill(pixels, 0xFF808080);
		Figure.Box leftArm = Figure.boxes(new Skin(64, 32, pixels, false)).get(3);
		assertTrue(leftArm.mirror());
		assertEquals(40, leftArm.u());
		assertEquals(16, leftArm.v());
	}

	@Test
	void slimArmsAreThreePixelsWide() {
		Skin slim = new Skin(64, 64, new int[64 * 64], true);
		assertEquals(3f, Figure.boxes(slim).get(2).w());
		assertEquals(-2f, Figure.boxes(slim).get(2).x(), "the right arm keeps its inner edge by the body");
	}

	@Test
	void aFigureIsDrawnWithAndWithoutASkin() {
		Pose pose = new Pose();
		Emote.TPOSE.sample(1.5f, pose);
		int[] plain = Figure.draw(pose, 64, null);
		int[] pixels = new int[64 * 64];
		Arrays.fill(pixels, 0xFF3366CC);
		int[] worn = Figure.draw(pose, 64, new Skin(64, 64, pixels, false));
		assertEquals(64 * 64, plain.length);
		long solid = Arrays.stream(worn).filter(p -> (p >>> 24) == 0xFF).count();
		assertTrue(solid > 300, "the figure covers part of the picture");
		assertEquals(0, plain[0], "the corners are left clear");
		// The skin's blue shows through the shading.
		int middle = worn[32 * 64 + 32];
		assertTrue((middle & 0xFF) > ((middle >> 16) & 0xFF));
	}

	@Test
	void theSkinAddressComesFromTheProfile() {
		String textures = "{\"textures\":{\"SKIN\":{\"url\":\"http://textures.minecraft.net/texture/abc123\",\"metadata\":{\"model\":\"slim\"}}}}";
		String profile = "{\"id\":\"x\",\"properties\":[{\"name\":\"textures\",\"value\":\""
				+ Base64.getEncoder().encodeToString(textures.getBytes(StandardCharsets.UTF_8)) + "\"}]}";
		SelfSkin.Source source = SelfSkin.parseProfile(profile);
		assertEquals("https://textures.minecraft.net/texture/abc123", source.url());
		assertTrue(source.slim());

		String elsewhere = textures.replace("textures.minecraft.net", "example.com");
		assertNull(SelfSkin.parseProfile("{\"properties\":[{\"name\":\"textures\",\"value\":\""
				+ Base64.getEncoder().encodeToString(elsewhere.getBytes(StandardCharsets.UTF_8)) + "\"}]}"), "only Mojang's texture server");
		assertNull(SelfSkin.parseProfile("{\"id\":\"x\",\"properties\":[]}"));
		assertNull(SelfSkin.parseProfile("nonsense"));
	}
}
