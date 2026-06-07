#version 150

uniform sampler2D DiffuseSampler;

in vec2 texCoord;
in vec2 oneTexel;
in vec4 gl_FragCoord;
in vec4 fragCoord;
uniform vec3 iResolution;  

uniform vec2 InSize;

uniform float Time;
uniform float Progress;
uniform float Amplitude;
uniform float Step;
uniform float Frequency;

out vec4 fragColor;

void main(void) {

	float adjustedDistortion = 0.5 * (1.0 + cos(Progress * Progress * 3.1415926535 * 0.5)) * Amplitude;
	
	float aspectRatio = InSize.x / InSize.y;
	
	vec2 testRes = vec2(aspectRatio, 1.0);
    
    vec2 cPos = ((texCoord - 0.5) * 2.0) * testRes;
    
	float cLength = length(cPos);
	
	cLength = 1.0 * cLength * cLength * cLength + cLength * 0.01 + 0.1;
	
	float amplitude = sin(Time * 3.1415926535 * 20.0);	
	
	vec2 uv = texCoord + (cPos/cLength) 
		* cos(cLength * Step - Time * 3.1415926535 * Frequency) * adjustedDistortion;

	vec3 col = texture(DiffuseSampler, uv).xyz;
	
	fragColor = vec4(col, 1.0);
}