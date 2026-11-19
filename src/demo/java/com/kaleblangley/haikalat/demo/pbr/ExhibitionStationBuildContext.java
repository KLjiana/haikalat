package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.runtime.RenderSettings;

/** Immutable inputs a station builder needs from the running session. */
record ExhibitionStationBuildContext(ExhibitionAssets assets, RenderSettings renderSettings) {
}
