#version 330

// Snowglass panels. The same transform blocks as vanilla's own GUI shaders (core/gui.vsh).
layout(std140) uniform DynamicTransforms {
    mat4 ModelViewMat;
    vec4 ColorModulator;
    vec3 ModelOffset;
    mat4 TextureMat;
};
layout(std140) uniform Projection {
    mat4 ProjMat;
};

// Per-panel values ride in vanilla's entity vertex layout, since GUI draws carry no uniforms of their own.
in vec3 Position;
in vec4 Color;   // glass tint, and the panel's fade in alpha
in vec2 UV0;     // where this vertex is in the panel, in GUI units from its centre
in ivec2 UV1;    // half width and half height, in eighths of a GUI unit
in ivec2 UV2;    // corner radius in eighths, then the packed flags (see SnowglassRenderer)
in vec3 Normal;  // rim strength, frost, saturation boost

out vec4 tint;
out vec2 local;
out vec2 screenUv;
flat out vec4 shape;
flat out vec3 material;

void main() {
    vec4 clip = ProjMat * ModelViewMat * vec4(Position, 1.0);
    gl_Position = clip;
    // The same corner-to-texel mapping vanilla's full-screen passes use (core/screenquad), so the
    // backdrop lines up on both graphics backends.
    screenUv = clip.xy / clip.w * 0.5 + 0.5;
    tint = Color;
    local = UV0;
    shape = vec4(vec2(UV1) / 8.0, float(UV2.x) / 8.0, float(UV2.y));
    material = Normal;
}
