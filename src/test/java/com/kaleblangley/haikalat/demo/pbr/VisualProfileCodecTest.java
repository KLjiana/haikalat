package com.kaleblangley.haikalat.demo.pbr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.joml.Vector3f;
import com.kaleblangley.haikalat.subsystems.render3d.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VisualProfileCodecTest {
    @TempDir
    Path temporary;

    @Test
    void roundTripPreservesEveryRendererValueAndCaptureInputsStaySeparate() throws Exception {
        VisualProfile profile = physicalProfile();
        Path path = temporary.resolve("forest_morning.properties");

        VisualProfileCodec.save(path, profile);
        SceneCaptureSpec spec = new SceneCaptureSpec("forest-v0243-r1", "forest_arc", 1337,
                java.util.List.of(60, 240, 420));
        Path capture = temporary.resolve("forest_morning.capture.properties");
        SceneCaptureSpecCodec.save(capture, spec);

        assertEquals(profile, VisualProfileCodec.load(path, profile));
        assertEquals(spec, SceneCaptureSpecCodec.load(capture));
        assertTrue(Files.readString(path).contains("schemaVersion=2"));
        assertTrue(Files.readString(path).contains("medium.emission="));
        assertTrue(Files.readString(path).contains("light.1.history=false"));
        assertTrue(Files.readString(capture).contains("cameraPath=forest_arc"));
    }

    @Test
    void unknownFieldsNaNAndExcessVolumesFailWithFieldContext() throws Exception {
        VisualProfile fallback = physicalProfile();
        Path unknown = temporary.resolve("unknown.properties");
        VisualProfileCodec.save(unknown, fallback);
        Files.writeString(unknown, Files.readString(unknown) + "\nsecret.option=1\n");
        IllegalArgumentException unknownFailure = assertThrows(IllegalArgumentException.class,
                () -> VisualProfileCodec.load(unknown, fallback));
        assertTrue(unknownFailure.getMessage().contains("secret.option"));

        Path nan = temporary.resolve("nan.properties");
        VisualProfileCodec.save(nan, fallback);
        Files.writeString(nan, Files.readString(nan).replace("exposure.manual=1.05",
                "exposure.manual=NaN"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> VisualProfileCodec.load(nan, fallback)).getMessage()
                .contains("exposure.manual"));

        Path volumes = temporary.resolve("volumes.properties");
        VisualProfileCodec.save(volumes, fallback);
        Files.writeString(volumes, Files.readString(volumes).replace("local.count=2",
                "local.count=9"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> VisualProfileCodec.load(volumes, fallback)).getMessage()
                .contains("local.count"));
    }

    @Test
    void schemaOneAndUnversionedOutdoorRequireExplicitMigration() throws Exception {
        Path legacy = temporary.resolve("legacy.properties");
        Files.writeString(legacy, """
                version=0.24
                preset=clear_day
                enabled=true
                noise_seed=99
                wind_speed=0.25
                volume.enabled=true
                """);
        VisualProfile fallback = VisualProfile.outdoorCompatibility(
                com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings.clearDay());

        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> VisualProfileCodec.load(legacy, fallback)).getMessage().contains("schemaVersion"));
        Files.writeString(legacy, "schemaVersion=1\nvolume.scattering_color=0.2,0.3,0.4\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> VisualProfileCodec.load(legacy, fallback)).getMessage().contains("not physical albedo"));
        Files.writeString(legacy, "schemaVersion=2\nvolume.scattering_color=0.2,0.3,0.4\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> VisualProfileCodec.load(legacy, fallback)).getMessage().contains("volume.scattering_color"));
    }

    @Test
    void undeclaredDuplicateLightEntriesAndInvalidAlbedoFailBeforeSaveIsPublished() throws Exception {
        VisualProfile profile = physicalProfile();
        Path file = temporary.resolve("entries.properties");
        VisualProfileCodec.save(file, profile);
        String saved = Files.readString(file);
        Files.writeString(file, saved + "\nlocal.2.extinction=0.02\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> VisualProfileCodec.load(file, profile)).getMessage().contains("local.2.extinction"));
        Files.writeString(file, saved.replace("light.1.scene_index=16", "light.1.scene_index=0"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> VisualProfileCodec.load(file, profile)).getMessage().contains("duplicate"));
        Files.writeString(file, saved + "\nmedium.albedo=1.1,0.8,0.6\n");
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> VisualProfileCodec.load(file, profile)).getMessage().contains("medium"));
    }

    private static VisualProfile physicalProfile() {
        var base = VisualProfile.outdoorCompatibility(com.kaleblangley.haikalat.subsystems.render3d.OutdoorEnvironmentSettings.morningFog());
        var local = List.of(
                new LocalFogVolume(LocalFogVolume.Shape.SPHERE, new Vector3f(1,2,3), new Vector3f(4),
                        .012f, new Vector3f(.6f,.7f,.8f), new Vector3f(.01f,.02f,.03f), .25f, .22f, .4f),
                new LocalFogVolume(LocalFogVolume.Shape.BOX, new Vector3f(-2,1,-7), new Vector3f(2,3,4),
                        .02f, new Vector3f(.8f), new Vector3f(), .4f, .1f, .2f));
        var volume = new VolumetricFogSettings(true, 90, VolumetricFogSettings.Quality.BALANCED,
                new FogMediumSettings(.004f, new Vector3f(.82f,.9f,1), new Vector3f(.01f), 1, .24f),
                local, .55f, true, .86f, 1337, new Vector3f(.12f,0,.02f));
        return new VisualProfile(2, "forest_morning", "Explicit physical coefficients", "Haikalat",
                base.settings().withVolumetricFog(volume), List.of(
                new VisualProfile.LightVolumeOverride(0, LightVolumeHints.DEFAULT),
                new VisualProfile.LightVolumeOverride(16, new LightVolumeHints(.3f,false,false))));
    }
}
