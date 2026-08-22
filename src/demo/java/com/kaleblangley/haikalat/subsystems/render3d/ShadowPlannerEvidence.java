package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM-only evidence for the sparse planner contract.  It builds a 10k-entry
 * immutable caster queue, changes one revisioned caster, and invokes the real
 * planner without recording any draw commands.  This keeps the benchmark from
 * confusing window/driver cost with planner complexity.
 */
public final class ShadowPlannerEvidence {
    private static final int ENTRY_COUNT = 10_000;
    private static final int CASCADES = 4;
    private static final int POINTS = 2;
    private static final int SPOTS = 4;
    private static final int VIEW_COUNT = CASCADES + POINTS * PointShadowAtlas.FACE_COUNT + SPOTS;

    private ShadowPlannerEvidence() {
    }

    public static void run(SceneObject template) {
        if (template == null) throw new IllegalArgumentException("template");
        SceneObject fixed = SceneObject.fixed(template.mesh(), template.material(),
                new Matrix4f(), true, template.drawBinding());
        AtomicLong dynamicRevision = new AtomicLong(1L);
        SceneObject dynamic = SceneObject.revisioned(template.mesh(), template.material(),
                (model, frame) -> model.identity().translate(frame == 0 ? 0.0f : 0.01f, 0.0f, 0.0f),
                dynamicRevision::get, true, template.drawBinding());

        MeshRenderer[] renderers = new MeshRenderer[ENTRY_COUNT];
        Matrix4f[] models = new Matrix4f[ENTRY_COUNT];
        WorldBounds[] bounds = new WorldBounds[ENTRY_COUNT];
        boolean[] mirrored = new boolean[ENTRY_COUNT];
        int[] queues = new int[ENTRY_COUNT];
        int[] forward = new int[ENTRY_COUNT];
        int[] shadow = new int[ENTRY_COUNT];
        for (int index = 0; index < ENTRY_COUNT; index++) {
            SceneObject source = index == 0 ? dynamic : fixed;
            renderers[index] = new MeshRenderer(source.mesh(), source.material().createInstance(),
                    Transform.identity(), source.updater(), true, source.drawBinding());
            models[index] = new Matrix4f();
            bounds[index] = new WorldBounds();
            bounds[index].minX = -0.25f;
            bounds[index].minY = -0.25f;
            bounds[index].minZ = -0.25f;
            bounds[index].maxX = 0.25f;
            bounds[index].maxY = 0.25f;
            bounds[index].maxZ = 0.25f;
            queues[index] = RenderQueueClass.OPAQUE.ordinal();
            forward[index] = index;
            shadow[index] = index;
        }

        SceneFrame first = frame(renderers, models, bounds, mirrored, queues, forward, shadow,
                new int[ENTRY_COUNT], 0, false, false, false);
        SceneFrame second = frame(renderers, models, bounds, mirrored, queues, forward, shadow,
                new int[]{0}, 1, true, false, true);
        second.models[0].translate(0.01f, 0.0f, 0.0f);
        SceneFrame forced = frame(renderers, models, bounds, mirrored, queues, forward, shadow,
                new int[ENTRY_COUNT], 0, false, true, false);

        SceneLight light = SceneLight.shadowedDirectional(
                new Vector3f(-0.4f, -1.0f, -0.3f), new Vector3f(1.0f), 1.0f);
        SceneLightEntry entry = new SceneLightEntry(1L, light, ShadowLightHints.DEFAULT, 0L);
        List<Matrix4f> matrices = List.of(new Matrix4f(), new Matrix4f(),
                new Matrix4f(), new Matrix4f());
        List<ShadowTileRect> tiles = List.of(
                new ShadowTileRect(0, 0, 16, 16, 0.0f, 0.0f, 0.25f, 0.25f),
                new ShadowTileRect(16, 0, 16, 16, 0.25f, 0.0f, 0.5f, 0.25f),
                new ShadowTileRect(0, 16, 16, 16, 0.0f, 0.25f, 0.25f, 0.5f),
                new ShadowTileRect(16, 16, 16, 16, 0.25f, 0.25f, 0.5f, 0.5f));
        List<SceneLightEntry> lightEntries = new java.util.ArrayList<>();
        lightEntries.add(entry);
        List<PointShadowSlotPlan> points = new java.util.ArrayList<>();
        for (int slot = 0; slot < POINTS; slot++) {
            SceneLight point = SceneLight.shadowedPoint(new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(1.0f), 50.0f, 16.0f);
            SceneLightEntry pointEntry = new SceneLightEntry(2L + slot, point,
                    ShadowLightHints.DEFAULT, 0L);
            lightEntries.add(pointEntry);
            points.add(new PointShadowSlotPlan(pointEntry, slot, slot, 1.0f,
                    matrices6(), tiles6(), true, true, ShadowFramePlan.MissReason.NEW_ALLOCATION));
        }
        List<SpotShadowSlotPlan> spots = new java.util.ArrayList<>();
        for (int slot = 0; slot < SPOTS; slot++) {
            SceneLight spot = SceneLight.shadowedSpot(new Vector3f(0.0f, 0.0f, 0.0f),
                    new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f),
                    50.0f, 16.0f, 0.16f, 0.58f);
            SceneLightEntry spotEntry = new SceneLightEntry(4L + slot, spot,
                    ShadowLightHints.DEFAULT, 0L);
            lightEntries.add(spotEntry);
            spots.add(new SpotShadowSlotPlan(spotEntry, slot, slot, 1.0f,
                    new Matrix4f(), tiles6().get(0), true, true,
                    ShadowFramePlan.MissReason.NEW_ALLOCATION));
        }
        ShadowFramePlan plan = new ShadowFramePlan(
                java.util.Optional.of(new ShadowFramePlan.DirectionalPlan(entry, 0, 1.0f,
                        matrices, new float[]{0.25f, 0.5f, 0.75f, 1.0f},
                        new float[]{1.0f, 1.0f, 1.0f, 1.0f}, tiles,
                        List.of(true, true, true, true),
                        List.of(ShadowFramePlan.MissReason.NEW_ALLOCATION,
                                ShadowFramePlan.MissReason.NEW_ALLOCATION,
                                ShadowFramePlan.MissReason.NEW_ALLOCATION,
                                ShadowFramePlan.MissReason.NEW_ALLOCATION))),
                points, spots, List.of(), 1, POINTS, SPOTS,
                ShadowFilterMode.PCF_3X3, POINTS, SPOTS);
        ExternalCamera camera = ExternalCamera.of(new Matrix4f(), new Matrix4f(),
                new Vector3f(), 0.0f);
        SceneRevisionSnapshot revisions = new SceneRevisionSnapshot(1L, 1L, 1L, 1L, 1L, 1L, 1L);
        RenderFrameContext context = new RenderFrameContext(camera,
                lightEntries.stream().map(SceneLightEntry::light).toList(), lightEntries,
                revisions, FrameInvalidation.NONE, 1280, 720, 1.0f / 60.0f, 0, 0);
        ShadowCasterPlanner planner = new ShadowCasterPlanner();
        ShadowCasterPlanner.ShadowCasterPlan firstPlan = planner.plan(plan, context, first, true, null);
        int staticTests = firstPlan.statistics().casterViewTests();
        int staticReferences = firstPlan.statistics().casterViewReferences();
        dynamicRevision.incrementAndGet();
        ShadowCasterPlanner.ShadowCasterPlan secondPlan = planner.plan(plan, context, second, true, null);
        int dynamicTests = secondPlan.statistics().casterViewTests();
        boolean dynamicFull = secondPlan.fullRebuild();
        int dynamicReferences = secondPlan.statistics().casterViewReferences();
        ShadowCasterPlanner.ShadowCasterPlan forcedPlan = planner.plan(plan, context, forced, true, null);
        System.out.printf("SHADOW_PLANNER_BENCHMARK entries=%d views=%d "
                        + "staticTests=%d dynamicTests=%d dynamicFull=%s "
                        + "forcedTests=%d forcedFull=%s staticReferences=%d dynamicReferences=%d%n",
                ENTRY_COUNT, VIEW_COUNT, staticTests,
                dynamicTests, dynamicFull, forcedPlan.statistics().casterViewTests(),
                forcedPlan.fullRebuild(),
                staticReferences,
                dynamicReferences);
    }

    private static List<Matrix4f> matrices6() {
        return List.of(new Matrix4f(), new Matrix4f(), new Matrix4f(),
                new Matrix4f(), new Matrix4f(), new Matrix4f());
    }

    private static List<ShadowTileRect> tiles6() {
        return List.of(new ShadowTileRect(0, 0, 16, 16, 0.0f, 0.0f, 0.1f, 0.1f),
                new ShadowTileRect(16, 0, 16, 16, 0.1f, 0.0f, 0.2f, 0.1f),
                new ShadowTileRect(32, 0, 16, 16, 0.2f, 0.0f, 0.3f, 0.1f),
                new ShadowTileRect(0, 16, 16, 16, 0.0f, 0.1f, 0.1f, 0.2f),
                new ShadowTileRect(16, 16, 16, 16, 0.1f, 0.1f, 0.2f, 0.2f),
                new ShadowTileRect(32, 16, 16, 16, 0.2f, 0.1f, 0.3f, 0.2f));
    }

    private static SceneFrame frame(MeshRenderer[] renderers, Matrix4f[] models,
                                    WorldBounds[] bounds, boolean[] mirrored, int[] queues,
                                    int[] forward, int[] shadow, int[] changedIndices,
                                    int changedCount, boolean dynamic, boolean force,
                                    boolean shadowReused) {
        SceneFrame frame = new SceneFrame();
        frame.renderers = renderers;
        frame.models = models;
        frame.worldBounds = bounds;
        frame.mirrored = mirrored;
        frame.queueClasses = queues;
        frame.forwardIndices = forward;
        frame.shadowIndices = shadow;
        frame.forwardCount = renderers.length;
        frame.shadowCount = renderers.length;
        frame.changedIndices = changedIndices;
        frame.changedCount = changedCount;
        frame.forceShadowPlanRebuild = force;
        frame.frameIndex = 1L;
        frame.sceneRevision = 1L;
        frame.statistics = new SceneFrame.Statistics(
                true, 1L, renderers.length, renderers.length, 0,
                renderers.length, 0, renderers.length, renderers.length, 0,
                renderers.length - (dynamic ? 1 : 0), dynamic ? 1 : 0,
                0, dynamic ? 1 : 0, renderers.length, 0,
                false, true, shadowReused, !shadowReused,
                0L, 0L, 0L, 0L, 0L,
                renderers.length, 0, 0, 0, 0, 0, 0, 0, 0,
                renderers.length, changedCount, 0, 0, 0, 0L, 0);
        frame.available = true;
        return frame;
    }
}
