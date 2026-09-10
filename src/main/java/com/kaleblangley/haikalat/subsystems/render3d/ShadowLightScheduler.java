package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Pure-CPU deterministic shadow budget scheduler and projection planner.
 *
 * <p>Candidates are enumerated across every eligible scene light; the old
 * 2/8/4 shader-array limits no longer reject lights.  Each selected candidate
 * is addressed by its frameLightIndex in the unified light table.</p>
 */
final class ShadowLightScheduler {
    private final ShadowAtlasAllocator directionalAllocator = new ShadowAtlasAllocator(1);
    private final ShadowAtlasAllocator pointAllocator = new ShadowAtlasAllocator(
            LocalShadowPipelineSettings.MAX_POINT_SHADOW_LIGHTS);
    private final ShadowAtlasAllocator spotAllocator = new ShadowAtlasAllocator(
            LocalShadowPipelineSettings.MAX_SPOT_SHADOW_LIGHTS);

    ShadowFramePlan plan(List<SceneLightEntry> entries, FrameLightTable lightTable,
                         ExternalCamera camera,
                         int width, int height,
                         LocalShadowPipelineSettings settings,
                         PointShadowAtlas pointAtlas, SpotShadowAtlas spotAtlas,
                         DirectionalShadowMap directionalMap,
                         DirectionalCascadeSettings cascades) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(lightTable, "lightTable");
        Objects.requireNonNull(camera, "camera");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(directionalMap, "directionalMap");
        Objects.requireNonNull(cascades, "cascades");

        CollectionResult collected = collect(entries, lightTable, camera, settings);
        Selection directionalSelection = select(collected.directional, 1,
                directionalAllocator, settings);
        Selection pointSelection = select(collected.points, settings.maxPointLights(),
                pointAllocator, settings);
        Selection spotSelection = select(collected.spots, settings.maxSpotLights(),
                spotAllocator, settings);

        List<ShadowDecision> decisions = new ArrayList<>(collected.terminalDecisions);
        appendDecisions(decisions, collected.directional, directionalSelection, 1);
        appendDecisions(decisions, collected.points, pointSelection, settings.maxPointLights());
        appendDecisions(decisions, collected.spots, spotSelection, settings.maxSpotLights());
        decisions.sort(Comparator.comparingLong(ShadowDecision::stableId));

        Optional<ShadowFramePlan.DirectionalPlan> directional = directionalSelection.selected
                .stream().findFirst().map(candidate -> directionalPlan(candidate, camera,
                        width, height, directionalMap, cascades));
        List<PointShadowSlotPlan> points = new ArrayList<>();
        if (!pointSelection.selected.isEmpty()) {
            Objects.requireNonNull(pointAtlas, "pointAtlas");
            for (Candidate candidate : pointSelection.selected) {
                int slot = pointSelection.assignment.slot(candidate.entry.stableId());
                points.add(new PointShadowSlotPlan(candidate.entry, candidate.frameLightIndex, slot,
                        candidate.score, pointAtlas.faceMatrices(candidate.entry.light()),
                        pointAtlas.faceTiles(slot),
                        pointSelection.assignment.newlyAssigned(candidate.entry.stableId()),
                        true, ShadowFramePlan.MissReason.NEW_ALLOCATION));
            }
        }
        points.sort(Comparator.comparingInt(PointShadowSlotPlan::slot));

        List<SpotShadowSlotPlan> spots = new ArrayList<>();
        if (!spotSelection.selected.isEmpty()) {
            Objects.requireNonNull(spotAtlas, "spotAtlas");
            for (Candidate candidate : spotSelection.selected) {
                int slot = spotSelection.assignment.slot(candidate.entry.stableId());
                spots.add(new SpotShadowSlotPlan(candidate.entry, candidate.frameLightIndex, slot,
                        candidate.score, spotAtlas.lightSpaceMatrix(candidate.entry.light()),
                        spotAtlas.tile(slot),
                        spotSelection.assignment.newlyAssigned(candidate.entry.stableId()),
                        true, ShadowFramePlan.MissReason.NEW_ALLOCATION));
            }
        }
        spots.sort(Comparator.comparingInt(SpotShadowSlotPlan::slot));
        return new ShadowFramePlan(directional, points, spots, decisions,
                collected.directionalCandidates, collected.pointCandidates,
                collected.spotCandidates, settings.filterMode(),
                settings.maxPointLights(), settings.maxSpotLights());
    }

    void clear() {
        directionalAllocator.clear();
        pointAllocator.clear();
        spotAllocator.clear();
    }

    private static CollectionResult collect(List<SceneLightEntry> entries,
                                            FrameLightTable lightTable,
                                            ExternalCamera camera,
                                            LocalShadowPipelineSettings settings) {
        List<Candidate> directional = new ArrayList<>();
        List<Candidate> points = new ArrayList<>();
        List<Candidate> spots = new ArrayList<>();
        List<ShadowDecision> terminal = new ArrayList<>();
        int directionalCandidates = 0;
        int pointCandidates = 0;
        int spotCandidates = 0;
        for (SceneLightEntry entry : entries) {
            SceneLight light = entry.light();
            int frameLightIndex = lightTable.frameLightIndex(entry.stableId());
            if (frameLightIndex < 0) {
                throw new IllegalStateException("shadow candidate missing from light table: "
                        + entry.stableId());
            }
            int capacity;
            List<Candidate> destination;
            switch (light.type()) {
                case DIRECTIONAL -> {
                    capacity = 1;
                    destination = directional;
                    if (light.castShadows()) directionalCandidates++;
                }
                case POINT -> {
                    capacity = settings.maxPointLights();
                    destination = points;
                    if (light.castShadows()) pointCandidates++;
                }
                case SPOT -> {
                    capacity = settings.maxSpotLights();
                    destination = spots;
                    if (light.castShadows()) spotCandidates++;
                }
                default -> throw new IllegalStateException("Unsupported light type " + light.type());
            }
            if (!light.castShadows()) continue;
            if (capacity == 0) {
                terminal.add(decision(entry, frameLightIndex,
                        ShadowDecision.Status.DISABLED_BY_SETTINGS, -1, 0.0f));
                continue;
            }
            LocalShadowSettings local = light.type() == LightType.POINT
                    ? settings.point() : settings.spot();
            if (light.type() != LightType.DIRECTIONAL && local.nearPlane() >= light.range()) {
                terminal.add(decision(entry, frameLightIndex,
                        ShadowDecision.Status.INVALID_NEAR_FAR_RANGE, -1, 0.0f));
                continue;
            }
            Influence influence = influence(light, camera, settings.selectionMode());
            if (light.intensity() == 0.0f) {
                terminal.add(decision(entry, frameLightIndex,
                        ShadowDecision.Status.OUTSIDE_CAMERA_INFLUENCE, -1, influence.score));
                continue;
            }
            destination.add(new Candidate(entry, frameLightIndex, influence.score,
                    influence.insideCamera));
        }
        return new CollectionResult(directional, points, spots, terminal,
                directionalCandidates, pointCandidates, spotCandidates);
    }

    private static Selection select(List<Candidate> candidates, int capacity,
                                    ShadowAtlasAllocator allocator,
                                    LocalShadowPipelineSettings settings) {
        if (capacity == 0 || candidates.isEmpty()) {
            return new Selection(List.of(), Set.of(),
                    allocator.assign(List.of()));
        }
        Comparator<Candidate> order = comparator(settings.selectionMode());
        List<Candidate> ordered = candidates.stream().sorted(order).toList();
        List<Candidate> selected = new ArrayList<>(ordered.subList(0,
                Math.min(capacity, ordered.size())));
        Map<Long, Candidate> byId = new HashMap<>();
        for (Candidate candidate : candidates) byId.put(candidate.entry.stableId(), candidate);
        Set<Long> heldChallengers = new HashSet<>();
        for (long ownerId : allocator.owners()) {
            Candidate owner = byId.get(ownerId);
            if (owner == null || selected.contains(owner)) continue;
            Candidate challenger = selected.stream()
                    .filter(candidate -> !allocator.owners().contains(candidate.entry.stableId()))
                    .max(order).orElse(null);
            if (challenger == null) continue;
            boolean retain = owner.entry.hints().priority() > challenger.entry.hints().priority()
                    || owner.entry.hints().priority() == challenger.entry.hints().priority()
                    && challenger.score < owner.score * settings.replacementThreshold();
            if (retain) {
                selected.remove(challenger);
                selected.add(owner);
                heldChallengers.add(challenger.entry.stableId());
            }
        }
        selected.sort(order);
        ShadowAtlasAllocator.Assignment assignment = allocator.assign(selected.stream()
                .map(candidate -> candidate.entry.stableId()).toList());
        return new Selection(List.copyOf(selected), Set.copyOf(heldChallengers), assignment);
    }

    private static Comparator<Candidate> comparator(ShadowSelectionMode mode) {
        Comparator<Candidate> result = Comparator
                .comparingInt((Candidate value) -> value.entry.hints().priority()).reversed();
        if (mode == ShadowSelectionMode.CAMERA_IMPORTANCE) {
            result = result.thenComparing(
                    Comparator.comparingDouble((Candidate value) -> value.score).reversed());
        }
        return result.thenComparingLong(value -> value.entry.stableId());
    }

    private static void appendDecisions(List<ShadowDecision> decisions,
                                        List<Candidate> candidates, Selection selection,
                                        int capacity) {
        Set<Long> selectedIds = new HashSet<>();
        for (Candidate selected : selection.selected) selectedIds.add(selected.entry.stableId());
        int lowestPriority = selection.selected.stream()
                .mapToInt(value -> value.entry.hints().priority()).min().orElse(Integer.MIN_VALUE);
        for (Candidate candidate : candidates) {
            long id = candidate.entry.stableId();
            if (selectedIds.contains(id)) {
                decisions.add(decision(candidate.entry, candidate.frameLightIndex,
                        ShadowDecision.Status.SELECTED, selection.assignment.slot(id),
                        candidate.score));
            } else if (capacity == 0) {
                decisions.add(decision(candidate.entry, candidate.frameLightIndex,
                        ShadowDecision.Status.DISABLED_BY_SETTINGS, -1, candidate.score));
            } else if (selection.heldChallengers.contains(id)) {
                decisions.add(decision(candidate.entry, candidate.frameLightIndex,
                        ShadowDecision.Status.HELD_BY_HYSTERESIS, -1, candidate.score));
            } else if (!candidate.insideCamera) {
                decisions.add(decision(candidate.entry, candidate.frameLightIndex,
                        ShadowDecision.Status.OUTSIDE_CAMERA_INFLUENCE, -1, candidate.score));
            } else if (candidate.entry.hints().priority() < lowestPriority) {
                decisions.add(decision(candidate.entry, candidate.frameLightIndex,
                        ShadowDecision.Status.LOWER_PRIORITY, -1, candidate.score));
            } else {
                decisions.add(decision(candidate.entry, candidate.frameLightIndex,
                        ShadowDecision.Status.BUDGET_EXHAUSTED, -1, candidate.score));
            }
        }
    }

    private static ShadowFramePlan.DirectionalPlan directionalPlan(
            Candidate candidate, ExternalCamera camera, int width, int height,
            DirectionalShadowMap map, DirectionalCascadeSettings cascades) {
        List<Matrix4f> matrices;
        float[] splits;
        float[] texelSizes;
        List<ShadowTileRect> tiles = new ArrayList<>();
        if (cascades.enabled()) {
            Matrix4f projection = camera.projection();
            float verticalFov = 2.0f * (float) Math.atan(1.0f / projection.m11());
            float aspect = projection.m11() / projection.m00();
            Vector3f forward = camera.inverseView().transformDirection(
                    0.0f, 0.0f, -1.0f, new Vector3f()).normalize();
            DirectionalCascadePlan plan = DirectionalCascadePlan.create(camera.position(), forward,
                    candidate.entry.light().direction(), verticalFov, aspect,
                    camera.nearPlane(), camera.farPlane(), cascades.cascadeCount(),
                    cascades.splitLambda(), cascades.tileSize());
            matrices = plan.cascades().stream()
                    .map(DirectionalCascadePlan.Cascade::lightSpaceMatrix).toList();
            splits = new float[matrices.size()];
            texelSizes = new float[matrices.size()];
            for (int index = 0; index < matrices.size(); index++) {
                DirectionalCascadePlan.Cascade cascade = plan.cascades().get(index);
                splits[index] = cascade.farDistance();
                texelSizes[index] = cascade.texelWorldSize();
                int x = index % cascades.columns() * cascades.tileSize();
                int y = index / cascades.columns() * cascades.tileSize();
                tiles.add(tile(x, y, cascades.tileSize(), cascades.tileSize(),
                        cascades.atlasSize(), cascades.atlasSize()));
            }
        } else {
            int size = map.settings().resolution();
            matrices = List.of(map.lightSpaceMatrix(candidate.entry.light(), camera.position()));
            splits = new float[]{camera.farPlane()};
            texelSizes = new float[]{1.0f};
            tiles.add(tile(0, 0, size, size, size, size));
        }
        List<Boolean> dirty = java.util.Collections.nCopies(matrices.size(), true);
        List<ShadowFramePlan.MissReason> reasons = java.util.Collections.nCopies(
                matrices.size(), ShadowFramePlan.MissReason.NEW_ALLOCATION);
        return new ShadowFramePlan.DirectionalPlan(candidate.entry, candidate.frameLightIndex,
                candidate.score, matrices, splits, texelSizes, tiles, dirty, reasons);
    }

    private static ShadowTileRect tile(int x, int y, int width, int height,
                                       int atlasWidth, int atlasHeight) {
        return new ShadowTileRect(x, y, width, height,
                x / (float) atlasWidth, y / (float) atlasHeight,
                (x + width) / (float) atlasWidth,
                (y + height) / (float) atlasHeight);
    }

    private static Influence influence(SceneLight light, ExternalCamera camera,
                                       ShadowSelectionMode mode) {
        if (light.type() == LightType.DIRECTIONAL || mode == ShadowSelectionMode.SCENE_ORDER) {
            return new Influence(1.0f, true);
        }
        Vector3f cameraPosition = camera.position();
        float distance = Math.max(camera.nearPlane(), cameraPosition.distance(light.position()));
        float impact = Math.min(4.0f, light.range() / distance);
        boolean visible = cameraInfluence(light, camera, distance);
        float score = impact * (visible ? 1.0f : 0.1f) + (visible ? 1.0f : 0.05f);
        if (light.type() == LightType.SPOT) {
            Vector3f toCamera = cameraPosition.sub(light.position(), new Vector3f()).normalize();
            float facing = Math.max(0.0f, light.direction().dot(toCamera));
            score *= 0.25f + 0.75f * facing;
        }
        return new Influence(Math.max(0.0001f, score), visible);
    }

    private static boolean cameraInfluence(SceneLight light, ExternalCamera camera,
                                           float distance) {
        if (distance - light.range() > camera.farPlane()) return false;
        Vector4f clip = camera.viewProjection().transform(new Vector4f(light.position(), 1.0f));
        if (clip.w <= 0.0f) return distance <= light.range();
        float margin = Math.min(2.0f, light.range() / Math.max(distance, camera.nearPlane()));
        float inverseW = 1.0f / clip.w;
        float x = Math.abs(clip.x * inverseW);
        float y = Math.abs(clip.y * inverseW);
        float z = clip.z * inverseW;
        return x <= 1.0f + margin && y <= 1.0f + margin
                && z >= -1.0f - margin && z <= 1.0f + margin;
    }

    private static ShadowDecision decision(SceneLightEntry entry, int frameLightIndex,
                                           ShadowDecision.Status status, int slot, float score) {
        return new ShadowDecision(entry.stableId(), entry.light().type(), frameLightIndex, status,
                slot, entry.hints().priority(), score);
    }

    private record Candidate(SceneLightEntry entry, int frameLightIndex,
                             float score, boolean insideCamera) { }

    private record Influence(float score, boolean insideCamera) { }

    private record Selection(List<Candidate> selected, Set<Long> heldChallengers,
                             ShadowAtlasAllocator.Assignment assignment) { }

    private record CollectionResult(List<Candidate> directional, List<Candidate> points,
                                    List<Candidate> spots,
                                    List<ShadowDecision> terminalDecisions,
                                    int directionalCandidates, int pointCandidates,
                                    int spotCandidates) { }
}
