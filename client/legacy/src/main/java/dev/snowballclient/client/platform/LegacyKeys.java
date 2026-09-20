package dev.snowballclient.client.platform;

/**
 * Minecraft 1.8.9 uses LWJGL 2 key codes while newer versions (and Snowball's saved keybinds) use
 * GLFW ones, so the two are translated here and a keybind set on any version means the same key.
 */
public final class LegacyKeys {
	// {LWJGL 2 code, GLFW code}
	private static final int[][] PAIRS = {
			{1, 256}, {2, 49}, {3, 50}, {4, 51}, {5, 52}, {6, 53}, {7, 54}, {8, 55}, {9, 56}, {10, 57}, {11, 48},
			{12, 45}, {13, 61}, {14, 259}, {15, 258},
			{16, 81}, {17, 87}, {18, 69}, {19, 82}, {20, 84}, {21, 89}, {22, 85}, {23, 73}, {24, 79}, {25, 80},
			{26, 91}, {27, 93}, {28, 257}, {29, 341},
			{30, 65}, {31, 83}, {32, 68}, {33, 70}, {34, 71}, {35, 72}, {36, 74}, {37, 75}, {38, 76},
			{39, 59}, {40, 39}, {41, 96}, {42, 340}, {43, 92},
			{44, 90}, {45, 88}, {46, 67}, {47, 86}, {48, 66}, {49, 78}, {50, 77},
			{51, 44}, {52, 46}, {53, 47}, {54, 344}, {55, 332}, {56, 342}, {57, 32}, {58, 280},
			{59, 290}, {60, 291}, {61, 292}, {62, 293}, {63, 294}, {64, 295}, {65, 296}, {66, 297}, {67, 298}, {68, 299},
			{69, 282}, {70, 281},
			{71, 327}, {72, 328}, {73, 329}, {74, 333}, {75, 324}, {76, 325}, {77, 326}, {78, 334},
			{79, 321}, {80, 322}, {81, 323}, {82, 320}, {83, 330},
			{87, 300}, {88, 301},
			{156, 335}, {157, 345}, {181, 331}, {184, 346}, {197, 284},
			{199, 268}, {200, 265}, {201, 266}, {203, 263}, {205, 262}, {207, 269}, {208, 264}, {209, 267},
			{210, 260}, {211, 261}, {219, 343}, {220, 347}, {221, 348},
	};

	private static final int MAX_LWJGL = 256;
	private static final int MAX_GLFW = 349;
	private static final int[] TO_GLFW = new int[MAX_LWJGL];
	private static final int[] TO_LWJGL = new int[MAX_GLFW];

	static {
		for (int[] pair : PAIRS) {
			TO_GLFW[pair[0]] = pair[1];
			TO_LWJGL[pair[1]] = pair[0];
		}
	}

	private LegacyKeys() {
	}

	/** @return the GLFW code for an LWJGL 2 key, or -1 ("unbound") for keys newer versions do not have */
	public static int toGlfw(int lwjglCode) {
		if (lwjglCode <= 0 || lwjglCode >= MAX_LWJGL) return -1;
		int glfw = TO_GLFW[lwjglCode];
		return glfw == 0 ? -1 : glfw;
	}

	/** @return the LWJGL 2 code for a GLFW key, or 0 ("none") when this version has no such key */
	public static int toLwjgl(int glfwCode) {
		if (glfwCode < 0 || glfwCode >= MAX_GLFW) return 0;
		return TO_LWJGL[glfwCode];
	}
}
