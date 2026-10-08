package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class OutdoorEnvironmentSettingsTest {
    @Test void skyPresetsAreFiniteAndPhysicalVolumeBudgetIsBounded() {
        assertTrue(OutdoorEnvironmentSettings.morningFog().enabled());
        assertTrue(OutdoorEnvironmentSettings.clearDay().sky().sunDirection().isFinite());
        List<LocalFogVolume> volumes = new ArrayList<>();
        for (int i=0;i<VolumetricFogSettings.MAX_LOCAL_VOLUMES;i++)
            volumes.add(LocalFogVolume.sphere(new Vector3f(i,0,-i),1,.1f,new Vector3f(.5f)));
        var accepted = fog(volumes, new Vector3f());
        volumes.clear();
        assertEquals(8, accepted.localVolumes().size());
        var excess = new ArrayList<>(accepted.localVolumes());
        excess.add(LocalFogVolume.sphere(new Vector3f(),1,.1f,new Vector3f(1)));
        assertThrows(IllegalArgumentException.class, () -> fog(excess, new Vector3f()));
    }
    @Test void rejectsInvalidSkyAndIndependentVolumeInputs() {
        assertThrows(IllegalArgumentException.class, () -> new OutdoorEnvironmentSettings(true," ",StylizedSkySettings.clearDay()));
        assertThrows(NullPointerException.class, () -> new OutdoorEnvironmentSettings(false,"disabled",null));
        assertThrows(IllegalArgumentException.class, () -> fog(List.of(),new Vector3f(Float.NaN,0,0)));
        // Disabling the sky does not disable independently authored physical fog.
        var visual = new VisualSettings(1,1,0,com.kaleblangley.haikalat.core.AntiAliasingMode.NONE,
                com.kaleblangley.haikalat.runtime.BloomSettings.disabled(),OutdoorEnvironmentSettings.disabled())
                .withVolumetricFog(fog(List.of(),new Vector3f()));
        assertTrue(visual.volumetricFog().enabled());
    }
    @Test void skyReplacementKeepsIdentityAndIndependentMedium() {
        var base=OutdoorEnvironmentSettings.morningFog();
        var changed=base.withSky(StylizedSkySettings.goldenHour());
        assertEquals(base.preset(),changed.preset());
        assertEquals(base.enabled(),changed.enabled());
        assertEquals(StylizedSkySettings.morningFog(),base.sky());
        assertEquals(StylizedSkySettings.goldenHour(),changed.sky());
    }
    @Test void physicalWindAndMediumSnapshotsAreImmutable() {
        var wind=new Vector3f(.75f,0,.02f);var settings=fog(List.of(),wind);
        wind.zero();settings.wind().zero();settings.globalMedium().albedo().zero();
        assertEquals(new Vector3f(.75f,0,.02f),settings.wind());
        assertEquals(new Vector3f(.8f),settings.globalMedium().albedo());
    }
    private static VolumetricFogSettings fog(List<LocalFogVolume> volumes,Vector3f wind) {
        return new VolumetricFogSettings(true,32,VolumetricFogSettings.Quality.BALANCED,
                new FogMediumSettings(.01f,new Vector3f(.8f),new Vector3f(),0,0),volumes,0,true,.8f,99,wind);
    }
}
