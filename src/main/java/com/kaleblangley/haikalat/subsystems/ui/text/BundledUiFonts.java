package com.kaleblangley.haikalat.subsystems.ui.text;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Single source of truth for the UI fonts shipped with the engine.
 *
 * <p>The catalog owns resource paths, family names and registration order. Native
 * handles still belong to {@link FontManager}; this class only performs the
 * deterministic classpath-to-face registration step.</p>
 */
final class BundledUiFonts {
    static final String NOTO_SANS_SC_FAMILY = "Noto Sans SC";
    static final String NOTO_SANS_SC_RESOURCE = "/ui/fonts/NotoSansSC-VF.ttf";
    static final String UNIFONT_FAMILY = "Unifont";
    static final String UNIFONT_RESOURCE = "/ui/fonts/unifont-17.0.05.otf";
    static final String JETBRAINS_MONO_FAMILY = "JetBrains Mono";
    static final String JETBRAINS_MONO_RESOURCE =
            "/ui/fonts/JetBrainsMono-Regular.ttf";

    /*
     * Keep this order stable: the first entry is the default, and subsequent
     * entries are the deterministic fallback order.
     */
    private static final List<Asset> ASSETS = List.of(
            new Asset(NOTO_SANS_SC_FAMILY, NOTO_SANS_SC_RESOURCE, 400.0f),
            new Asset(UNIFONT_FAMILY, UNIFONT_RESOURCE, null),
            new Asset(JETBRAINS_MONO_FAMILY, JETBRAINS_MONO_RESOURCE, null));

    private BundledUiFonts() {
    }

    static LinkedHashMap<String, FontFace> registerAll(FontManager fonts) {
        Objects.requireNonNull(fonts, "fonts");
        LinkedHashMap<String, FontFace> faces = new LinkedHashMap<>();
        for (Asset asset : ASSETS) {
            FontFamily family = fonts.registerFamily(asset.familyName());
            FontFace face = fonts.registerFace(family, read(asset.resource()), 0);
            if (asset.variationWeight() != null) {
                face.variationCoordinate("wght", asset.variationWeight());
            }
            faces.put(asset.familyName(), face);
        }
        return faces;
    }

    private static byte[] read(String resource) {
        try (InputStream input = BundledUiFonts.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled UI font " + resource);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to load bundled UI font " + resource,
                    failure);
        }
    }

    private record Asset(String familyName, String resource, Float variationWeight) {
    }
}
