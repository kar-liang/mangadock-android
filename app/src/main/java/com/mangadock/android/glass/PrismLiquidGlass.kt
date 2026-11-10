package com.mangadock.android.glass

import android.graphics.RuntimeShader
import androidx.compose.ui.graphics.Color

/**
 * prism_ui 液态玻璃管线的 Android 移植底座。
 *
 * 出处（`_refs/prism_ui/`）：
 *  - `prism_liquid_glass.dart` → [LiquidGlassStyle] 与 `scale(lift)`，参数 1:1 照抄
 *  - `prism_liquid_glass.frag` → [PRISM_GLASS_AGSL] 的光学主体，SkSL → AGSL
 *  - `prism_navigation_dock.dart` → 几何 / lift 在 GlassDock.kt，`_LensContent` 的
 *    图标二次折射也在这里落地
 *
 * 和 Flutter 版唯一的结构差异：Flutter 用 `BackdropFilter` / `ImageFilter.shader`
 * 直接采已经画好的 backdrop；Compose 的 `RenderEffect.createRuntimeShaderEffect`
 * 的 `uContent` 只采**挂 effect 的节点自身内容**。所以在 GlassDock 里把
 * 「页面内容层 + 录制好的底栏胶囊」画进气泡节点，再由 shader 折射 ——
 * 等价于 engine 的 backdrop capture（frag 里也叫 uTexture / uClamp）。
 *
 * 砍掉的部分（底栏气泡用不到，且最贵）：goo 融合 `uProfile/uPills/uCurves`、
 * menu shine 数组 `uShine*`、`sampleFrosted` 的 64 taps 模糊圆盘。
 * `LiquidGlassStyle.lens` 是 blur=0 / haze=0，凸透镜本来就只做一次采样。
 */

/**
 * `liquid_glass.frag` main() 的核心光学，逐段对应：
 *  - 圆角矩形/胶囊 SDF + 法线           → sdRoundBox + 四点梯度
 *  - uOptics.x < 0 的 raised lens 分支   → shift（向外读，把胶囊上下边线掰弯）
 *  - uEdge.y 的通道分离                  → part（红蓝沿 normal.yx 分开）
 *  - uOptics.y/z 饱和度、亮度            → col
 *  - uLight.z endShade                   → 两端压暗
 *  - uTint / coverage / veil             → glass
 *  - uLight.x × facing × band/tail       → 上下沿亮白 shine
 *  - uEdge.x / uRim.w 的最外暗线         → hairline
 *
 * 单位全是**节点局部像素**：dp 参数在 [PrismLiquidGlass.applyTo] 里乘 density 后传入。
 * 采样 clamp 到 uSize，对应 frag 的 `uClamp`（capture 只读自己那块）。
 */
const val PRISM_GLASS_AGSL = """
uniform shader uContent;

uniform float2 uSize;
uniform float  uPad;          // 节点比镜片每边多出的采样余量 (px)
uniform float  uRadius;       // capsule corner radius (px)
uniform float  uBevel;        // refraction band width (px)
uniform float3 uOptics;       // refraction(负=凸镜), saturation, brightness
uniform float4 uTint;         // rgb, alpha
uniform float  uLine;         // hairline darkness (uEdge.x)
uniform float  uLinePx;       // kLine in px
uniform float  uDispersion;   // uEdge.y, px
uniform float  uBlurPx;       // uShape.w, style.blur * density
uniform float  uRimFrostPx;   // uEdge.z, style.rimBlur * density
uniform float3 uLight;        // shine, shine fade px (rimWidth), endShade
uniform float4 uRim;          // rimTail, rimFocus, rimAdded, hairlineSide
uniform float  uRimTailPx;    // kRimTail in px
uniform float  uPartHoldPx;   // kPartHold in px
uniform float  uPartReachPx;  // kPartReach in px
uniform float  uContrast;     // uGlow.w —— 背景比 tint 暗多少就把 tint 加厚多少

const float kEndShade = 3.0;
const float kLineCap  = 0.298;

float sdRoundBox(float2 p, float2 b, float r) {
    float2 q = abs(p) - b + float2(r);
    return min(max(q.x, q.y), 0.0) + length(max(q, float2(0.0))) - r;
}

/* frag:122-189 —— texel / sampleFrosted 的 AGSL 版。
 * 原版按 sigma 分 16/32/64 taps 的 Vogel 圆盘；这里固定 8 taps，
 * 衰减与 spread 公式照抄。lens/lensContent 的 blur 都是 0，
 * frost 只由 rimBlur * rise^2 在边缘产生；静止时 scale(0) 让它归零，
 * 直接退化成单次采样。 */
half4 sampleContent(float2 p, float2 lo, float2 hi) {
    return uContent.eval(clamp(p, lo, hi));
}

half4 frosted(float2 p, float2 lo, float2 hi, float sigma) {
    if (sigma < 0.75) return sampleContent(p, lo, hi);
    float reach = max(sigma - 0.5, 0.0) * 2.07846096908;
    float2 turn = float2(-0.737368878, 0.675490394); // cos/sin(kGoldenAngle)
    float2 ray = float2(1.0, 0.0);
    float decay = exp(-2.1592 / 8.0);
    float weight = sqrt(decay);
    half4 sum = half4(0.0);
    float total = 0.0;
    for (int i = 0; i < 8; i++) {
        float spread = sqrt((float(i) + 0.5) / 8.0);
        sum += sampleContent(p + ray * reach * spread, lo, hi) * weight;
        total += weight;
        weight *= decay;
        ray = float2(ray.x * turn.x - ray.y * turn.y,
                     ray.x * turn.y + ray.y * turn.x);
    }
    return sum / max(total, 1e-4);
}

half4 main(float2 p) {
    /*
     * uPad：节点为了「能采到镜片外的内容」而向外扩出的余量。
     * Flutter 那边 `uTexture` 是整块 backdrop 纹理，镜片边缘往外采样读到的是
     * 真实的邻居内容；Compose 的 `uContent` 只有节点自身，采样越界会被 clamp
     * 成边缘像素 —— 表现就是图标在镜片边上被拉成一条硬舌头。
     * 所以镜片面板的节点头比镜片本身大一圈，SDF 仍按镜片尺寸算，多出来那圈
     * 只用于采样，最后由外层 clip 裁掉。
     */
    float2 h = (uSize - 2.0 * uPad) * 0.5;
    float2 c = p - uSize * 0.5;
    float r = min(uRadius, min(h.x, h.y));
    float inset = max(-sdRoundBox(c, h, r), 0.0);
    float halfMinor = min(h.x, h.y);
    // frag:461 —— bevel 再被 halfMinor*0.5 夹一次，小面板按比例缩
    float bevel = max(min(uBevel, halfMinor * 0.5), 0.001);
    float rise = 1.0 - clamp(inset / bevel, 0.0, 1.0);

    // frag:213 roundedBoxNormal —— 解析法线。之前用四点有限差分(e=1px)，
    // 圆角处梯度会抖成台阶，折射带跟着发糙。
    float2 q = abs(c) - h + float2(r);
    float2 n = (q.x > 0.0 && q.y > 0.0)
        ? q / max(length(q), 1e-4)
        : (q.x > q.y ? float2(1.0, 0.0) : float2(0.0, 1.0));
    n *= float2(c.x < 0.0 ? -1.0 : 1.0, c.y < 0.0 ? -1.0 : 1.0);

    bool lens = uOptics.x < 0.0;
    float2 shift;
    if (lens) {
        // iOS 27 raised lens: nothing at the rim, full depth a bevel in,
        // easing off toward the middle. Reads OUTWARD along the normal —
        // this is what bends the dock's straight edges into arcs inside the lens.
        shift = n * (-uOptics.x) * smoothstep(0.0, bevel, inset)
              * (1.0 - smoothstep(bevel, halfMinor, inset));
    } else {
        // Sunk pane: circular bevel profile, steepest at the rim.
        float depth = min(uOptics.x, halfMinor) * (1.0 - sqrt(max(1.0 - rise * rise, 0.0)));
        shift = -n * depth;
    }

    float2 lo = float2(0.5);
    float2 hi = max(uSize - float2(0.5), lo);
    float2 sp = clamp(p + shift, lo, hi);

    // frag:480 —— frost = blur + rimBlur * rise^2，只在 bevel 边缘把采样柔化。
    float frostPx = uBlurPx + uRimFrostPx * rise * rise;

    // frag:488-493 —— 通道沿 normal 的镜像分离，并且只在 kPartReach 内做
    float2 part = float2(0.0);
    bool parted = uDispersion > 0.0 && inset < uPartReachPx;
    if (parted) {
        float partScale = uDispersion * 0.5
            * (0.6 + 0.4 * abs(n.y) * max(1.0 - inset / max(uPartHoldPx, 1.0), 0.0))
            * (1.0 - smoothstep(uPartHoldPx, uPartReachPx, inset));
        part = n.yx * partScale;
    }
    half4 cg = frosted(sp, lo, hi, frostPx);
    half4 cr = parted ? frosted(clamp(sp + part, lo, hi), lo, hi, frostPx) : cg;
    half4 cb = parted ? frosted(clamp(sp - part, lo, hi), lo, hi, frostPx) : cg;

    /*
     * frag:493 原式 base = vec4(red.r, base.g, blue.b, max(a)) —— 直接拼通道，
     * 红蓝才分得开。之前这版改成三采样 alpha 加权平均，把色散整个平均掉了，
     * 表现就是老大说的「边缘灰白硬块、没有彩虹」。
     *
     * uContent 是预乘 alpha 的，所以先反预乘回直通色再拼，最后重新预乘；
     * guard 在红/蓝采样落到透明侧(alpha -> 0)时退回中心采样，避免拼出黑边
     * （这就是当初改成加权平均想绕开的问题，正确解法是保护而不是平均）。
     */
    float ar = float(cr.a), ag = float(cg.a), ab = float(cb.a);
    float3 sr = float3(cr.rgb) / max(ar, 1e-4);
    float3 sg = float3(cg.rgb) / max(ag, 1e-4);
    float3 sb = float3(cb.rgb) / max(ab, 1e-4);
    float3 straight = float3(sr.r, sg.g, sb.b);
    float alpha = max(ag, max(ar, ab));
    float guard = clamp(min(ar, ab) / max(ag, 1e-4), 0.0, 1.0);
    straight = mix(sg, straight, guard);
    float4 base = float4(straight * alpha, alpha);

    float3 color = base.rgb / max(alpha, 0.0001);
    float3 kLuma = float3(0.2126, 0.7152, 0.0722);
    color = mix(float3(dot(color, kLuma)), color, uOptics.y) * uOptics.z;
    color *= 1.0 - uLight.z * abs(n.x) * exp(-inset / kEndShade);

    // frag:504-521 —— clash / cover。
    // 底栏压在漫画画面上时，tint 不够厚就会「白底白字」或「深底黑字」读不清。
    // prism_ui 的做法：在面板里取 5 个点估背景亮度，背景和 tint 差得越多就把
    // tint 越往不透明顶（contrast 正 = 背景更暗时加厚，负 = 背景更亮时加厚）。
    float clash = 0.0;
    if (abs(uContrast) > 0.001) {
        float2 sz = uSize - 2.0 * uPad;
        float4 behind = float4(
            sampleContent(sz * float2(0.5, 0.5), lo, hi)
            + sampleContent(sz * float2(0.2, 0.3), lo, hi)
            + sampleContent(sz * float2(0.8, 0.3), lo, hi)
            + sampleContent(sz * float2(0.2, 0.7), lo, hi)
            + sampleContent(sz * float2(0.8, 0.7), lo, hi)
        ) * 0.2;
        float behindLuma = dot(behind.rgb / max(behind.a, 1e-4), kLuma);
        clash = clamp(
            (sign(uContrast) * (dot(uTint.rgb, kLuma) - behindLuma) - 0.3) / 0.5,
            0.0, 1.0);
    }

    // tint / coverage (veil folded into uTint by the caller)
    float cover = uTint.a + (1.0 - uTint.a) * clash * abs(uContrast);
    float coverage = alpha * (1.0 - cover) + cover;
    float3 glass = color * alpha * (1.0 - cover) + uTint.rgb * cover;

    // frag:533 —— 镜子只看 |normal.y|：上下沿亮、两端消隐。
    // 非镜子（lensContent / pill）走 rise2 与 rimFocus 混合的原式。
    float facing;
    if (lens) {
        facing = smoothstep(0.65, 0.95, abs(n.y));
    } else {
        float rise2 = n.y * n.y;
        float rise4 = rise2 * rise2;
        float rise16 = rise4 * rise4 * rise4 * rise4;
        facing = mix(rise2, 0.5 * rise2 * abs(n.y) * (1.0 + rise16), uRim.y);
    }
    float within = max(inset - uLinePx, 0.0);
    float band = clamp(1.0 - within / max(uLight.y, 0.001), 0.0, 1.0);
    float tail = clamp(1.0 - max(inset - uLinePx, 0.0) / max(uRimTailPx, 0.001), 0.0, 1.0);
    float shine = min(uLight.x * facing * mix(band, tail, uRim.x), 1.0);
    glass += (float3(coverage) - (1.0 - uRim.z) * glass) * shine;

    // The dark hairline lives in the outermost pixels, over the backdrop.
    float line = clamp((uLinePx - inset) + 0.5, 0.0, 1.0);
    float across = abs(n.x);
    float darken = mix(uLine, uRim.w, across * across * across);
    if ((uLine > 0.0 || uRim.w > 0.0) && line > 0.0) {
        float4 under = float4(uContent.eval(clamp(p, lo, hi)));
        float3 dark = max(under.rgb - kLineCap * under.a, 0.22 * under.rgb);
        glass = mix(glass, mix(under.rgb, dark, darken), line);
        coverage = mix(coverage, under.a, line);
    }

    return half4(glass, coverage);
}
"""

/**
 * `LiquidGlassStyle`（prism_liquid_glass.dart:110-345）的 1:1 搬运。
 * 单位 dp / 无量纲，[PrismLiquidGlass.applyTo] 负责换算成像素并写进 shader。
 * `scale(t)` 逐字段照抄 dart：bevel / rimWidth / rimTail / rimFocus / rimAdded
 * **不随 t 变**，其余按 t 缩放，hairlineSide 为 null 时跟 hairline 一起缩。
 */
data class LiquidGlassStyle(
    val tint: Color = Color.Transparent,
    val blur: Float = 0f,
    val refraction: Float,
    val bevel: Float,
    val saturation: Float = 1f,
    val brightness: Float = 1f,
    /** uLight.x：上下沿 shine 强度 */
    val rim: Float,
    /** uLight.y：shine 从边缘往里衰减的 dp */
    val rimWidth: Float = 1.3f,
    val rimBlur: Float = 0f,
    /** uLight.z：两端压暗系数 */
    val endShade: Float = 0f,
    /** uEdge.y：红蓝通道分离距离 */
    val dispersion: Float = 0f,
    /** uEdge.x：最外暗线强度；lens 用 hairline 当它 */
    val hairline: Float = 0f,
    /** uRim.x：tail 混入比例（默认 0 = 纯 band） */
    val rimTail: Float = 0f,
    /** uRim.y：圆角端 fade 曲线在 tab bar 与 iOS 控件间的插值（默认 0） */
    val rimFocus: Float = 0f,
    /** uRim.z：以加光而非 screen 方式加入的比例（默认 0） */
    val rimAdded: Float = 0f,
    /** uRim.w：侧面暗线；null ⇒ 等于 hairline（dart 的 `hairlineSide ?? hairline`） */
    val hairlineSide: Float? = null,
    /** uGlow.w：背景与 tint 的明暗差把 tint 加厚到多满（0 = 关；dart 的 contrast） */
    val contrast: Float = 0f,
) {
    /** dart:350 —— 注意 rimTail / rimFocus / rimAdded / bevel / rimWidth 不缩放 */
    fun scale(t: Float): LiquidGlassStyle {
        val a = t.coerceIn(0f, 1f)
        return copy(
            tint = tint.copy(alpha = tint.alpha * a),
            blur = blur * a,
            refraction = refraction * a,
            saturation = 1f + (saturation - 1f) * a,
            brightness = 1f + (brightness - 1f) * a,
            rim = rim * a,
            endShade = endShade * a,
            dispersion = dispersion * a,
            rimBlur = rimBlur * a,
            hairline = hairline * a,
            hairlineSide = hairlineSide?.let { it * a },
            contrast = contrast * a,
        )
    }

    /** frag 的 uRim.w：侧面暗线，null 时退回 hairline */
    val sideLine: Float get() = hairlineSide ?: hairline

    companion object {
        /** dart:280 —— 抬起后的清透镜片，tint 透明、blur 0、负折射 */
        fun lens(dark: Boolean) = LiquidGlassStyle(
            tint = Color.Transparent,
            blur = 0f,
            refraction = -4.3f,
            bevel = 5.5f,
            saturation = 1f,
            brightness = if (dark) 1.33f else 1f,
            rim = if (dark) 0.3f else 0.6f,
            rimWidth = 1.1f,
            rimBlur = 1.5f,
            endShade = 0.045f,
            dispersion = 6.5f,
            hairline = 0.42f,
            // rimTail / rimFocus / rimAdded 保持默认 0：源码里 lens 没设它们。
            // 之前这里自作主张填 0.4 / 0.35 / 0.12，把 edge shine 稀释成了
            // mix(band, tail, 0.4)，上下沿于是发灰不亮 —— 老大 23:55 说的
            // 「两条折射弧不够亮」就是这个。
        )

        /** dart:298 —— 只折图标那一层，正折射、不画 rim */
        fun lensContent(dark: Boolean) = LiquidGlassStyle(
            tint = Color.Transparent,
            blur = 0f,
            refraction = 14f,
            bevel = 11.2f,
            saturation = 1f,
            brightness = if (dark) 1.33f else 1f,
            rim = 0f,
            rimWidth = 1.3f,
            rimBlur = 2.5f,
            dispersion = 3.6f,
            hairline = 0f,
        )

        /**
         * dart:155 `pill` —— **底栏胶囊本体**该用的那一档玻璃。
         *
         * 关键差别（相对之前手搓的 GlassSurface + 描边）：
         *  · refraction 60 / bevel 20 —— 胶囊自己是**凹面**，边缘 14dp 内把背景
         *    向内挤、镜像，这才是 FlClash 那条「玻璃珠」的边；
         *  · haze 0.72 / 15.6dp 不在 shader 里，由 [PrismGlassBar] 在采样源里预混
         *    （权重恒定 + 同一位移场 ⇒ 与 frag 的 `mix(base, frameHaze)` 数学等价）；
         *  · contrast ±0.4 —— 背景比 tint 暗就自动加厚，图标才读得清；
         *  · rim 0.6 / rimWidth 1.3 / hairline 0.35 —— 上下沿柔白 + 侧面暗线，
         *    以前是 Canvas 手画的两条描边，现在由 shader 的 SDF 出。
         */
        fun pill(dark: Boolean) = if (dark) LiquidGlassStyle(
            tint = Color(0x7A1A1A1A),
            contrast = -0.4f,
            blur = 0f,
            refraction = 60f,
            bevel = 20f,
            saturation = 1.925f,
            brightness = 1f,
            rim = 0.35f,
            rimWidth = 1.3f,
        ) else LiquidGlassStyle(
            tint = Color(0x93F4F4F4),
            contrast = 0.4f,
            blur = 0f,
            refraction = 60f,
            bevel = 20f,
            saturation = 2.1f,
            brightness = 1f,
            rim = 0.6f,
            rimWidth = 1.3f,
            hairline = 0.35f,
        )

        /**
         * dart:156 `regular` —— 底栏胶囊本体的玻璃。
         * 只用它的 rim（0.28）/ rimWidth（1.2）/ rimTail（0.17）/ rimFocus（1）/
         * rimAdded（0.38）/ hairline（0.25）/ hairlineSide（0.75）来描述外圈：
         * 上沿是一道 1.2dp 的柔白亮边（两端 fade），侧面是一道更重的暗线。
         */
        fun regular(dark: Boolean) = LiquidGlassStyle(
            tint = if (dark) Color(0x9E1A1A1A) else Color(0x8AFDFDFD),
            blur = 0f,
            refraction = 0f,
            bevel = 20f,
            saturation = if (dark) 2.84f else 2.08f,
            rim = if (dark) 0.28f else 0.28f,
            rimWidth = 1.2f,
            rimTail = 0.17f,
            rimFocus = 1f,
            rimAdded = 0.38f,
            hairline = if (dark) 0f else 0.25f,
            hairlineSide = if (dark) 0.75f else 0.75f,
        )
    }
}

/**
 * 把 style + 几何写进 RuntimeShader。dp → px 只在这里做一次。
 * [density] 是 LocalDensity.density（px per dp）。
 */
object PrismLiquidGlass {
    fun applyTo(
        shader: RuntimeShader,
        style: LiquidGlassStyle,
        widthPx: Float,
        heightPx: Float,
        density: Float,
        padPx: Float = 0f,
    ) {
        shader.setFloatUniform("uSize", widthPx, heightPx)
        shader.setFloatUniform("uPad", padPx)
        shader.setFloatUniform("uRadius", minOf(widthPx, heightPx) * 0.5f)
        shader.setFloatUniform("uBevel", style.bevel * density)
        shader.setFloatUniform(
            "uOptics",
            style.refraction * density,
            style.saturation,
            style.brightness,
        )
        shader.setFloatUniform(
            "uTint",
            style.tint.red, style.tint.green, style.tint.blue, style.tint.alpha,
        )
        shader.setFloatUniform("uLine", style.hairline)
        shader.setFloatUniform("uLinePx", 0.67f * density)
        shader.setFloatUniform("uDispersion", style.dispersion * density)
        shader.setFloatUniform("uBlurPx", style.blur * density)
        shader.setFloatUniform("uRimFrostPx", style.rimBlur * density)
        shader.setFloatUniform(
            "uLight",
            style.rim,
            style.rimWidth * density,
            style.endShade,
        )
        shader.setFloatUniform(
            "uRim",
            style.rimTail, style.rimFocus, style.rimAdded, style.sideLine,
        )
        shader.setFloatUniform("uRimTailPx", 4.5f * density)
        shader.setFloatUniform("uPartHoldPx", 7f * density)
        shader.setFloatUniform("uPartReachPx", 10f * density)
        shader.setFloatUniform("uContrast", style.contrast)
    }
}
