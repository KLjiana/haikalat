package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.List;
import java.util.Objects;

/**
 * Immutable observation of the unified feature control.
 *
 * <p>{@code requested} always keeps the most recent complete request, even
 * while the master switch is off.  {@code effective} only advances after a
 * successful publication, so a failed or pending candidate cannot make the
 * reported running configuration claim success early.</p>
 */
public record RenderFeatureState(RenderFeatureSettings requested, long requestedRevision,
                                 RenderFeatureSettings effective, long effectiveRevision,
                                 RenderFeatureStatus status, String failureReason,
                                 List<RenderFeatureActivity> activities) {
    public RenderFeatureState {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(status, "status");
        failureReason = failureReason == null ? "" : failureReason;
        activities = List.copyOf(Objects.requireNonNull(activities, "activities"));
        if (requestedRevision < 0 || effectiveRevision < 0
                || effectiveRevision > requestedRevision) {
            throw new IllegalArgumentException("invalid feature revisions");
        }
    }
}
