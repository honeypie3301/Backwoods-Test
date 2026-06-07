#version  120

out vec2 texCoord;
flat out vec4 glColor;

void main() {
    gl_Position = ftransform();
    texCoord = (gl_TextureMatrix[0] * gl_MultiTexCoord0).xy;
    
    glColor = gl_Color;
}
