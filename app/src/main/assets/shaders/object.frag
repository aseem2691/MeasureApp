precision mediump float;
varying vec3 v_ViewPosition;
varying vec3 v_ViewNormal;
uniform sampler2D u_Texture;
void main() {
    // Simple texture mapping
    gl_FragColor = texture2D(u_Texture, v_ViewPosition.xy);
}

