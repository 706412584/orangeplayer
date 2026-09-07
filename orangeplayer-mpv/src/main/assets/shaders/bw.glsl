// 黑白档：RGB 去色（Rec.601 luma）。mpv user shader。
//!HOOK MAIN
//!BIND MAIN
vec4 hook() {
    vec4 c = MAIN_tex(MAIN_pos);
    float g = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    return vec4(vec3(g), c.a);
}
