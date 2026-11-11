package com.kaleblangley.haikalat.subsystems.render3d.pbr;

/**
 * Independent double-precision oracle for the bounded anisotropic PBR
 * contract in {@code pbr-forward.frag}.
 *
 * <p>This test-source class deliberately does not call production helpers or
 * the shader source: it re-derives the Burley anisotropic GGX distribution and
 * SMITH visibility from the published equations, the tangent frame from the
 * documented vertex outputs, and the isotropic Schlick-GGX path used when the
 * anisotropy strength is zero. The real-GL acceptance test uses it as the
 * per-pixel oracle, and {@link AnisotropicReferenceMathTest} locks fixed
 * hand-computable samples.</p>
 *
 * <p>Vector arguments are 3-element arrays; multi-returning methods follow the
 * same convention. Implementations never clamp invalid production settings;
 * they mirror the shader's bounded evaluation after the caller has validated
 * the material contract.</p>
 */
public final class AnisotropicReferenceMath {
    /** Mirrors the shader's per-axis GGX alpha floor (0.045 squared). */
    public static final double MINIMUM_ALPHA = 0.002025;
    /** Dielectric F0 shared with the PBR shader. */
    public static final double DIELECTRIC_F0 = 0.04;

    private AnisotropicReferenceMath() {
    }

    /**
     * Builds the shader's anisotropic tangent frame.
     *
     * @param normal        shaded surface normal (normalized here)
     * @param tangent       vertex tangent, or {@code null} for a mesh without TANGENT
     * @param handedness    {@code pbr-forward.vert} sign (negative flips the bitangent)
     * @param rotationRadians in-plane rotation; 0 aligns the stretch with the tangent
     * @return {@code {t.x, t.y, t.z, b.x, b.y, b.z}}
     */
    public static double[] frame(double[] normal, double[] tangent, double handedness,
                                 double rotationRadians) {
        double[] n = normalize(require(normal, "normal"));
        double[] candidate = tangent == null ? fallbackTangent(n) : require(tangent, "tangent");
        if (!(lengthSquared(candidate) > 1.0e-8)) candidate = fallbackTangent(n);
        double[] projected = subtract(candidate, scale(n, dot(n, candidate)));
        if (!(lengthSquared(projected) > 1.0e-8)) projected = fallbackTangent(n);
        double[] t = normalize(projected);
        double sign = handedness < 0.0 ? -1.0 : 1.0;
        double[] b = scale(cross(n, t), sign);
        if (!(lengthSquared(b) > 1.0e-8)) {
            t = fallbackTangent(n);
            b = cross(n, t);
        }
        b = normalize(b);
        double c = Math.cos(rotationRadians);
        double s = Math.sin(rotationRadians);
        double[] rotatedT = add(scale(t, c), scale(b, s));
        double[] rotatedB = subtract(scale(b, c), scale(t, s));
        return new double[]{rotatedT[0], rotatedT[1], rotatedT[2],
                rotatedB[0], rotatedB[1], rotatedB[2]};
    }

    /**
     * Tangential GGX alphas along the anisotropy direction and across it.
     * Matches the shader's {@code alpha = max(roughness^2, floor)} mapping.
     */
    public static double[] axisAlphas(double roughness, double strength) {
        double alpha = Math.max(roughness * roughness, MINIMUM_ALPHA);
        return new double[]{
                Math.max(alpha * (1.0 + strength), MINIMUM_ALPHA),
                Math.max(alpha * (1.0 - strength), MINIMUM_ALPHA)};
    }

    /**
     * Burley anisotropic GGX distribution
     * {@code D = a2 * (a2 / dot(v,v))^2 / PI} with
     * {@code v = (ab*ToH, at*BoH, a2*NoH)} and {@code a2 = at*ab}.
     */
    public static double distribution(double[] normal, double[] halfVector,
                                      double[] tangent, double[] bitangent,
                                      double roughness, double strength) {
        double[] atAb = axisAlphas(roughness, strength);
        double[] n = normalize(require(normal, "normal"));
        double[] h = normalize(require(halfVector, "halfVector"));
        double nDotH = Math.max(dot(n, h), 0.0);
        double tDotH = dot(require(tangent, "tangent"), h);
        double bDotH = dot(require(bitangent, "bitangent"), h);
        double a2 = atAb[0] * atAb[1];
        double[] scaled = {atAb[1] * tDotH, atAb[0] * bDotH, a2 * nDotH};
        double lengthSquared = lengthSquared(scaled);
        if (!(lengthSquared > 1.0e-20)) return 0.0;
        double weight = a2 / lengthSquared;
        return a2 * weight * weight / Math.PI;
    }

    /**
     * Burley anisotropic Smith visibility
     * {@code V = 0.5 / (NoL*|(at*ToV, ab*BoV, NoV)| + NoV*|(at*ToL, ab*BoL, NoL)|)}.
     */
    public static double visibility(double[] normal, double[] view, double[] light,
                                    double[] tangent, double[] bitangent,
                                    double roughness, double strength) {
        double[] atAb = axisAlphas(roughness, strength);
        double[] n = normalize(require(normal, "normal"));
        double[] v = normalize(require(view, "view"));
        double[] l = normalize(require(light, "light"));
        double nDotV = Math.max(dot(n, v), 0.0);
        double nDotL = Math.max(dot(n, l), 0.0);
        double[] scaledView = {
                atAb[0] * dot(require(tangent, "tangent"), v),
                atAb[1] * dot(require(bitangent, "bitangent"), v), nDotV};
        double[] scaledLight = {
                atAb[0] * dot(tangent, l),
                atAb[1] * dot(bitangent, l), nDotL};
        double lambdaV = nDotL * Math.sqrt(lengthSquared(scaledView));
        double lambdaL = nDotV * Math.sqrt(lengthSquared(scaledLight));
        return 0.5 / Math.max(lambdaV + lambdaL, 1.0e-5);
    }

    /** Anisotropic direct specular factor {@code D * V * F} for a scalar F0. */
    public static double specularFactor(double[] normal, double[] view, double[] light,
                                        double[] tangent, double[] bitangent,
                                        double roughness, double strength, double f0) {
        double[] n = normalize(require(normal, "normal"));
        double[] v = normalize(require(view, "view"));
        double[] l = normalize(require(light, "light"));
        double[] h = normalize(add(v, l));
        double fresnel = fresnelSchlick(Math.max(dot(h, v), 0.0), f0);
        return distribution(n, h, tangent, bitangent, roughness, strength)
                * visibility(n, v, l, tangent, bitangent, roughness, strength)
                * fresnel;
    }

    /**
     * The original isotropic path that the shader must keep when the
     * anisotropy strength is zero: GGX distribution plus Schlick-GGX Smith.
     */
    public static double isotropicSpecularFactor(double[] normal, double[] view,
                                                 double[] light, double roughness,
                                                 double f0) {
        double[] n = normalize(require(normal, "normal"));
        double[] v = normalize(require(view, "view"));
        double[] l = normalize(require(light, "light"));
        double nDotV = Math.max(dot(n, v), 0.0);
        double nDotL = Math.max(dot(n, l), 0.0);
        if (nDotL <= 0.0) return 0.0;
        double[] h = normalize(add(v, l));
        double a = roughness * roughness;
        double a2 = a * a;
        double nDotH = Math.max(dot(n, h), 0.0);
        double denominator = nDotH * nDotH * (a2 - 1.0) + 1.0;
        double d = a2 / Math.max(Math.PI * denominator * denominator, 1.0e-6);
        double r = roughness + 1.0;
        double k = r * r * 0.125;
        double gv = nDotV / Math.max(nDotV * (1.0 - k) + k, 1.0e-6);
        double gl = nDotL / Math.max(nDotL * (1.0 - k) + k, 1.0e-6);
        double fresnel = fresnelSchlick(Math.max(dot(h, v), 0.0), f0);
        return d * gv * gl * fresnel;
    }

    /**
     * Full direct-light base BRDF of the anisotropic fixture material, in the
     * shader's order: {@code (kd * baseColor / PI + D*V*F) * radiance * NoL}.
     * Mirrors the PBR shader's dielectric/metallic split without calling it.
     */
    public static double[] anisotropicDirectBrdf(double[] normal, double[] view,
                                                 double[] light, double[] radiance,
                                                 double[] baseColor, double metallic,
                                                 double roughness, double strength,
                                                 double[] tangent, double[] bitangent) {
        double[] n = normalize(require(normal, "normal"));
        double[] v = normalize(require(view, "view"));
        double[] l = normalize(require(light, "light"));
        double nDotL = Math.max(dot(n, l), 0.0);
        if (nDotL <= 0.0) return new double[3];
        double[] h = normalize(add(v, l));
        double hDotV = Math.max(dot(h, v), 0.0);
        double[] f0 = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            f0[channel] = DIELECTRIC_F0 + (baseColor[channel] - DIELECTRIC_F0) * metallic;
        }
        double[] fresnel = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            fresnel[channel] = fresnelSchlick(hDotV, f0[channel]);
        }
        double specular = distribution(n, h, tangent, bitangent, roughness, strength)
                * visibility(n, v, l, tangent, bitangent, roughness, strength);
        double[] result = new double[3];
        for (int channel = 0; channel < 3; channel++) {
            double diffuse = (1.0 - fresnel[channel]) * (1.0 - metallic)
                    * baseColor[channel] / Math.PI;
            result[channel] = (diffuse + specular * fresnel[channel])
                    * radiance[channel] * nDotL;
        }
        return result;
    }

    /** Schlick Fresnel shared by the direct evaluators. */
    public static double fresnelSchlick(double cosTheta, double f0) {
        return f0 + (1.0 - f0) * Math.pow(1.0 - clamp01(cosTheta), 5.0);
    }

    private static double[] fallbackTangent(double[] n) {
        double[] reference = Math.abs(n[2]) < 0.999
                ? new double[]{0.0, 0.0, 1.0} : new double[]{0.0, 1.0, 0.0};
        double[] tangent = cross(reference, n);
        return lengthSquared(tangent) > 1.0e-12
                ? normalize(tangent) : new double[]{1.0, 0.0, 0.0};
    }

    private static double[] require(double[] value, String name) {
        if (value == null || value.length != 3) {
            throw new IllegalArgumentException(name + " must be a 3-element array");
        }
        return value;
    }

    private static double[] add(double[] a, double[] b) {
        return new double[]{a[0] + b[0], a[1] + b[1], a[2] + b[2]};
    }

    private static double[] subtract(double[] a, double[] b) {
        return new double[]{a[0] - b[0], a[1] - b[1], a[2] - b[2]};
    }

    private static double[] scale(double[] value, double factor) {
        return new double[]{value[0] * factor, value[1] * factor, value[2] * factor};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double lengthSquared(double[] value) {
        return dot(value, value);
    }

    private static double[] normalize(double[] value) {
        double length = Math.sqrt(lengthSquared(value));
        if (!(length > 1.0e-12)) throw new IllegalArgumentException("cannot normalize zero vector");
        return new double[]{value[0] / length, value[1] / length, value[2] / length};
    }

    private static double clamp01(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
