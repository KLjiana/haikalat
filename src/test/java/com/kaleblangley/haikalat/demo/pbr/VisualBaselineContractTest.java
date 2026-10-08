package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualBaselineContractTest {
    @TempDir
    Path temporary;

    @Test
    void allFrozenProfilesAndCameraPathsAreStrictlyReloadable() {
        verify("forest_morning", com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings.morningFog(),
                "forest-v0243-r1", "deterministic_arc", 1337);
        verify("forest_dusk", com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings.goldenHour(),
                "forest-v0243-r1", "deterministic_arc", 4242);
        verify("town_night", OutdoorEnvironmentSettings.disabled(),
                "clustered-town-v1", "street", 242);
    }

    private void verify(String id, OutdoorEnvironmentSettings fallbackOutdoor,
                        String sceneRevision, String cameraPath, int seed) {
        VisualProfile fallback = VisualProfile.outdoorCompatibility(fallbackOutdoor);
        VisualProfile profile = VisualProfileCodec.load(
                Path.of("config/visual-profiles", id + ".properties"), fallback);
        SceneCaptureSpec capture = SceneCaptureSpecCodec.load(
                Path.of("config/visual-baselines", id + ".capture.properties"));
        assertEquals(id, profile.profileId());
        assertEquals(sceneRevision, capture.sceneRevision());
        assertEquals(cameraPath, capture.cameraPath());
        assertEquals(seed, capture.seed());
        assertEquals(List.of(60, 240, 420), capture.captureFrames());
        for (int frame : capture.captureFrames()) {
            VisualBaselineHdr.Image hdr = VisualBaselineHdr.load(Path.of(
                    "config/visual-baselines/reference", id + "-" + String.format("%04d", frame)
                            + ".h4f.gz"));
            assertEquals(320, hdr.width());
            assertEquals(180, hdr.height());
            double average = 0.0;
            float maximum = 0.0f;
            for (float component : hdr.rgb()) {
                average += component;
                maximum = Math.max(maximum, component);
            }
            assertTrue(average / hdr.rgb().length > 0.002,
                    "HDR reference should contain illuminated content");
            assertTrue(maximum > 0.1f, "HDR reference should retain highlights");
        }

        Path roundTrip = temporary.resolve(id + ".properties");
        VisualProfileCodec.save(roundTrip, profile);
        assertEquals(profile, VisualProfileCodec.load(roundTrip, fallback));
    }

    @Test
    void hdrErrorGateRejectsAVisibleLinearColorChange() {
        float[] reference = new float[4 * 4 * 4];
        float[] changed = new float[reference.length];
        for (int pixel = 0; pixel < 16; pixel++) {
            reference[pixel * 4] = 0.5f;
            changed[pixel * 4] = 0.7f;
        }
        VisualBaselineHdr.Image expected = VisualBaselineHdr.downsample(reference, 4, 4);
        VisualBaselineHdr.Image actual = VisualBaselineHdr.downsample(changed, 4, 4);
        assertThrows(IllegalStateException.class,
                () -> VisualBaselineHdr.compare(expected, actual,
                        temporary.resolve("visible-error.png")));
    }
}
