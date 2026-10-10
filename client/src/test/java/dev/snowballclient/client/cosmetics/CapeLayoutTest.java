package dev.snowballclient.client.cosmetics;

import dev.snowballclient.client.social.Looks;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CapeLayoutTest {
	@Test
	void theFaceRoundTripsThroughTheBackendForm() {
		int[] face = new int[160];
		for (int i = 0; i < face.length; i++) face[i] = (i * 0x10203) & 0xFFFFFF;
		String hex = CapeLayout.encode(face);
		assertEquals(960, hex.length());
		assertArrayEquals(face, CapeLayout.decode(hex));
		assertNull(CapeLayout.decode("zz"));
		assertNull(CapeLayout.decode(hex.toUpperCase()), "only the backend's lowercase form");
	}

	@Test
	void theTexturePutsTheFaceWhereTheCapeModelReadsIt() {
		int[] face = CustomCapes.blank();
		face[0] = 0xFF0000;
		face[159] = 0x00FF00;
		int[] tex = CapeLayout.texture(face);
		assertEquals(64 * 32, tex.length);
		assertEquals(0xFFFF0000, tex[1 * 64 + 1], "top left of the outer face at (1,1)");
		assertEquals(0xFF00FF00, tex[16 * 64 + 10], "bottom right at (10,16)");
		// Every pixel of the cape area is solid: capes are drawn without transparency.
		for (int y = 1; y <= 16; y++) for (int x = 0; x <= 21; x++) assertEquals(0xFF, tex[y * 64 + x] >>> 24);
		// The inside is a darker mirror.
		int inside = tex[1 * 64 + 21];
		assertTrue(((inside >> 16) & 0xFF) < 0xFF && ((inside >> 16) & 0xFF) > 0x80);
	}

	@Test
	void customCapesAreDrawnFromTheirApprovedVersion() {
		UUID them = UUID.randomUUID();
		Looks.setCape(them, "custom", 3);
		assertEquals("custom:3", Looks.cape(them));
		Looks.setCape(them, "custom", 0);
		assertNull(Looks.cape(them), "custom without an approved version is nothing");
		Looks.setCape(them, "aurora", 0);
		assertEquals("aurora", Looks.cape(them));
		Looks.setCape(them, "../evil", 0);
		assertNull(Looks.cape(them));
		Looks.forgetOthers();
	}
}
