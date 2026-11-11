package com.kaleblangley.haikalat.subsystems.render3d.vfx;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import org.joml.Matrix4f;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JVM contracts for the projected-decal settings, bounded overlap ordering,
 * default-off no-op and the receiver-volume transform.
 */
class ProjectedDecalTest {
    private static final AssetRef TEXTURE =
            AssetRef.of("/vfx/decals/kenney/particle_pack/scorch_01.png");
    private static final ProjectedDecalSettings ENABLED =
            new ProjectedDecalSettings(true, 0.5f, 0.02f, 0.5f, false, true, 8);

    @Test
    void disabledSettingsAreAnExactNoOpAndTheBoundedControlsAreValidated() {
        ProjectedDecalSettings disabled = ProjectedDecalSettings.disabled();
        assertFalse(disabled.enabled());
        assertFalse(disabled.active());
        assertEquals(32, disabled.maximumDecals());

        ProjectedDecalSettings enabled = disabled.withEnabled(true);
        assertTrue(enabled.enabled());
        assertTrue(enabled.active());
        assertFalse(disabled.enabled(), "withEnabled must not mutate the shared disabled value");

        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, 0.0f, 0.0f, 0.5f, false, true, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, -1.0f, 0.0f, 0.5f, false, true, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, Float.NaN, 0.0f, 0.5f, false, true, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, ProjectedDecalSettings.MAX_THICKNESS
                        + 0.01f, 0.0f, 0.5f, false, true, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, 0.5f, -0.01f, 0.5f, false, true, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, 0.5f, 0.6f, 0.5f, false, true, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, 0.5f, 0.0f, -0.01f, false, true, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, 0.5f, 0.0f, 1.01f, false, true, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, 0.5f, 0.0f, Float.NaN, false, true, 8));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, 0.5f, 0.0f, 0.5f, false, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ProjectedDecalSettings(true, 0.5f, 0.0f, 0.5f, false, true,
                        ProjectedDecalSettings.MAX_DECALS + 1));
    }

    @Test
    void planDropsEverythingWhenDisabledEmptyTransparentOrBehindCamera() {
        Vector3f camera = new Vector3f();
        Matrix4f view = new Matrix4f();
        ProjectedDecalPlan disabled = ProjectedDecalPlan.build(
                ProjectedDecalSettings.disabled(), List.of(decal(1, 0.0f, 0.0f, -1.0f, 1.0f)),
                view, camera);
        assertTrue(disabled.isEmpty());
        assertEquals(0, disabled.size());
        assertEquals(1, disabled.submitted());
        assertEquals(1, disabled.rejected());

        ProjectedDecalPlan empty = ProjectedDecalPlan.build(ENABLED, List.of(), view, camera);
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.submitted());
        assertEquals(0, empty.rejected());

        ProjectedDecalPlan transparent = ProjectedDecalPlan.build(ENABLED,
                List.of(decal(1, 0.0f, 0.0f, -1.0f, 0.0f)), view, camera);
        assertTrue(transparent.isEmpty());
        assertEquals(1, transparent.submitted());
        assertEquals(1, transparent.rejected());

        ProjectedDecalPlan behind = ProjectedDecalPlan.build(ENABLED,
                List.of(decal(1, 0.0f, 0.0f, 1.0f, 1.0f)), view, camera);
        assertTrue(behind.isEmpty(),
                "an anchor behind the camera plane is conservatively dropped");
        assertEquals(1, behind.submitted());
        assertEquals(1, behind.rejected());
    }

    @Test
    void planOrdersOverlapsFarToNearAndKeepsTheNearestBoundedSet() {
        Vector3f camera = new Vector3f();
        Matrix4f view = new Matrix4f();
        List<ProjectedDecal> decals = List.of(
                decal(30, 0.0f, 0.0f, -3.0f, 1.0f),
                decal(10, 0.0f, 0.0f, -1.0f, 1.0f),
                decal(20, 0.0f, 0.0f, -2.0f, 1.0f));

        ProjectedDecalSettings nearestTwo =
                new ProjectedDecalSettings(true, 0.5f, 0.02f, 0.5f, false, true, 2);
        ProjectedDecalPlan bounded = ProjectedDecalPlan.build(nearestTwo, decals, view, camera);
        assertEquals(2, bounded.size());
        assertEquals(3, bounded.submitted());
        assertEquals(1, bounded.rejected());
        assertEquals(20L, bounded.decal(0).sequence(), "far decal of the kept pair draws first");
        assertEquals(10L, bounded.decal(1).sequence(), "nearest overlap draws last");

        ProjectedDecalPlan all = ProjectedDecalPlan.build(ENABLED, decals, view, camera);
        assertEquals(3, all.size());
        assertEquals(List.of(30L, 20L, 10L),
                List.of(all.decal(0).sequence(), all.decal(1).sequence(),
                        all.decal(2).sequence()),
                "overlap order must be far-to-near");

        ProjectedDecalPlan tied = ProjectedDecalPlan.build(ENABLED, List.of(
                decal(7, 0.0f, 0.0f, -1.0f, 1.0f),
                decal(3, 0.0f, 0.0f, -1.0f, 1.0f)), view, camera);
        assertEquals(3L, tied.decal(0).sequence());
        assertEquals(7L, tied.decal(1).sequence(),
                "equal camera distances must fall back to ascending sequence");
    }

    @Test
    void modelMapsTheLocalVolumeOntoTheReceiverDepthContract() {
        ProjectedDecal decal = new ProjectedDecal(1L, TEXTURE,
                new Vector3f(1.0f, 2.0f, 3.0f),
                new Vector3f(0.0f, 0.0f, 2.0f),
                new Vector3f(0.0f, 3.0f, 0.0f),
                new Vector2f(2.0f, 4.0f),
                new Vector4f(1.0f, 0.5f, 0.25f, 1.0f), 1.0f);
        ProjectedDecalSettings settings =
                new ProjectedDecalSettings(true, 0.5f, 0.1f, 0.5f, false, true, 8);
        Matrix4f model = new Matrix4f();
        VfxRenderer.projectedDecalModel(decal, settings, model);

        assertEquals(0.0f, decal.normal().length() - 1.0f, 1.0e-6f);
        assertEquals(0.0f, decal.up().length() - 1.0f, 1.0e-6f);
        assertEquals(0.0f, decal.normal().dot(decal.up()), 1.0e-6f);

        Vector3f front = model.transformPosition(new Vector3f(0.0f, 0.0f, 0.0f));
        assertEquals(1.0f, front.x, 1.0e-6f);
        assertEquals(2.0f, front.y, 1.0e-6f);
        assertEquals(3.1f, front.z, 1.0e-6f, "local z=0 is +surfaceOffset in front");

        Vector3f back = model.transformPosition(new Vector3f(0.0f, 0.0f, 1.0f));
        assertEquals(2.5f, back.z, 1.0e-6f, "local z=1 is -thickness behind");

        Vector3f corner = model.transformPosition(new Vector3f(0.5f, 0.5f, 0.0f));
        assertEquals(2.0f, corner.x, 1.0e-6f);
        assertEquals(4.0f, corner.y, 1.0e-6f);

        Vector3f roundTrip = new Matrix4f(model).invert()
                .transformPosition(new Vector3f(corner));
        assertEquals(0.5f, roundTrip.x, 1.0e-5f);
        assertEquals(0.5f, roundTrip.y, 1.0e-5f);
        assertEquals(0.0f, roundTrip.z, 1.0e-5f);
    }

    @Test
    void rejectsDegenerateOrientationColourAndEmissiveValues() {
        assertThrows(IllegalArgumentException.class, () -> new ProjectedDecal(1L, TEXTURE,
                new Vector3f(), new Vector3f(0.0f, 0.0f, 1.0f),
                new Vector3f(0.0f, 0.0f, 2.0f), new Vector2f(1.0f, 1.0f),
                new Vector4f(1.0f), 1.0f), "parallel up must be rejected");
        assertThrows(IllegalArgumentException.class, () -> new ProjectedDecal(1L, TEXTURE,
                new Vector3f(), new Vector3f(), new Vector3f(0.0f, 1.0f, 0.0f),
                new Vector2f(1.0f, 1.0f), new Vector4f(1.0f), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new ProjectedDecal(1L, TEXTURE,
                new Vector3f(), new Vector3f(0.0f, 0.0f, 1.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), new Vector2f(0.0f, 1.0f),
                new Vector4f(1.0f), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new ProjectedDecal(1L, TEXTURE,
                new Vector3f(), new Vector3f(0.0f, 0.0f, 1.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), new Vector2f(1.0f, 1.0f),
                new Vector4f(1.0f, 1.0f, 1.0f, 1.5f), 1.0f));
        assertThrows(IllegalArgumentException.class, () -> new ProjectedDecal(1L, TEXTURE,
                new Vector3f(), new Vector3f(0.0f, 0.0f, 1.0f),
                new Vector3f(0.0f, 1.0f, 0.0f), new Vector2f(1.0f, 1.0f),
                new Vector4f(1.0f), ProjectedDecal.MAX_EMISSIVE + 0.01f));

        ProjectedDecal decal = decal(1, 0.0f, 0.0f, 0.0f, 1.0f);
        Vector4f detached = decal.color();
        detached.set(0.0f, 0.0f, 0.0f, 0.0f);
        assertEquals(1.0f, decal.color().x, 0.0f, "accessors must expose copies");
    }

    private static ProjectedDecal decal(long sequence, float x, float y, float z,
                                        float opacity) {
        return new ProjectedDecal(sequence, TEXTURE, new Vector3f(x, y, z),
                new Vector3f(0.0f, 0.0f, 1.0f), new Vector3f(0.0f, 1.0f, 0.0f),
                new Vector2f(1.0f, 1.0f), new Vector4f(1.0f, 0.5f, 0.25f, opacity), 1.0f);
    }
}
