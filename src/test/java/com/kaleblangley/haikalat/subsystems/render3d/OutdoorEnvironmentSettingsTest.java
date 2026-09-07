package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OutdoorEnvironmentSettingsTest {
    @Test
    void presetsAreFiniteAndLocalVolumeBudgetIsBounded() {
        assertTrue(OutdoorEnvironmentSettings.morningFog().volumetricSun().enabled());
        assertTrue(OutdoorEnvironmentSettings.clearDay().sky().sunDirection().isFinite());
        List<LocalFogVolume> volumes = new ArrayList<>();
        for (int index = 0; index < OutdoorEnvironmentSettings.MAX_LOCAL_VOLUMES; index++) {
            volumes.add(LocalFogVolume.sphere(new Vector3f(index, 0.0f, -index), 1.0f,
                    0.1f, new Vector3f(0.5f)));
        }
        assertEquals(8, OutdoorEnvironmentSettings.morningFog()
                .withLocalFogVolumes(volumes).localFogVolumes().size());
        volumes.add(LocalFogVolume.sphere(new Vector3f(), 1.0f, 0.1f, new Vector3f(1.0f)));
        assertThrows(IllegalArgumentException.class,
                () -> OutdoorEnvironmentSettings.morningFog().withLocalFogVolumes(volumes));
    }

    @Test
    void rejectsInvalidSunAndDisabledVolumeCombinations() {
        assertThrows(IllegalArgumentException.class,
                () -> new VolumetricSunSettings(true, 3, 2, 20.0f, 0.1f,
                        new Vector3f(1.0f), 0.0f, 0.8f, 0.1f, 0.1f));
        assertThrows(IllegalArgumentException.class,
                () -> new OutdoorEnvironmentSettings(false, "disabled", StylizedSkySettings.clearDay(),
                        VolumetricSunSettings.balanced(),
                        com.kaleblangley.haikalat.subsystems.postprocess.FogSettings.disabled(),
                        List.of(), 0, 0.0f));
        assertThrows(IllegalArgumentException.class,
                () -> OutdoorEnvironmentSettings.disabled().withLocalFogVolumes(List.of(
                        LocalFogVolume.sphere(new Vector3f(), 1.0f, 0.1f, new Vector3f(1.0f)))));
    }

    @Test
    void qualitySnapshotKeepsPresetAndChangesOnlyVolumeShape() {
        OutdoorEnvironmentSettings base = OutdoorEnvironmentSettings.morningFog();
        VolumetricSunSettings high = new VolumetricSunSettings(true, 96, 1,
                base.volumetricSun().maximumDistance(), base.volumetricSun().density(),
                base.volumetricSun().scatteringColor(), base.volumetricSun().anisotropy(),
                0.0f, base.volumetricSun().depthRejectThreshold(), 0.0f);
        OutdoorEnvironmentSettings replacement = base.withVolumetricSun(high);
        assertEquals(base.preset(), replacement.preset());
        assertEquals(1, replacement.volumetricSun().downsample());
        assertEquals(base.globalFog(), replacement.globalFog());
    }

    @Test
    void parameterSnapshotsAreImmutableAndKeepTheResourceContract() {
        OutdoorEnvironmentSettings base = OutdoorEnvironmentSettings.morningFog();
        OutdoorEnvironmentSettings changed = base
                .withNoiseSeed(99)
                .withWindSpeed(0.75f)
                .withSky(new StylizedSkySettings(base.sky().zenithColor(), base.sky().horizonColor(),
                        base.sky().nadirColor(), base.sky().sunDirection(), base.sky().sunColor(),
                        4.0f, base.sky().sunAngularRadius(), base.sky().haloIntensity(),
                        base.sky().environmentIntensity()));
        assertEquals(1337, base.noiseSeed());
        assertEquals(0.12f, base.windSpeed());
        assertEquals(99, changed.noiseSeed());
        assertEquals(0.75f, changed.windSpeed());
        assertEquals(4.0f, changed.sky().sunIntensity());
        assertTrue(changed.enabled());
        assertEquals(base.volumetricSun().enabled(), changed.volumetricSun().enabled());
    }
}
