#version 330

// One step up Snowglass's blur pyramid: twice the size, the dual-filter (Kawase) eight-tap tent,
// which spreads the blur smoothly with no blockiness from the small level below.

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
    vec2 diagonal = texel * 0.5;
    vec3 sum = texture(InSampler, texCoord + vec2(-texel.x, 0.0)).rgb;
    sum += texture(InSampler, texCoord + vec2(texel.x, 0.0)).rgb;
    sum += texture(InSampler, texCoord + vec2(0.0, -texel.y)).rgb;
    sum += texture(InSampler, texCoord + vec2(0.0, texel.y)).rgb;
    sum += texture(InSampler, texCoord + vec2(-diagonal.x, -diagonal.y)).rgb * 2.0;
    sum += texture(InSampler, texCoord + vec2(diagonal.x, -diagonal.y)).rgb * 2.0;
    sum += texture(InSampler, texCoord + vec2(-diagonal.x, diagonal.y)).rgb * 2.0;
    sum += texture(InSampler, texCoord + vec2(diagonal.x, diagonal.y)).rgb * 2.0;
    fragColor = vec4(sum / 12.0, 1.0);
}
