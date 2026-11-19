package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.subsystems.render3d.volumetric.VolumetricParticleSettings;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contract for the bounded volumetric-particle configuration.
 *
 * <p>Checks the disabled no-op value, defensive copies, coefficient helpers,
 * every constructor bound and the duplicate-medium guard against the existing
 * volumetric fog. The real-GL behaviour is covered separately by the guarded
 * {@code VolumetricParticleGlTest}; this class never creates a GL context.</p>
 */
class VolumetricParticleSettingsTest {
    private static BuilderValues values() {
        return new BuilderValues();
    }

    private static VolumetricParticleSettings settings() {
        return values().build();
    }

    @Test
    void disabledIsAnExactNoOpValueAndCanBeReenabledWithoutRebuilding() {
        VolumetricParticleSettings disabled = VolumetricParticleSettings.disabled();
        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertEquals(1.0f, disabled.fogWeight(), 0.0f);
        assertEquals(1.0f, disabled.maximumScreenCoverage(), 0.0f);
        VolumetricParticleSettings enabled = disabled.withEnabled(true);
        assertTrue(enabled.active());
        assertEquals(disabled.boundsCenter(), enabled.boundsCenter());
        assertEquals(disabled.lightDirection(), enabled.lightDirection());
        assertEquals(disabled.maximumSteps(), enabled.maximumSteps());
        assertFalse(enabled.withEnabled(false).active());
        assertEquals(disabled, disabled.withEnabled(false));
    }

    @Test
    void vectorComponentsReturnDefensiveCopies() {
        VolumetricParticleSettings settings = settings();
        settings.boundsCenter().set(99.0f);
        settings.boundsHalfExtent().set(99.0f);
        settings.lightDirection().set(0.0f, 0.0f, 99.0f);
        settings.lightColor().set(99.0f);
        assertEquals(new Vector3f(1.0f, 2.0f, -3.0f), settings.boundsCenter());
        assertEquals(new Vector3f(1.5f, 2.5f, 3.5f), settings.boundsHalfExtent());
        assertEquals(1.0f, settings.lightDirection().length(), 1.0e-6f);
        assertEquals(new Vector3f(3.5f, 3.2f, 3.0f), settings.lightColor());
    }

    @Test
    void lightDirectionIsNormalizedAndCoefficientHelpersAreConsistent() {
        VolumetricParticleSettings settings = settings();
        assertEquals(1.0f, settings.lightDirection().length(), 1.0e-6f);
        assertEquals(4.0f * (0.2f + 0.8f), settings.extinction(), 1.0e-6f);
        assertEquals(0.8f, settings.scatteringAlbedo(), 1.0e-6f);

        VolumetricParticleSettings vacuum = new VolumetricParticleSettings(true, 0.0f, 0.0f, 0.0f,
                new Vector3f(), new Vector3f(1.0f), 8, 0.1f, new Vector3f(0.0f, -1.0f, 0.0f),
                new Vector3f(1.0f), 0.0f, VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
        assertEquals(0.0f, vacuum.extinction(), 0.0f);
        assertEquals(0.0f, vacuum.scatteringAlbedo(), 0.0f, "zero coefficients have no albedo");

        VolumetricParticleSettings pureScattering = new VolumetricParticleSettings(true, 2.0f,
                0.0f, 3.0f, new Vector3f(), new Vector3f(1.0f), 8, 0.1f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.0f,
                VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
        assertEquals(6.0f, pureScattering.extinction(), 1.0e-6f);
        assertEquals(1.0f, pureScattering.scatteringAlbedo(), 0.0f);
    }

    @Test
    void everyComponentIsValidatedAtConstruction() {
        assertRejected(values().density(-0.1f));
        assertRejected(values().density(Float.NaN));
        assertRejected(values().density(65.0f));
        assertRejected(values().absorption(-0.1f));
        assertRejected(values().absorption(Float.POSITIVE_INFINITY));
        assertRejected(values().scattering(65.0f));
        assertRejected(values().scattering(Float.NaN));
        assertRejected(values().boundsCenter(new Vector3f(Float.NaN, 0.0f, 0.0f)));
        assertRejected(values().boundsCenter(new Vector3f(100_001.0f, 0.0f, 0.0f)));
        assertRejected(values().boundsHalfExtent(new Vector3f(0.0f, 1.0f, 1.0f)));
        assertRejected(values().boundsHalfExtent(new Vector3f(1.0f, 1.0e-4f, 1.0f)));
        assertRejected(values().boundsHalfExtent(new Vector3f(1.0f, 1.0f, 10_001.0f)));
        assertRejected(values().maximumSteps(0));
        assertRejected(values().maximumSteps(65));
        assertRejected(values().stepSizeMetres(0.0f));
        assertRejected(values().stepSizeMetres(-1.0f));
        assertRejected(values().stepSizeMetres(64.1f));
        assertRejected(values().lightDirection(new Vector3f()));
        assertRejected(values().lightDirection(new Vector3f(Float.NaN, -1.0f, 0.0f)));
        assertRejected(values().lightColor(new Vector3f(-0.1f, 1.0f, 1.0f)));
        assertRejected(values().lightColor(new Vector3f(17.0f, 1.0f, 1.0f)));
        assertRejected(values().anisotropy(0.95f));
        assertRejected(values().anisotropy(-0.91f));
        assertRejected(values().fogBlend(null));
        assertRejected(values().fogWeight(-0.1f));
        assertRejected(values().fogWeight(1.1f));
        assertRejected(values().maximumScreenCoverage(0.0f));
        assertRejected(values().maximumScreenCoverage(1.1f));
        assertRejected(values().maximumScreenCoverage(Float.NaN));
        values().maximumSteps(64).stepSizeMetres(64.0f)
                .boundsHalfExtent(new Vector3f(10_000.0f)).build();
    }

    @Test
    void duplicateLocalFogMediaAreRejectedWhileDistinctMediaAreAccepted() {
        VolumetricParticleSettings settings = settings();
        LocalFogVolume duplicateBox = new LocalFogVolume(LocalFogVolume.Shape.BOX,
                new Vector3f(1.0f, 2.0f, -3.0f), new Vector3f(1.5f, 2.5f, 3.5f), 0.25f,
                new Vector3f(0.7f), new Vector3f(), 0.0f, 0.0f, 0.0f);
        assertThrows(IllegalArgumentException.class,
                () -> settings.validateFogComposition(fog(List.of(duplicateBox))),
                "the same particle body declared as a positive local fog volume is a duplicate");

        LocalFogVolume overlapping = new LocalFogVolume(LocalFogVolume.Shape.BOX,
                new Vector3f(1.0f, 2.0f, -3.0f), new Vector3f(2.5f, 2.5f, 3.5f), 0.25f,
                new Vector3f(0.7f), new Vector3f(), 0.0f, 0.0f, 0.0f);
        settings.validateFogComposition(fog(List.of(overlapping)));

        LocalFogVolume transparent = new LocalFogVolume(LocalFogVolume.Shape.BOX,
                new Vector3f(1.0f, 2.0f, -3.0f), new Vector3f(1.5f, 2.5f, 3.5f), 0.0f,
                new Vector3f(0.7f), new Vector3f(), 0.0f, 0.0f, 0.0f);
        settings.validateFogComposition(fog(List.of(transparent)));

        assertThrows(IllegalArgumentException.class,
                () -> settings.validateFogComposition(null));

        VolumetricFogSettings disabledFog = new VolumetricFogSettings(false, 64.0f,
                VolumetricFogSettings.Quality.BALANCED, FogMediumSettings.vacuum(),
                List.of(duplicateBox), 0.0f, false, 0.0f, 1337, new Vector3f());
        settings.validateFogComposition(disabledFog);

        VolumetricParticleSettings cubic = new VolumetricParticleSettings(true, 1.0f, 0.2f, 0.8f,
                new Vector3f(), new Vector3f(2.0f), 16, 0.2f,
                new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f), 0.0f,
                VolumetricParticleSettings.FogBlend.OCCLUDING, 1.0f, 1.0f);
        LocalFogVolume sameRadiusSphere = LocalFogVolume.sphere(new Vector3f(), 2.0f, 0.3f,
                new Vector3f(0.7f));
        assertThrows(IllegalArgumentException.class,
                () -> cubic.validateFogComposition(fog(List.of(sameRadiusSphere))),
                "a sphere with the same center and radius is the same body");
        LocalFogVolume otherRadiusSphere = LocalFogVolume.sphere(new Vector3f(), 2.5f, 0.3f,
                new Vector3f(0.7f));
        cubic.validateFogComposition(fog(List.of(otherRadiusSphere)));
    }

    private static VolumetricFogSettings fog(List<LocalFogVolume> volumes) {
        return new VolumetricFogSettings(true, 64.0f, VolumetricFogSettings.Quality.BALANCED,
                FogMediumSettings.vacuum(), volumes, 0.0f, false, 0.0f, 1337, new Vector3f());
    }

    private static void assertRejected(BuilderValues values) {
        assertThrows(IllegalArgumentException.class, values::build,
                "an out-of-range component must fail at construction");
    }

    /** Mutable fixture mirroring the record components and their valid defaults. */
    private static final class BuilderValues {
        private boolean enabled = true;
        private float density = 4.0f;
        private float absorption = 0.2f;
        private float scattering = 0.8f;
        private Vector3f center = new Vector3f(1.0f, 2.0f, -3.0f);
        private Vector3f halfExtent = new Vector3f(1.5f, 2.5f, 3.5f);
        private int maximumSteps = 32;
        private float stepSize = 0.1f;
        private Vector3f lightDirection = new Vector3f(0.3f, -1.0f, 0.2f);
        private Vector3f lightColor = new Vector3f(3.5f, 3.2f, 3.0f);
        private float anisotropy = 0.2f;
        private VolumetricParticleSettings.FogBlend fogBlend =
                VolumetricParticleSettings.FogBlend.OCCLUDING;
        private float fogWeight = 1.0f;
        private float maximumScreenCoverage = 1.0f;

        BuilderValues density(float value) {
            density = value;
            return this;
        }

        BuilderValues absorption(float value) {
            absorption = value;
            return this;
        }

        BuilderValues scattering(float value) {
            scattering = value;
            return this;
        }

        BuilderValues boundsCenter(Vector3f value) {
            center = value;
            return this;
        }

        BuilderValues boundsHalfExtent(Vector3f value) {
            halfExtent = value;
            return this;
        }

        BuilderValues maximumSteps(int value) {
            maximumSteps = value;
            return this;
        }

        BuilderValues stepSizeMetres(float value) {
            stepSize = value;
            return this;
        }

        BuilderValues lightDirection(Vector3f value) {
            lightDirection = value;
            return this;
        }

        BuilderValues lightColor(Vector3f value) {
            lightColor = value;
            return this;
        }

        BuilderValues anisotropy(float value) {
            anisotropy = value;
            return this;
        }

        BuilderValues fogBlend(VolumetricParticleSettings.FogBlend value) {
            fogBlend = value;
            return this;
        }

        BuilderValues fogWeight(float value) {
            fogWeight = value;
            return this;
        }

        BuilderValues maximumScreenCoverage(float value) {
            maximumScreenCoverage = value;
            return this;
        }

        VolumetricParticleSettings build() {
            return new VolumetricParticleSettings(enabled, density, absorption, scattering,
                    center, halfExtent, maximumSteps, stepSize, lightDirection, lightColor,
                    anisotropy, fogBlend, fogWeight, maximumScreenCoverage);
        }
    }
}
