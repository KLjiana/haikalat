package com.kaleblangley.haikalat.subsystems.render3d.pbr;

/** 与 GLSL 安全下限一致的纯 JVM BRDF 测试参考，不参与逐像素生产计算。 */
final class PbrBrdfMath {
    static final float DIELECTRIC_F0 = 0.04f;
    private static final float PI = (float) Math.PI;

    private PbrBrdfMath() {
    }

    static float diffuseWeight(float metallic, float fresnel) {
        return (1.0f - clamp01(fresnel)) * (1.0f - clamp01(metallic));
    }

    static float distributionGgx(float nDotH, float roughness) {
        float safeRoughness = Math.max(0.045f, clamp01(roughness));
        float a = safeRoughness * safeRoughness;
        float a2 = a * a;
        float nh = clamp01(nDotH);
        float denominator = nh * nh * (a2 - 1.0f) + 1.0f;
        return a2 / Math.max(PI * denominator * denominator, 1.0e-6f);
    }

    static float directScalar(float nDotL, float nDotV, float nDotH,
                              float vDotH, float roughness) {
        float nl = clamp01(nDotL);
        if (nl == 0.0f) return 0.0f;
        float nv = clamp01(nDotV);
        float f = DIELECTRIC_F0 + (1.0f - DIELECTRIC_F0)
                * (float) Math.pow(1.0f - clamp01(vDotH), 5.0);
        float r = Math.max(0.045f, clamp01(roughness)) + 1.0f;
        float k = r * r * 0.125f;
        float gv = nv / Math.max(nv * (1.0f - k) + k, 1.0e-6f);
        float gl = nl / Math.max(nl * (1.0f - k) + k, 1.0e-6f);
        return distributionGgx(nDotH, roughness) * gv * gl * f
                / Math.max(4.0f * nv * nl, 1.0e-5f) * nl;
    }

    static float compose(float direct, float indirect, float emissive,
                         float shadow, float ao) {
        return direct * (1.0f - clamp01(shadow)) + indirect * clamp01(ao) + emissive;
    }

    static float rangeInverseSquareAttenuation(float distance, float range) {
        if (!Float.isFinite(distance) || distance < 0.0f) {
            throw new IllegalArgumentException("light distance must be finite and non-negative");
        }
        if (!Float.isFinite(range) || range <= 0.0f) {
            throw new IllegalArgumentException("light range must be finite and positive");
        }
        float smooth = Math.max(0.0f, Math.min(1.0f, 1.0f - distance / range));
        return smooth * smooth / Math.max(distance * distance, 1.0e-4f);
    }

    private static float clamp01(float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("BRDF input must be finite");
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
