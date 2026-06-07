#version  120

uniform sampler2D texture;

uniform float viewHeight;
uniform float viewWidth;

in vec2 texCoord;

const float strength = 0.1;

/* RENDERTARGETS: {} */
void main() {

    vec2 aspectRatio = vec2(viewWidth / viewHeight, 1.0);

    vec2 uv = (texCoord.xy - 0.5) * 2.0;

    vec2 distortedCoord = vec2(
        (1.0 - uv.y * uv.y) * strength * uv.x,
        (1.0 - uv.x * uv.x) * strength * uv.y
    );

    float chromaAbRed = texture2D(texture, (uv - distortedCoord * 0.95) * 0.5 + 0.5).x;
    vec2 chromaAbGreenBlue = texture2D(texture, (uv - distortedCoord) * 0.5 + 0.5).yz;
    vec3 color = vec3(chromaAbRed, chromaAbGreenBlue);

    float uvMagSqrd = dot(uv * aspectRatio, uv * aspectRatio);
    float vignette = 1.0 - uvMagSqrd * strength * 4.5;
    color *= vignette;

    gl_FragColor = vec4(color.rgb, 1.0);
}
