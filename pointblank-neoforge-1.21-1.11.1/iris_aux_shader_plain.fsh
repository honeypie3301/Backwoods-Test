#version  120

uniform sampler2D texture;

in vec2 texCoord;

/* RENDERTARGETS: {} */
void main() {
    vec4 color = texture2D(texture, texCoord);
    gl_FragColor = color;
}
