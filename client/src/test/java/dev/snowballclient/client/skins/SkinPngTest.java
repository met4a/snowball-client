package dev.snowballclient.client.skins;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** What counts as a skin, which arms it has, and how old skins are brought up to date. */
class SkinPngTest {
	@Test
	void theHeaderSaysWhetherItIsASkin() {
		assertEquals(64, SkinPng.skinHeight(SkinImages.skin(64, false)));
		assertEquals(32, SkinPng.skinHeight(SkinImages.skin(32, false)));
		assertTrue(SkinPng.isSkin(SkinImages.skin(64, true)));

		assertEquals(0, SkinPng.skinHeight(SkinImages.png(new int[64 * 48], 64, 48)), "64x48 is no skin size");
		assertEquals(0, SkinPng.skinHeight(SkinImages.png(new int[32 * 32], 32, 32)), "32x32 is no skin size");
		assertEquals(0, SkinPng.skinHeight(SkinImages.png(new int[128 * 128], 128, 128)), "HD skins are refused, as Mojang does");
		assertEquals(0, SkinPng.skinHeight(null));
		assertEquals(0, SkinPng.skinHeight(Arrays.copyOf(SkinImages.skin(64, false), 20)), "too short to hold a header");
		assertEquals(0, SkinPng.skinHeight("not a picture at all, just some words".getBytes()));

		byte[] noHeader = SkinImages.skin(64, false);
		noHeader[12] = 'X';
		assertEquals(0, SkinPng.skinHeight(noHeader), "the first chunk has to be IHDR");
		byte[] badSignature = SkinImages.skin(64, false);
		badSignature[1] = 'Q';
		assertEquals(0, SkinPng.skinHeight(badSignature));
	}

	@Test
	void filesOver64KiBAreRefusedEvenWithAGoodHeader() {
		byte[] skin = SkinImages.skin(64, false);
		byte[] big = Arrays.copyOf(skin, SkinPng.MAX_BYTES + 1);
		assertEquals(0, SkinPng.skinHeight(big));
		assertNull(SkinPng.decode(big));
		assertEquals(64, SkinPng.skinHeight(Arrays.copyOf(skin, SkinPng.MAX_BYTES)), "64 KiB itself is allowed");
	}

	@Test
	void decodesThePixelsAsTheyWereWritten() {
		int[] argb = SkinImages.pixels(64, true);
		argb[5] = 0x80123456;
		assertArrayEquals(argb, SkinPng.decode(SkinImages.png(argb, 64, 64)));
		assertEquals(64 * 32, SkinPng.decode(SkinImages.skin(32, false)).length);

		byte[] cut = SkinImages.skin(64, false);
		assertNull(SkinPng.decode(Arrays.copyOf(cut, cut.length / 2)), "a half-written file is no skin");
	}

	@Test
	void slimWhenTheWideArmStripsAreSeeThrough() {
		assertTrue(SkinPng.slim(SkinImages.pixels(64, true), 64, 64));
		assertFalse(SkinPng.slim(SkinImages.pixels(64, false), 64, 64));

		int[] almost = SkinImages.pixels(64, true);
		almost[31 * 64 + 55] = 0x01000000;
		assertFalse(SkinPng.slim(almost, 64, 64), "one barely visible pixel in the strip makes it classic");
		int[] onlyTop = SkinImages.pixels(64, false);
		SkinImages.clear(onlyTop, 50, 16, 2, 4);
		assertFalse(SkinPng.slim(onlyTop, 64, 64), "both strips have to be clear");

		int[] old = SkinImages.pixels(32, false);
		SkinImages.clear(old, 50, 16, 2, 4);
		SkinImages.clear(old, 54, 20, 2, 12);
		assertFalse(SkinPng.slim(old, 64, 32), "64x32 skins are always classic");
	}

	@Test
	void oldSkinsGetMirroredLeftLimbs() {
		int[] old = SkinImages.pixels(32, false);
		int[] modern = SkinPng.modernize(old, 64, 32);
		assertEquals(64 * 64, modern.length);
		// The top half is kept (apart from alpha, which the next test covers).
		for (int i = 0; i < 64 * 32; i++) assertEquals(old[i] & 0xFFFFFF, modern[i] & 0xFFFFFF, "pixel " + i);

		// Left leg front (20..24, 52..64) is the right leg front (4..8, 20..32) flipped left to right.
		for (int y = 0; y < 12; y++) {
			for (int x = 0; x < 4; x++) {
				assertEquals(old[(20 + y) * 64 + 4 + x], modern[(52 + y) * 64 + 20 + 3 - x], "leg front " + x + "," + y);
				// Left arm front (36..40, 52..64) from the right arm front (44..48, 20..32).
				assertEquals(old[(20 + y) * 64 + 44 + x], modern[(52 + y) * 64 + 36 + 3 - x], "arm front " + x + "," + y);
				// The outside of the leg goes to the outside: right leg's right side (0..4) to the left leg's left side (24..28).
				assertEquals(old[(20 + y) * 64 + x], modern[(52 + y) * 64 + 24 + 3 - x], "leg side " + x + "," + y);
			}
		}
		// Tops of the left leg and arm.
		for (int y = 0; y < 4; y++) {
			for (int x = 0; x < 4; x++) {
				assertEquals(old[(16 + y) * 64 + 4 + x], modern[(48 + y) * 64 + 20 + 3 - x]);
				assertEquals(old[(16 + y) * 64 + 44 + x], modern[(48 + y) * 64 + 36 + 3 - x]);
			}
		}
		// The new outer layer for the limbs is empty.
		assertEquals(0, modern[40 * 64 + 4]);
		assertEquals(0, modern[56 * 64 + 2], "left leg's outer layer");
		assertEquals(0, modern[56 * 64 + 50], "left arm's outer layer");
	}

	@Test
	void theInnerLayerIsMadeSolidWhereMinecraftMakesItSolid() {
		int[] argb = SkinImages.pixels(64, false);
		argb[10 * 64 + 10] = 0x00ABCDEF;     // head
		argb[20 * 64 + 60] = 0x00ABCDEF;     // right arm (y 16..32)
		argb[56 * 64 + 20] = 0x00ABCDEF;     // left leg (x 16..48, y 48..64)
		argb[10 * 64 + 40] = 0x00ABCDEF;     // hat: the outer layer keeps its see-through pixels
		argb[40 * 64 + 20] = 0x00ABCDEF;     // jacket
		argb[56 * 64 + 2] = 0x00ABCDEF;      // left leg's outer layer
		int[] modern = SkinPng.modernize(argb, 64, 64);
		assertEquals(0xFFABCDEF, modern[10 * 64 + 10]);
		assertEquals(0xFFABCDEF, modern[20 * 64 + 60]);
		assertEquals(0xFFABCDEF, modern[56 * 64 + 20]);
		assertEquals(0x00ABCDEF, modern[10 * 64 + 40]);
		assertEquals(0x00ABCDEF, modern[40 * 64 + 20]);
		assertEquals(0x00ABCDEF, modern[56 * 64 + 2]);
		assertNotSame(argb, modern, "a copy");
		assertEquals(0x00ABCDEF, argb[10 * 64 + 10], "the original is left as it was");
	}

	@Test
	void anOldSkinWhoseHatIsOneSolidBlockHasNoHat() {
		int[] old = SkinImages.pixels(32, false);
		int[] solidHat = SkinPng.modernize(old, 64, 32);
		assertEquals(0, solidHat[10 * 64 + 40] >>> 24, "Minecraft reads a fully solid hat area as no hat");

		old[10 * 64 + 40] = 0;
		int[] realHat = SkinPng.modernize(old, 64, 32);
		assertEquals(0xFF, realHat[10 * 64 + 41] >>> 24, "a hat with a hole in it is a real hat");
	}

	@Test
	void modernizeRefusesWhatIsNotASkin() {
		assertThrows(IllegalArgumentException.class, () -> SkinPng.modernize(new int[64 * 48], 64, 48));
		assertThrows(IllegalArgumentException.class, () -> SkinPng.modernize(new int[10], 64, 64));
	}

	@Test
	void theFaceIsTheHeadWithItsHatOnTop() {
		int[] argb = SkinImages.pixels(64, false);
		SkinImages.clear(argb, 40, 8, 8, 8);
		argb[8 * 64 + 40] = 0xFF00FF00;
		int[] face = SkinPng.face(argb, 64, 64);
		assertEquals(64, face.length);
		assertEquals(0xFF00FF00, face[0], "the hat covers the head");
		assertEquals(argb[9 * 64 + 9], face[8 + 1], "the head shows where the hat is clear");
		for (int p : face) assertEquals(0xFF, p >>> 24, "a face is solid");
	}

	@Test
	void textureHashIsTheLastPartOfAMojangTextureAddress() {
		String hash = "3b60a1f6d562f52aaebbf1434f1de147933a3affe0e764fa49ea057536623cd3";
		assertEquals(hash, SkinPng.textureHash("http://textures.minecraft.net/texture/" + hash));
		assertEquals(hash, SkinPng.textureHash("https://textures.minecraft.net/texture/" + hash));
		assertNull(SkinPng.textureHash("https://example.com/texture/" + hash), "another server's file is not Mojang's skin");
		assertNull(SkinPng.textureHash("https://textures.minecraft.net/texture/skin.png"));
		assertNull(SkinPng.textureHash("not an address"));
		assertNull(SkinPng.textureHash(null));
	}
}
