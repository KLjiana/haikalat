package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.List;
import java.util.Objects;

/**
 * Stable batch entry for Render3D feature configuration.
 *
 * <p>The control only records requests and observations.  It owns no GL
 * resource, builds no pass and performs no candidate publication itself; the
 * pipeline validates and publishes the latest request at a frame boundary.
 * Requests must be made on the pipeline's GPU owner thread.</p>
 */
public final class RenderFeaturesControl {
    public static final long INITIAL_REVISION = 0L;

    private final RenderPipeline owner;
    private RenderFeatureSettings requested;
    private RenderFeatureSettings effective;
    private long requestedRevision = INITIAL_REVISION;
    private long effectiveRevision = INITIAL_REVISION;
    private RenderFeatureStatus status = RenderFeatureStatus.PENDING;
    private String failureReason = "";
    private List<RenderFeatureActivity> activities = List.of();

    RenderFeaturesControl(RenderPipeline owner) {
        this.owner = Objects.requireNonNull(owner, "owner");
        RenderFeatureSettings defaults = RenderFeatureSettings.defaults();
        requested = defaults;
        effective = defaults;
    }

    /**
     * Submits one complete immutable snapshot.
     *
     * @return the revision assigned to this request
     * @throws IllegalStateException when called from a non-owner thread or after close
     */
    public long submit(RenderFeatureSettings settings) {
        return owner.submitFeatureSettings(settings);
    }

    public synchronized RenderFeatureSettings requested() {
        return requested;
    }

    public synchronized long requestedRevision() {
        return requestedRevision;
    }

    public synchronized RenderFeatureSettings effective() {
        return effective;
    }

    public synchronized long effectiveRevision() {
        return effectiveRevision;
    }

    public synchronized RenderFeatureStatus status() {
        return status;
    }

    public synchronized String failureReason() {
        return failureReason;
    }

    public synchronized List<RenderFeatureActivity> activities() {
        return activities;
    }

    public synchronized RenderFeatureState state() {
        return new RenderFeatureState(requested, requestedRevision, effective, effectiveRevision,
                status, failureReason, activities);
    }

    synchronized long recordRequest(RenderFeatureSettings settings) {
        requested = Objects.requireNonNull(settings, "settings");
        requestedRevision = Math.incrementExact(requestedRevision);
        status = RenderFeatureStatus.PENDING;
        failureReason = "";
        return requestedRevision;
    }

    synchronized void markPreparing() {
        status = RenderFeatureStatus.PREPARING;
    }

    synchronized boolean pending() {
        // A failed request is settled: it must not be retried implicitly on the
        // next frame. Only an explicit new submission creates new pending work.
        return status == RenderFeatureStatus.PENDING || status == RenderFeatureStatus.PREPARING;
    }

    /** Publishes a state produced by an existing compatibility apply path. */
    synchronized long publishCompatibility(RenderFeatureSettings settings,
                                           List<RenderFeatureActivity> resolved) {
        RenderFeatureSettings published = Objects.requireNonNull(settings, "settings");
        long revision = Math.incrementExact(requestedRevision);
        requested = published;
        effective = published;
        requestedRevision = revision;
        effectiveRevision = revision;
        status = RenderFeatureStatus.APPLIED;
        failureReason = "";
        activities = List.copyOf(resolved);
        return revision;
    }

    synchronized void publish(RenderFeatureSettings published, long revision,
                              List<RenderFeatureActivity> resolved) {
        publish(published, published, revision, resolved);
    }

    /**
     * Publishes a request whose effective snapshot may differ from the
     * requested one: a post-process feature that is requested but cannot run
     * in this scene stays in {@code requested} while {@code effective} carries
     * the safe baseline.
     */
    synchronized void publish(RenderFeatureSettings requestedSettings,
                              RenderFeatureSettings effectiveSettings, long revision,
                              List<RenderFeatureActivity> resolved) {
        effective = Objects.requireNonNull(effectiveSettings, "effectiveSettings");
        effectiveRevision = revision;
        requested = Objects.requireNonNull(requestedSettings, "requestedSettings");
        requestedRevision = Math.max(requestedRevision, revision);
        status = RenderFeatureStatus.APPLIED;
        failureReason = "";
        activities = List.copyOf(resolved);
    }

    synchronized void failed(String reason) {
        status = RenderFeatureStatus.FAILED;
        failureReason = reason == null || reason.isBlank() ? "feature request failed" : reason;
    }

    /** Keeps the observation coherent with configuration changed through a local apply path. */
    synchronized void refresh(RenderFeatureSettings settings,
                              List<RenderFeatureActivity> resolved) {
        RenderFeatureSettings current = Objects.requireNonNull(settings, "settings");
        if (!current.equals(effective)) {
            long revision = Math.incrementExact(requestedRevision);
            requested = current;
            requestedRevision = revision;
            effectiveRevision = revision;
        }
        effective = current;
        requested = current;
        status = RenderFeatureStatus.APPLIED;
        failureReason = "";
        activities = List.copyOf(resolved);
    }

    synchronized void closeControl() {
        status = RenderFeatureStatus.PENDING;
        activities = List.of();
    }
}
