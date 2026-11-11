package com.kaleblangley.haikalat.subsystems.postprocess;

import java.util.List;

/**
 * Per-frame producer of {@link ScreenDistortion} values for the colour chain.
 *
 * <p>The colour chain queries the source once while recording the
 * {@link PostProcessTargets#SCREEN_DISTORTION_PASS} pass, after the HDR VFX
 * composite has been recorded.  The call happens on the GPU owner thread and
 * must be pure CPU data access: no GL calls, no pipeline mutation and no
 * allocation beyond the bounded frame list.  Contributions may be returned in
 * any order; the chain freezes the far-to-near composite order with
 * {@link ScreenDistortion#orderForComposite(List, int)} and truncates to the
 * configured bound.  An empty list is a valid exact copy no-op.</p>
 *
 * <p>The source instance is part of {@link ScreenDistortionSettings}, so the
 * same instance must stay associated with a pipeline generation while the
 * camera/effect state changes per frame.  Replacing the instance is a settings
 * change and rebuilds the distortion owner.</p>
 */
public interface ScreenDistortionSource {
    /**
     * @return this frame's contributions; never {@code null}, may be empty
     */
    List<ScreenDistortion> screenDistortions();
}
