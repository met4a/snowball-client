#version 330

// One step down Snowglass's blur pyramid: half the size, a dual-filter (Kawase) average of five
// bilinear taps. Drawn with vanilla's full-screen vertex shader (core/screenquad).

uniform sampler2D InSampler;

in vec2 texCoord;

out vec4 fragColor;

void main() {
    vec2 texel = 1.0 / vec2(textureSize(InSampler, 0));
    vec3 sum = texture(InSampler, texCoord).rgb * 4.0;
    sum += texture(InSampler, texCoord - texel).rgb;
    sum += texture(InSampler, texCoord + texel).rgb;
    sum += texture(InSampler, texCoord + vec2(texel.x, -texel.y)).rgb;
    sum += texture(InSampler, texCoord - vec2(texel.x, -texel.y)).rgb;
    fragColor = vec4(sum / 8.0, 1.0);
}
