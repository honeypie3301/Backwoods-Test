#version  120

uniform sampler2D texture;

uniform float viewHeight;
uniform float viewWidth;

in vec2 texCoord;
flat in vec4 glColor;

/* RENDERTARGETS: {} */
void main() {

    vec4 color = texture2D(texture, texCoord) * glColor;
    if (color.a < 0.01) {
        discard;
    }
    gl_FragData[0] = vec4(color.rgb, 1.0);
}
