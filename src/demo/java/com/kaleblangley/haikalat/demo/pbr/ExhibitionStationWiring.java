package com.kaleblangley.haikalat.demo.pbr;

import java.util.List;
import java.util.function.IntFunction;

/**
 * Maps a station id to its real scene builder.  A {@code null} result is the
 * honest "not wired yet" state: the demo shows the station card with its
 * delivery batch instead of a fake effect.
 */
final class ExhibitionStationWiring {
    private static final List<IntFunction<ExhibitionStationBuilder>> WINGS = List.of(
            ExhibitionWingAStations::builder,
            ExhibitionWingBStations::builder,
            ExhibitionWingCStations::builder,
            ExhibitionWingDStations::builder,
            ExhibitionWingEStations::builder,
            ExhibitionWingFStations::builder,
            ExhibitionWingGStations::builder,
            ExhibitionWingHStations::builder);

    private ExhibitionStationWiring() {
    }

    static ExhibitionStationBuilder builderFor(int stationId) {
        for (IntFunction<ExhibitionStationBuilder> wing : WINGS) {
            ExhibitionStationBuilder builder = wing.apply(stationId);
            if (builder != null) {
                return builder;
            }
        }
        return null;
    }
}
