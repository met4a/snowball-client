package dev.snowballclient.client.gui;

import dev.snowballclient.client.module.Module;
import dev.snowballclient.client.module.ModuleCategory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleSearchTest {
	static final class M extends Module {
		M(String id, String name, String description, ModuleCategory category) {
			super(id, name, description, category);
		}
	}

	private final M zoom = new M("zoom", "Zoom", "Hold the key to zoom the camera", ModuleCategory.QOL);
	private final M freelook = new M("freelook", "Freelook", "Hold to look around without turning", ModuleCategory.QOL);
	private final M fullbright = new M("fullbright", "Fullbright", "Makes dark areas easier to see", ModuleCategory.RENDER);
	private final List<Module> all = List.of(zoom, freelook, fullbright, zoom);

	@Test
	void emptyQueryReturnsEverythingOnce() {
		assertEquals(List.of(zoom, freelook, fullbright), ModuleSearch.filter(all, "  "));
	}

	@Test
	void matchesNameDescriptionAndCategoryIgnoringCase() {
		assertEquals(List.of(zoom), ModuleSearch.filter(all, "ZOO"));
		assertEquals(List.of(fullbright), ModuleSearch.filter(all, "dark"));
		assertEquals(List.of(fullbright), ModuleSearch.filter(all, "render"));
		assertTrue(ModuleSearch.filter(all, "nothing like this").isEmpty());
	}

	@Test
	void everyWordMustMatchAndNameMatchesComeFirst() {
		assertEquals(List.of(freelook), ModuleSearch.filter(all, "hold look"));
		// "o" is in the names of Zoom and Freelook but only in Fullbright's description ("to see"): name hits rank first.
		assertEquals(List.of(zoom, freelook, fullbright), ModuleSearch.filter(all, "o"));
	}
}
