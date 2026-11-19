package com.kaleblangley.haikalat.demo.pbr;

/**
 * Builds the real scene/settings for one station phase.  Implementations may
 * create station-local materials through {@link ExhibitionAssets} but never
 * own the window, frame driver or pipeline.
 */
@FunctionalInterface
interface ExhibitionStationBuilder {
    ExhibitionStationView build(ExhibitionStationBuildContext context, ExhibitionStation station,
                                ExhibitionPhase phase);
}
