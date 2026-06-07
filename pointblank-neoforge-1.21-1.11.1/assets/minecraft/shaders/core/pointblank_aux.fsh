#version 150

uniform sampler2D Sampler0;

uniform vec4 ColorModulator;

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

const float strength = 0.1;

void main() {

    vec2 aspectRatio = vec2(1.0, 1.0);
    vec2 uv = (texCoord0.xy - 0.5) * 2.0;

    vec2 distortedCoord = vec2(
        (1.0 - uv.y * uv.y) * strength * uv.x,
        (1.0 - uv.x * uv.x) * strength * uv.y
    );

    float chromaAbRed = texture(Sampler0, (uv - distortedCoord * 0.95) * 0.5 + 0.5).x;
    vec2 chromaAbGreenBlue = texture(Sampler0, (uv - distortedCoord) * 0.5 + 0.5).yz;
    vec3 color = vec3(chromaAbRed, chromaAbGreenBlue);

    // Vignetting
    float uvMagSqrd = dot(uv * aspectRatio, uv * aspectRatio);
    float vignette = 1.0 - uvMagSqrd * strength * 4.5;
    color *= vignette;

    fragColor = vec4(color.rgb, 1.0) * ColorModulator;
}
