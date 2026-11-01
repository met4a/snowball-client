#version 330

// Snowglass: one panel of glass over the world. The world behind comes from a blur pyramid built
// once a frame (SnowglassRenderer); this shader bends it at the rim, frosts it, tints it as much as
// the text on it needs, and lights the rim from the top left.

layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};

uniform sampler2D Sampler0; // the world, half size, lightly blurred
uniform sampler2D Sampler1; // the world, frosted
uniform sampler2D Sampler2; // the world, an eighth of the size: how bright it is behind the panel

in vec4 tint;
in vec2 local;
in vec2 screenUv;
flat in vec4 shape;
flat in vec3 material;

out vec4 fragColor;

// Tuned in the Glass Lab and baked in as defines; these are the defaults.
#ifndef LENS_BEZEL
#define LENS_BEZEL 12.0
#endif
#ifndef LENS_STRENGTH
#define LENS_STRENGTH 8.0
#endif
#ifndef DISPERSION
#define DISPERSION 1.0
#endif
#ifndef GRAIN
#define GRAIN 0.015
#endif
#ifndef SHADOW
#define SHADOW 1.0
#endif

// Luminance of the text colours glass must keep readable (SnowglassTokens, Legibility).
const float TEXT_PRIMARY = 0.91691;
const float TEXT_SECONDARY_CLEAR = 0.47076;
const float TEXT_SECONDARY = 0.29121;
const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);
// Towards the one light: up and to the left (GUI y grows downwards).
const vec2 LIGHT = vec2(-0.70710678, -0.70710678);

float roundedBox(vec2 p, vec2 halfSize, float r) {
    vec2 q = abs(p) - halfSize + r;
    return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - r;
}

float decode(float c) {
    return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4);
}

float encode(float l) {
    return l <= 0.0031308 ? l * 12.92 : 1.055 * pow(l, 1.0 / 2.4) - 0.055;
}

float luminance(vec3 c) {
    return dot(vec3(decode(c.r), decode(c.g), decode(c.b)), LUMA);
}

// The least tint that leaves the backdrop dark enough under text of luminance textL. Blending
// happens on stored (gamma-encoded) values, as in the game's colour target; see Legibility.java.
float neededTint(float backdrop, float tintValue, float textL, float ratio) {
    float darkest = (textL + 0.05) / ratio - 0.05;
    float limit = encode(max(darkest, 0.0));
    if (backdrop <= limit) return 0.0;
    return clamp((backdrop - limit) / max(backdrop - tintValue, 1e-3), 0.0, 1.0);
}

float hash(vec2 p) {
    return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
}

// The frosted level, softened once more with a small tent of taps: about twice the blur of the
// level itself, for the cost of four texture reads instead of another pass.
vec3 frosted(vec2 uv) {
    vec2 spread = 1.25 / vec2(textureSize(Sampler1, 0));
    return (texture(Sampler1, uv).rgb * 2.0
            + texture(Sampler1, uv + vec2(spread.x, spread.y)).rgb
            + texture(Sampler1, uv + vec2(-spread.x, spread.y)).rgb
            + texture(Sampler1, uv + vec2(spread.x, -spread.y)).rgb
            + texture(Sampler1, uv + vec2(-spread.x, -spread.y)).rgb) / 6.0;
}

void main() {
    vec2 halfSize = shape.xy;
    float radius = min(shape.z, min(halfSize.x, halfSize.y));
    int flags = int(shape.w + 0.5);
    float baseTint = float(flags & 255) / 255.0;
    bool full = (flags & 256) != 0;
    bool shadow = (flags & 512) != 0;
    bool focused = (flags & 1024) != 0;
    bool clearText = (flags & 2048) != 0;

    // Derivatives first, while every pixel of the quad is still on the same path.
    float d = roundedBox(local, halfSize, radius);
    float aa = max(fwidth(d), 1e-4);
    mat2 localPerScreen = mat2(dFdx(local), dFdy(local));
    mat2 uvPerScreen = mat2(dFdx(screenUv), dFdy(screenUv));
    float det = determinant(localPerScreen);
    // How far a step in the panel's own units moves across the backdrop, whatever the pose
    // (HUD rotation, 3D Menus' lean) does to the panel.
    mat2 uvPerLocal = abs(det) > 1e-12 ? uvPerScreen * inverse(localPerScreen) : mat2(0.0);

    float coverage = clamp(0.5 - d / aa, 0.0, 1.0);
    float shadowAlpha = 0.0;
    if (shadow) {
        float below = roundedBox(local - vec2(0.0, 2.0), halfSize, radius);
        shadowAlpha = SHADOW * 0.28 * (1.0 - smoothstep(-2.0, 7.0, below));
    }
    if (coverage <= 0.0) {
        float a = clamp(shadowAlpha, 0.0, 1.0) * tint.a;
        if (a <= 0.002) discard;
        fragColor = vec4(0.0, 0.0, 0.0, a) * ColorModulator;
        return;
    }

    vec2 e = vec2(0.5, 0.0);
    vec2 normal = normalize(vec2(
            roundedBox(local + e.xy, halfSize, radius) - roundedBox(local - e.xy, halfSize, radius),
            roundedBox(local + e.yx, halfSize, radius) - roundedBox(local - e.yx, halfSize, radius)) + vec2(1e-6));
    float inside = max(-d, 0.0);
    float bezel = max(min(LENS_BEZEL, min(halfSize.x, halfSize.y) * 0.8), 1e-3);
    float band = clamp(1.0 - inside / bezel, 0.0, 1.0);

    // Lensing: near the rim the world is taken from further out, as thick glass bends it.
    vec2 uv = screenUv;
    if (full) uv += uvPerLocal * (normal * LENS_STRENGTH * band * band);

    // Small controls stay clear droplets; big panels frost, the more so the thicker the level. On
    // Full the rim looks through clearer glass, so the bending there can be seen.
    float size = min(halfSize.x, halfSize.y) * 2.0;
    float frost = smoothstep(8.0, 60.0, size) * mix(0.55, 1.0, clamp(material.y, 0.0, 1.0));
    if (full) frost *= 1.0 - 0.85 * band;
    vec3 world = mix(texture(Sampler0, uv).rgb, frosted(uv), frost);
    if (full && band > 0.01 && DISPERSION > 0.0) {
        vec2 split = uvPerLocal * (normal * DISPERSION * band);
        world.r = mix(texture(Sampler0, uv + split).r, frosted(uv + split).r, frost);
        world.b = mix(texture(Sampler0, uv - split).b, frosted(uv - split).b, frost);
    }
    float saturation = 1.0 + material.z;
    world = mix(vec3(dot(world, LUMA)), world, saturation);

    // Tint: the slider's amount, or more where the world behind would sink the text.
    vec2 centre = screenUv - uvPerLocal * local;
    vec2 across = uvPerLocal * vec2(halfSize.x * 0.5, 0.0);
    vec2 down = uvPerLocal * vec2(0.0, halfSize.y * 0.5);
    vec3 behind = (texture(Sampler2, centre).rgb + texture(Sampler2, centre + across).rgb + texture(Sampler2, centre - across).rgb
            + texture(Sampler2, centre + down).rgb + texture(Sampler2, centre - down).rgb) / 5.0;
    float backdrop = encode(luminance(behind));
    float tintValue = encode(luminance(tint.rgb));
    float alpha = max(baseTint, max(neededTint(backdrop, tintValue, TEXT_PRIMARY, 4.5),
            neededTint(backdrop, tintValue, clearText ? TEXT_SECONDARY_CLEAR : TEXT_SECONDARY, 3.0)));
    vec3 color = mix(world, tint.rgb, alpha);

    // The rim: a thin bright edge, strongest where it faces the light.
    float lit = max(dot(normal, LIGHT), 0.0);
    float rimLine = 1.0 - smoothstep(0.0, 1.6, inside / aa);
    float rim = material.x * (0.12 + 0.88 * lit) * (focused ? 1.4 : 1.0);
    color = mix(color, vec3(1.0), clamp(rim * rimLine * 0.6, 0.0, 1.0));
    // A soft shade inside the bottom edge.
    float shade = smoothstep(0.0, 1.0, normal.y) * (1.0 - smoothstep(0.0, 6.0, inside)) * 0.16;
    color *= 1.0 - shade;
    color += (hash(floor(gl_FragCoord.xy)) - 0.5) * GRAIN;

    float outAlpha = coverage + shadowAlpha * (1.0 - coverage);
    fragColor = vec4(color * coverage / max(outAlpha, 1e-4), outAlpha * tint.a) * ColorModulator;
}
