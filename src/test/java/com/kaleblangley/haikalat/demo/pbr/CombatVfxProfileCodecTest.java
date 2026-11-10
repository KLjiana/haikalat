package com.kaleblangley.haikalat.demo.pbr;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CombatVfxProfileCodecTest {
    @TempDir
    Path directory;

    @Test
    void roundTripPreservesEveryField() {
        CombatVfxProfile profile = CombatVfxProfile.defaults().toBuilder()
                .profileId("round-trip")
                .rimEnabled(false)
                .rimColor(new Vector3f(0.1f, 0.2f, 0.3f))
                .uvSpeedU(-0.125f)
                .dissolveEdgeWidth(0.33f)
                .lightPriority(17)
                .seed(-42L)
                .meshId("mesh.sphere")
                .build();
        Path file = directory.resolve("combat.properties");
        CombatVfxProfileCodec.save(file, profile);
        assertEquals(profile, CombatVfxProfileCodec.load(file));
        assertTrue(Files.exists(file));
    }

    @Test
    void missingUnknownWrongVersionAndInvalidValuesAreRejectedWithKeys() throws IOException {
        Path missing = write("schemaVersion=1\nprofileId=missing\n");
        IllegalArgumentException missingFailure = assertThrows(IllegalArgumentException.class,
                () -> CombatVfxProfileCodec.load(missing));
        assertTrue(missingFailure.getMessage().contains("rim.enabled"));

        Path unknown = write("schemaVersion=1\nunknown.key=1\n");
        assertThrows(IllegalArgumentException.class, () -> CombatVfxProfileCodec.load(unknown));

        Path wrongVersion = write("schemaVersion=2\n");
        IllegalArgumentException versionFailure = assertThrows(IllegalArgumentException.class,
                () -> CombatVfxProfileCodec.load(wrongVersion));
        assertTrue(versionFailure.getMessage().contains("unsupported schema"));

        CombatVfxProfile valid = CombatVfxProfile.defaults();
        Path nonFinite = directory.resolve("non-finite.properties");
        CombatVfxProfileCodec.save(nonFinite, valid);
        String text = Files.readString(nonFinite).replaceFirst(
                "(?m)^rim\\.intensity=.*$", "rim.intensity=NaN");
        Files.writeString(nonFinite, text);
        IllegalArgumentException finiteFailure = assertThrows(IllegalArgumentException.class,
                () -> CombatVfxProfileCodec.load(nonFinite));
        assertTrue(finiteFailure.getMessage().contains("rim.intensity"));

        Path outOfRange = directory.resolve("out-of-range.properties");
        CombatVfxProfileCodec.save(outOfRange, valid);
        String rangeText = Files.readString(outOfRange).replaceFirst(
                "(?m)^uv\\.noise_amplitude=.*$", "uv.noise_amplitude=2.0");
        Files.writeString(outOfRange, rangeText);
        assertThrows(IllegalArgumentException.class,
                () -> CombatVfxProfileCodec.load(outOfRange));
    }

    @Test
    void invalidTimingAndColorsAreRejectedBeforePublication() {
        assertThrows(IllegalArgumentException.class, () -> CombatVfxProfile.defaults().toBuilder()
                .releaseSeconds(0.5f).chargeSeconds(1.0f).build());
        assertThrows(IllegalArgumentException.class, () -> CombatVfxProfile.defaults().toBuilder()
                .fadeSeconds(4.0f).lightEndSeconds(3.0f).build());
        assertThrows(IllegalArgumentException.class, () -> CombatVfxProfile.defaults().toBuilder()
                .rimColor(new Vector3f(-0.1f, 0.0f, 0.0f)).build());
        assertThrows(IllegalArgumentException.class, () -> CombatVfxProfile.defaults().toBuilder()
                .dissolveEdgeWidth(1.5f).build());
    }

    @Test
    void unknownLogicalResourcesFailBeforeAnAssetIsPublished() {
        CombatVfxCatalog catalog = CombatVfxCatalog.showcase();
        assertThrows(IllegalArgumentException.class, () -> CombatVfxEffectFactory.build(
                CombatVfxProfile.defaults().toBuilder().particleTextureId("missing").build(),
                catalog));
        assertThrows(IllegalArgumentException.class, () -> CombatVfxEffectFactory.build(
                CombatVfxProfile.defaults().toBuilder().meshId("missing").build(), catalog));
    }

    @Test
    void baselineKeepsTheAbComparisonFixedAndOnlyDisablesNewTerms() {
        CombatVfxProfile baseline = CombatVfxProfile.baseline();
        CombatVfxProfile defaults = CombatVfxProfile.defaults();
        assertEquals(defaults.seed(), baseline.seed());
        assertEquals(defaults.chargeSeconds(), baseline.chargeSeconds());
        assertEquals(defaults.releaseSeconds(), baseline.releaseSeconds());
        assertEquals(defaults.fadeSeconds(), baseline.fadeSeconds());
        assertEquals(defaults.lightEndSeconds(), baseline.lightEndSeconds());
        assertEquals(defaults.particleTextureId(), baseline.particleTextureId());
        assertEquals(defaults.meshId(), baseline.meshId());
        assertFalse(baseline.rimEnabled());
        assertFalse(baseline.uvFlowEnabled());
        assertFalse(baseline.dissolveEnabled());
        assertFalse(baseline.lightEnabled());
    }

    private Path write(String contents) throws IOException {
        Path file = directory.resolve("profile-" + Math.abs(contents.hashCode()) + ".properties");
        Files.writeString(file, contents);
        return file;
    }
}
