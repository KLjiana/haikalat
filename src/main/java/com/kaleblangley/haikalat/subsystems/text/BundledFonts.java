package com.kaleblangley.haikalat.subsystems.text;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;

/**
 * Single source of truth for the fonts shipped with the engine.
 *
 * <p>The catalog owns resource paths, family names and registration order. Native
 * handles still belong to {@link FontManager}; this class only performs the
 * deterministic classpath-to-face registration step.</p>
 */
public final class BundledFonts {
    public static final String NOTO_SANS_SC_FAMILY = "Noto Sans SC";
    public static final String NOTO_SANS_SC_RESOURCE = "/text/fonts/NotoSansSC-VF.ttf";
    public static final String UNIFONT_FAMILY = "Unifont";
    public static final String UNIFONT_RESOURCE = "/text/fonts/unifont-17.0.05.otf";
    public static final String JETBRAINS_MONO_FAMILY = "JetBrains Mono";
    public static final String JETBRAINS_MONO_RESOURCE =
            "/text/fonts/JetBrainsMono-Regular.ttf";

    /*
     * Keep this order stable: the first entry is the default, and subsequent
     * entries are the deterministic fallback order.
     */
    private static final List<Asset> ASSETS = List.of(
            new Asset(NOTO_SANS_SC_FAMILY, NOTO_SANS_SC_RESOURCE, 400.0f),
            new Asset(UNIFONT_FAMILY, UNIFONT_RESOURCE, null),
            new Asset(JETBRAINS_MONO_FAMILY, JETBRAINS_MONO_RESOURCE, null));

    private BundledFonts() {
    }

    public static LinkedHashMap<String, FontFace> registerAll(FontManager fonts) {
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
        try (InputStream input = BundledFonts.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("Missing bundled font " + resource);
            }
            return input.readAllBytes();
        } catch (IOException failure) {
            throw new IllegalStateException("Failed to load bundled font " + resource,
                    failure);
        }
    }

    private record Asset(String familyName, String resource, Float variationWeight) {
    }
}
