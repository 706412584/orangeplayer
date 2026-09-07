// 鲜艳档：RGB 饱和度增强（S=1.5）。mpv user shader。
//!HOOK MAIN
//!BIND MAIN
vec4 hook() {
    vec4 c = MAIN_tex(MAIN_pos);
    float luma = dot(c.rgb, vec3(0.299, 0.587, 0.114));
    return vec4(mix(vec3(luma), c.rgb, 1.5), c.a);
}
