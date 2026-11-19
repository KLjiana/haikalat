package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;

import java.util.Objects;

/**
 * One fully prepared station phase: the real scene, the complete feature
 * request that must be published at the frame boundary, and an optional
 * mutable driver for frame data that cannot live in an immutable snapshot.
 *
 * @param pipelineRebuild {@code true} when the scene changes a consumer that is
 *                        not part of the pipeline topology key (for example the
 *                        presence of a refraction material); the session then
 *                        rebuilds the pipeline generation for this phase
 */
record ExhibitionStationView(Scene scene, RenderFeatureSettings settings,
                             ExhibitionStationDriver driver, String note,
                             boolean pipelineRebuild) {
    ExhibitionStationView {
        Objects.requireNonNull(scene, "scene");
        Objects.requireNonNull(settings, "settings");
        note = note == null ? "" : note;
    }

    ExhibitionStationView(Scene scene, RenderFeatureSettings settings,
                          ExhibitionStationDriver driver, String note) {
        this(scene, settings, driver, note, false);
    }
}
