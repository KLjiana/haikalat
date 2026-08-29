package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.core.mesh.Bounds3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * JVM-only evidence for the sparse planner contract. The benchmark deliberately
 * uses the real Scene -> SceneFrameBuilder -> ShadowCasterPlanner path so queue
 * reuse and changedIndices cannot be fabricated by its fixture.
 */
public final class ShadowPlannerEvidence {
    private static final int ENTRY_COUNT = 10_000;
    private static final int CASCADES = 4;
    private static final int POINTS = 2;
    private static final int SPOTS = 4;
    private static final int VIEW_COUNT = CASCADES + POINTS * PointShadowAtlas.FACE_COUNT + SPOTS;

    private ShadowPlannerEvidence() {
    }

    public static void run(List<SceneObject> templates) {
        if (templates == null) throw new IllegalArgumentException("templates");
        SceneObject template = templates.stream()
                .filter(object -> nonDegenerate(object.mesh().localBounds()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "benchmark requires one finite, non-degenerate mesh"));
        Bounds3f localBounds = template.mesh().localBounds();
        float centerX = (localBounds.minX() + localBounds.maxX()) * 0.5f;
        float centerY = (localBounds.minY() + localBounds.maxY()) * 0.5f;
        float centerZ = (localBounds.minZ() + localBounds.maxZ()) * 0.5f;
        float largestExtent = Math.max(localBounds.maxX() - localBounds.minX(),
                Math.max(localBounds.maxY() - localBounds.minY(),
                        localBounds.maxZ() - localBounds.minZ()));
        float scale = largestExtent > 0.0f ? 0.5f / largestExtent : 1.0f;
        AtomicLong dynamicRevision = new AtomicLong(1L);
        float[] dynamicX = {0.0f};
        SceneObject dynamic = SceneObject.revisioned(template.mesh(), template.material(),
                (model, frame) -> model.identity()
                        .translate(dynamicX[0] - centerX * scale,
                                -centerY * scale, -centerZ * scale)
                        .scale(scale),
                dynamicRevision::get, true, template.drawBinding());
        SceneObject fixed = SceneObject.fixed(template.mesh(), template.material(),
                new Matrix4f(), true, template.drawBinding());

        Camera camera = new Camera(new Vector3f(0.0f, 0.0f, 5.0f));
        Scene scene = new Scene(camera)
                .addLight(SceneLight.shadowedDirectional(
                        new Vector3f(-0.4f, -1.0f, -0.3f), new Vector3f(1.0f), 1.0f));
        for (int slot = 0; slot < POINTS; slot++) {
            scene.addLight(SceneLight.shadowedPoint(new Vector3f(),
                    new Vector3f(1.0f), 50.0f, 16.0f));
        }
        for (int slot = 0; slot < SPOTS; slot++) {
            scene.addLight(SceneLight.shadowedSpot(new Vector3f(),
                    new Vector3f(0.0f, -1.0f, 0.0f), new Vector3f(1.0f),
                    50.0f, 16.0f, 0.16f, 0.58f));
        }
        scene.add(dynamic);
        for (int index = 1; index < ENTRY_COUNT; index++) scene.add(fixed);

        ShadowFramePlan shadowFramePlan = shadowFramePlan(scene.lightEntries());
        SceneFrameBuilder frameBuilder = new SceneFrameBuilder();
        ShadowCasterPlanner planner = new ShadowCasterPlanner();
        Matrix4f legacyShadowMatrix = new Matrix4f();

        RenderFrameContext firstContext = context(scene, camera, 0, 1L, null);
        SceneFrame firstFrame = frameBuilder.build(scene, camera, 1280, 720,
                legacyShadowMatrix, true, true, false, 0.0f, 0);
        ShadowCasterPlanner.ShadowCasterPlan firstPlan = planner.plan(
                shadowFramePlan, firstContext, firstFrame, true, null);
        int staticTests = firstPlan.statistics().casterViewTests();
        int staticReferences = firstPlan.statistics().casterViewReferences();
        int[] firstOrder = casterOrder(firstPlan, 0);
        require(staticTests == ENTRY_COUNT * VIEW_COUNT,
                "initial full plan must test every caster/view pair");

        dynamicX[0] = 100.0f;
        dynamicRevision.incrementAndGet();
        RenderFrameContext outsideContext = context(scene, camera, 1, 2L, firstContext);
        SceneFrame outsideFrame = frameBuilder.build(scene, camera, 1280, 720,
                legacyShadowMatrix, true, true, false, 0.0f, 1);
        require(outsideFrame.changedCount == 1,
                "SceneFrameBuilder must publish exactly one changed caster");
        require(outsideFrame.statistics.shadowQueueReused(),
                "unculled shared shadow queue must survive a bounds-only change");
        ShadowCasterPlanner.ShadowCasterPlan outsidePlan = planner.plan(
                shadowFramePlan, outsideContext, outsideFrame, true, null);
        int dynamicTests = outsidePlan.statistics().casterViewTests();
        boolean dynamicFull = outsidePlan.fullRebuild();
        int dynamicReferences = outsidePlan.statistics().casterViewReferences();
        require(!dynamicFull, "one revisioned caster must stay on the sparse path");
        require(dynamicTests == VIEW_COUNT,
                "one changed caster must perform exactly one test per active view");
        require(casterOrder(outsidePlan, 0).length == firstOrder.length - 1,
                "caster leaving the view must remove one reference");

        dynamicX[0] = 0.0f;
        dynamicRevision.incrementAndGet();
        RenderFrameContext restoredContext = context(scene, camera, 2, 3L, outsideContext);
        SceneFrame restoredFrame = frameBuilder.build(scene, camera, 1280, 720,
                legacyShadowMatrix, true, true, false, 0.0f, 2);
        require(restoredFrame.changedCount == 1 && restoredFrame.statistics.shadowQueueReused(),
                "restored caster must retain the real sparse queue frontier");
        ShadowCasterPlanner.ShadowCasterPlan restoredPlan = planner.plan(
                shadowFramePlan, restoredContext, restoredFrame, true, null);
        require(!restoredPlan.fullRebuild()
                        && restoredPlan.statistics().casterViewTests() == VIEW_COUNT,
                "restored caster must remain a twenty-test sparse update");
        require(java.util.Arrays.equals(firstOrder, casterOrder(restoredPlan, 0)),
                "leave/re-enter must preserve deterministic candidate order");

        restoredFrame.forceShadowPlanRebuild = true;
        ShadowCasterPlanner.ShadowCasterPlan forcedPlan = planner.plan(
                shadowFramePlan, restoredContext, restoredFrame, true, null);
        require(forcedPlan.fullRebuild(), "explicit rebuild barrier must remain effective");
        require(forcedPlan.statistics().casterViewTests() == ENTRY_COUNT * VIEW_COUNT,
                "forced full plan must test every caster/view pair");

        System.out.printf("SHADOW_PLANNER_BENCHMARK entries=%d views=%d "
                        + "staticTests=%d dynamicTests=%d dynamicFull=%s "
                        + "forcedTests=%d forcedFull=%s staticReferences=%d dynamicReferences=%d "
                        + "sceneChanged=%d shadowQueueReused=%s deterministicOrder=true%n",
                ENTRY_COUNT, VIEW_COUNT, staticTests, dynamicTests, dynamicFull,
                forcedPlan.statistics().casterViewTests(), forcedPlan.fullRebuild(),
                staticReferences, dynamicReferences, outsideFrame.changedCount,
                outsideFrame.statistics.shadowQueueReused());
    }

    private static RenderFrameContext context(Scene scene, Camera camera, int frameIndex,
                                              long sequence, RenderFrameContext previous) {
        return RenderFrameContext.capture(scene, camera, 1280, 720,
                1.0f / 60.0f, frameIndex, sequence, 0L, previous);
    }

    private static ShadowFramePlan shadowFramePlan(List<SceneLightEntry> entries) {
        SceneLightEntry directional = entries.get(0);
        List<Matrix4f> matrices = List.of(new Matrix4f(), new Matrix4f(),
                new Matrix4f(), new Matrix4f());
        List<ShadowTileRect> tiles = List.of(
                new ShadowTileRect(0, 0, 16, 16, 0.0f, 0.0f, 0.25f, 0.25f),
                new ShadowTileRect(16, 0, 16, 16, 0.25f, 0.0f, 0.5f, 0.25f),
                new ShadowTileRect(0, 16, 16, 16, 0.0f, 0.25f, 0.25f, 0.5f),
                new ShadowTileRect(16, 16, 16, 16, 0.25f, 0.25f, 0.5f, 0.5f));
        List<PointShadowSlotPlan> points = new ArrayList<>();
        for (int slot = 0; slot < POINTS; slot++) {
            points.add(new PointShadowSlotPlan(entries.get(1 + slot), slot, slot, 1.0f,
                    matrices6(), tiles6(), true, true,
                    ShadowFramePlan.MissReason.NEW_ALLOCATION));
        }
        List<SpotShadowSlotPlan> spots = new ArrayList<>();
        for (int slot = 0; slot < SPOTS; slot++) {
            spots.add(new SpotShadowSlotPlan(entries.get(1 + POINTS + slot), slot, slot, 1.0f,
                    new Matrix4f(), tiles6().get(0), true, true,
                    ShadowFramePlan.MissReason.NEW_ALLOCATION));
        }
        return new ShadowFramePlan(
                java.util.Optional.of(new ShadowFramePlan.DirectionalPlan(directional, 0, 1.0f,
                        matrices, new float[]{0.25f, 0.5f, 0.75f, 1.0f},
                        new float[]{1.0f, 1.0f, 1.0f, 1.0f}, tiles,
                        List.of(true, true, true, true),
                        List.of(ShadowFramePlan.MissReason.NEW_ALLOCATION,
                                ShadowFramePlan.MissReason.NEW_ALLOCATION,
                                ShadowFramePlan.MissReason.NEW_ALLOCATION,
                                ShadowFramePlan.MissReason.NEW_ALLOCATION))),
                points, spots, List.of(), 1, POINTS, SPOTS,
                ShadowFilterMode.PCF_3X3, POINTS, SPOTS);
    }

    private static int[] casterOrder(ShadowCasterPlanner.ShadowCasterPlan plan, int view) {
        int[] result = new int[plan.count(view)];
        int rank = plan.firstCandidateRank(view);
        for (int index = 0; index < result.length; index++) {
            require(rank >= 0, "view membership ended before its published count");
            result[index] = plan.casterAtRank(rank);
            rank = plan.nextCandidateRank(view, rank);
        }
        require(rank < 0, "view membership exceeds its published count");
        return result;
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

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static boolean nonDegenerate(Bounds3f bounds) {
        return bounds.isFinite()
                && bounds.minX() < bounds.maxX()
                && bounds.minY() < bounds.maxY()
                && bounds.minZ() < bounds.maxZ();
    }
}
