package com.kaleblangley.haikalat.subsystems.scene;

import com.kaleblangley.haikalat.subsystems.resources.AssetId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Coalesces watcher/resource notifications into one deterministic reload
 * decision. The coordinator is CPU-only; a host can apply the decision on its
 * render thread without rebuilding unrelated graph or pipeline state.
 */
public final class SceneReloadCoordinator {
    private final Map<AssetId, EnumSet<ChangeKind>> pending = new LinkedHashMap<>();

    /** Adds one notification. Repeated notifications are merged. */
    public synchronized SceneReloadCoordinator submit(AssetId asset, ChangeKind change) {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(change, "change");
        pending.computeIfAbsent(asset, ignored -> EnumSet.noneOf(ChangeKind.class)).add(change);
        return this;
    }

    /** Adds a batch from a directory watcher and merges duplicate paths. */
    public synchronized SceneReloadCoordinator submit(
            Map<AssetId, ? extends Collection<ChangeKind>> changes) {
        Objects.requireNonNull(changes, "changes");
        changes.forEach((asset, kinds) -> {
            Objects.requireNonNull(asset, "asset");
            Objects.requireNonNull(kinds, "change kinds").forEach(kind -> submit(asset, kind));
        });
        return this;
    }

    public synchronized int pendingAssetCount() {
        return pending.size();
    }

    public synchronized boolean isEmpty() {
        return pending.isEmpty();
    }

    /**
     * Drains all currently pending notifications. The returned decision is
     * immutable and sorted by AssetId for reproducible diagnostics/tests.
     */
    public synchronized ReloadDecision drain() {
        if (pending.isEmpty()) return ReloadDecision.empty();
        List<AssetChange> changes = new ArrayList<>();
        pending.forEach((asset, kinds) -> changes.add(new AssetChange(asset, kinds)));
        pending.clear();
        changes.sort(Comparator.comparing(change -> change.asset().toString()));
        EnumSet<ReloadAction> actions = EnumSet.noneOf(ReloadAction.class);
        for (AssetChange change : changes) {
            for (ChangeKind kind : change.changes()) actions.add(kind.action());
        }
        if (actions.contains(ReloadAction.REBUILD_PIPELINE)) {
            actions.remove(ReloadAction.REPLACE_TARGET);
        }
        return new ReloadDecision(changes, actions);
    }

    public enum ChangeKind {
        GRAPH_PARAMETERS(ReloadAction.UPDATE_PARAMETERS),
        GRAPH_DEFINITION(ReloadAction.REBUILD_GRAPH),
        ANIMATION_CLIP(ReloadAction.REBUILD_CHARACTER),
        ANIMATION_LIBRARY(ReloadAction.REBUILD_CHARACTER),
        MODEL(ReloadAction.REBUILD_ASSET),
        MATERIAL(ReloadAction.REBUILD_ASSET),
        TEXTURE(ReloadAction.REBUILD_ASSET),
        OBJECT_TRANSFORM(ReloadAction.UPDATE_INSTANCE),
        PRESENTATION_TARGET(ReloadAction.REPLACE_TARGET),
        TOPOLOGY(ReloadAction.REBUILD_PIPELINE);

        private final ReloadAction action;

        ChangeKind(ReloadAction action) {
            this.action = action;
        }

        ReloadAction action() {
            return action;
        }
    }

    public enum ReloadAction {
        UPDATE_PARAMETERS,
        UPDATE_INSTANCE,
        REBUILD_GRAPH,
        REBUILD_CHARACTER,
        REBUILD_ASSET,
        REPLACE_TARGET,
        REBUILD_PIPELINE
    }

    public record AssetChange(AssetId asset, Set<ChangeKind> changes) {
        public AssetChange {
            asset = Objects.requireNonNull(asset, "asset");
            changes = Set.copyOf(Objects.requireNonNull(changes, "changes"));
            if (changes.isEmpty()) throw new IllegalArgumentException("changes must not be empty");
        }
    }

    public record ReloadDecision(List<AssetChange> changes,
                                 Set<ReloadAction> actions) {
        public ReloadDecision {
            changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
            actions = Set.copyOf(Objects.requireNonNull(actions, "actions"));
        }

        public static ReloadDecision empty() {
            return new ReloadDecision(List.of(), EnumSet.noneOf(ReloadAction.class));
        }

        public boolean requiresPipelineRebuild() {
            return actions.contains(ReloadAction.REBUILD_PIPELINE);
        }

        public boolean graphOnly() {
            return !actions.isEmpty()
                    && actions.stream().allMatch(action -> action == ReloadAction.UPDATE_PARAMETERS
                    || action == ReloadAction.REBUILD_GRAPH);
        }
    }
}
