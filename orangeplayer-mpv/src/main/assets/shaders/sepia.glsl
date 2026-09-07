// 复古档：RGB sepia 矩阵。mpv user shader。
//!HOOK MAIN
//!BIND MAIN
vec4 hook() {
    vec4 c = MAIN_tex(MAIN_pos);
    float r = dot(c.rgb, vec3(0.393, 0.769, 0.189));
    float g = dot(c.rgb, vec3(0.349, 0.686, 0.168));
    float b = dot(c.rgb, vec3(0.272, 0.534, 0.131));
    return vec4(r, g, b, c.a);
}
