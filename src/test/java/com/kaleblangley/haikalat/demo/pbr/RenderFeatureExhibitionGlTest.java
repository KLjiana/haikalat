package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureActivity;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureReason;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureState;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureStatus;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.CameraProjection;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.RefractionPass;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.opengl.GL46.GL_BACK;
import static org.lwjgl.opengl.GL46.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL46.GL_RGBA;
import static org.lwjgl.opengl.GL46.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL46.glBindFramebuffer;
import static org.lwjgl.opengl.GL46.glReadBuffer;
import static org.lwjgl.opengl.GL46.glReadPixels;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hidden deterministic integration for the 46-station exhibition.
 *
 * <p>This is not human visual acceptance.  It proves the catalogue is unique
 * and complete, that every station can be selected and switched between its
 * two phases without a production failure or GL error, that wired stations
 * really change the rendered output (or expose a production rejection reason),
 * and that the session closes every owned GL resource.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class RenderFeatureExhibitionGlTest {
    private static final float FIXED_DELTA_SECONDS = 1.0f / 60.0f;
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;
    private static final int SETTLE_FRAMES = 3;

    /** Stations whose effect cannot be expected to change a 320x180 frame; the test
     * requires a production rejection/disabled reason instead of a pixel delta. */
    private static final Set<Integer> PIXEL_DELTA_OPTIONAL = Set.of(42);

    private GlfwWindow window;
    private ExhibitionSession session;
    private final Map<String, String> evidence = new LinkedHashMap<>();

    @BeforeAll
    void openContext() {
        window = new GlfwWindow.Builder()
                .dimensions(WIDTH, HEIGHT)
                .title("Exhibition Integration")
                .visible(false)
                .cursorMode(GlfwWindow.CursorMode.NORMAL)
                .build();
        window.bindContext();
        GL.createCapabilities();
        GlDebug.enableDebugCallback();
        window.setVsync(false);
        session = ExhibitionSession.create(window, true);
    }

    @AfterAll
    void closeContext() {
        try {
            if (session != null) {
                session.close();
                assertTrue(GlDebug.resources().liveResources().isEmpty(),
                        "exhibition session leaked GL resources: "
                                + GlDebug.resources().liveResources());
            }
        } finally {
            if (window != null) {
                window.close();
            }
        }
    }

    @Test
    void catalogueCoversEveryRoadmapStationExactlyOnce() {
        List<Integer> ids = ExhibitionCatalog.validate();
        assertEquals(46, ids.size());
        assertEquals(List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
                19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 36, 37,
                38, 39, 40, 41, 42, 43, 44, 45, 46), ids);
        for (ExhibitionStation station : ExhibitionCatalog.stations()) {
            assertNotNull(station.wing(), "station " + station.id() + " has no wing");
            assertFalse(station.nameZh().isBlank());
            assertFalse(station.nameEn().isBlank());
            assertFalse(station.subject().isBlank());
            assertFalse(station.limitations().isEmpty(),
                    "station " + station.id() + " has no limitation card");
            assertFalse(station.evidence().isEmpty(),
                    "station " + station.id() + " has no evidence link");
            assertNotNull(station.gate());
            assertNotNull(station.batch());
            assertTrue(station.wing().stationIdList().contains(station.id()),
                    "station " + station.id() + " is not listed in wing " + station.wing());
        }
        assertEquals(46, ExhibitionCatalog.stations().stream()
                .map(ExhibitionStation::id).distinct().count());
        assertEquals(ExhibitionCatalog.station(1), ExhibitionCatalog.next(46));
        assertEquals(ExhibitionCatalog.station(46), ExhibitionCatalog.previous(1));
    }

    @Test
    void walkableHallShowsModelsAndRunsNearbyEffectsWithoutMovingTheVisitor() {
        ExhibitionMuseum hall = ExhibitionMuseum.open(session);
        session.select(35);
        session.setPhase(ExhibitionPhase.EFFECT);
        session.frame(FIXED_DELTA_SECONDS, false);
        session.showHall(hall);
        assertEquals(46, hall.exhibits().stream().map(ExhibitionMuseum.Exhibit::stationId).distinct().count());
        assertEquals(-1, hall.aimedStationId(), "the entrance faces the central aisle");
        var entrance = hall.camera().position();
        hall.move(0, 0, 1, 0.1f);
        var raised = hall.camera().position();
        assertEquals(entrance.x, raised.x);
        assertEquals(entrance.z, raised.z);
        assertEquals(entrance.y + 0.6f, raised.y, 1e-5f, "rise along world Y regardless of camera pitch");
        assertFalse(hall.toggleViewpoint());
        var overview = hall.camera().position();
        hall.move(1, 0, -1, 0.1f);
        assertEquals(overview, hall.camera().position(), "movement must not affect the overview");
        assertTrue(hall.toggleViewpoint());
        assertEquals(raised, hall.camera().position(), "returning from overview must restore the visitor's height");
        hall.move(0, 0, -1, 0.1f);
        assertEquals(entrance.y, hall.camera().position().y, 1e-5f, "descend at the same speed");
        for (int step = 0; step < 10; step++) hall.move(0, 0, 1, 0.1f);
        assertEquals(7.75f, hall.camera().position().y, 1e-5f, "fly above the 5.5 m hall walls");
        for (int step = 0; step < 10; step++) hall.move(0, 0, -1, 0.1f);
        assertEquals(1.75f, hall.camera().position().y, 1e-5f, "return below the hall ceiling");

        for (ExhibitionMuseum.Exhibit exhibit : hall.exhibits()) {
            assertTrue(exhibit.endModel() > exhibit.firstModel(), "missing shared-scene geometry #" + exhibit.stationId());
            faceExhibit(hall, exhibit, 2f, 2.8f, -36f);
            assertEquals(exhibit.stationId(), hall.aimedStationId());
            hall.camera().setPitch(45);
            assertEquals(-1, hall.aimedStationId());
        }

        var flow = hall.exhibits().stream().filter(exhibit -> exhibit.stationId() == 2).findFirst().orElseThrow();
        faceExhibit(hall, flow, 3f, 1.75f, -25f);
        for (int step = 0; step < 12; step++) hall.move(1, 0, 0, 0.1f);
        var passed = hall.camera().position();
        assertEquals(entrance.y, passed.y, "horizontal flight keeps its height");
        assertTrue(passed.z < flow.center().z - flow.halfDepth(),
                "visitor must be able to fly through a pedestal");

        var basin = hall.exhibits().stream().filter(exhibit -> exhibit.stationId() == 11)
                .findFirst().orElseThrow();
        hall.camera().setPosition(new org.joml.Vector3f(basin.center().x, basin.center().y + 0.1f,
                basin.center().z + basin.halfDepth() + 0.4f));
        hall.camera().setYaw(-90);
        hall.move(1, 0, 0, 0.1f);
        hall.move(0, 0, -1, 0.05f);
        var underwater = hall.camera().position();
        assertEquals(basin.center().x, underwater.x, 1e-5f);
        assertTrue(underwater.z < basin.center().z + basin.halfDepth(),
                "visitor must be able to enter the water exhibit");
        assertTrue(underwater.y > 0f, "underwater viewing should remain above the hall floor");
        assertTrue(underwater.y < basin.center().y - 0.1f,
                "visitor must be able to descend below the raised water surface");
        hallFrame();
        var basinViewCamera = session.camera().position();
        assertTrue(basinViewCamera.y < 0f && basinViewCamera.y > -1f,
                "the rendered #11 view camera must be inside the pool, below its surface and above its bottom");
        assertTrue(session.featureState().effective().waterSurface().active());
        assertTrue(session.featureState().effective().waterRefraction().active());

        faceExhibit(hall, flow, 1.65f, 1.37f, 0);
        hallFrame();
        assertTrue(session.pipeline().projectedDecals().enabled());
        assertEquals(3, session.pipeline().projectedDecalStatistics().drawn());
        assertEquals(1, session.pipeline().vfxLightDiagnostics().selected());
        assertTrue(session.featureState().effective().hairStrands().enabled());
        double[] initialFlow = readPresentedLuminance();
        for (int step = 0; step < 8; step++) hallFrame();
        assertTrue(changedPixelFraction(initialFlow, readPresentedLuminance()) > 0.0005,
                "the shared-world UV flow must advance under a stationary visitor camera");

        var distortion = hall.exhibits().stream().filter(exhibit -> exhibit.stationId() == 4).findFirst().orElseThrow();
        faceExhibit(hall, distortion, 2.8f, 1.75f, -1f);
        session.frame(FIXED_DELTA_SECONDS, false);
        int farWallBannerPixel = (HEIGHT - 1 - 159) * WIDTH + 305;
        double bannerInHall = readPresentedLuminance()[farWallBannerPixel];
        hallFrame();
        assertEquals(4, session.hallEffectId());
        assertEquals(bannerInHall, readPresentedLuminance()[farWallBannerPixel], 3.0 / 255.0,
                "activating a nearby exhibit must preserve the distant wall and its fixed emissive banner");

        var shared = Set.of(1, 2, 3, 5, 6, 7, 16, 17, 31, 32, 46);
        // Visit glass, water, and SSR consecutively to exercise incompatible chains.
        var order = new ArrayList<>(List.of(13, 14, 20, 35, 21, 43));
        for (int id = 1; id <= 46; id++) {
            if (!shared.contains(id) && !order.contains(id)) order.add(id);
        }
        for (int id : order) {
            var exhibit = hall.exhibits().stream().filter(entry -> entry.stationId() == id).findFirst().orElseThrow();
            faceExhibit(hall, exhibit, 2.8f, 1.75f, -18f);
            var visitorPosition = hall.camera().position();
            hallFrame();
            assertEquals(id, session.hallEffectId(), "proximity should activate #" + id);
            assertEquals(visitorPosition, hall.camera().position(), "activation must not move the visitor");
            assertEquals(-18f, session.camera().pitch());
            assertTrue(session.pipeline().scene().rendererCount() > 100, "nearby effect must render in the hall");
            var state = session.featureState();
            assertEquals(RenderFeatureStatus.APPLIED, state.status(),
                    () -> "hall exhibit #" + id + " did not apply: " + state.failureReason());
            assertEquals(id == 13,
                    session.pipeline().graph().description().executionOrder().contains(RefractionPass.PASS_NAME));
            assertEquals(Set.of(11, 14, 30, 36).contains(id), state.effective().waterSurface().active());
            assertEquals(id == 35 ? 0.5f : 1f, state.effective().render().renderScale());
            if (id == 21 || id == 43) {
                double[] first = readPresentedLuminance();
                for (int step = 0; step < 12; step++) hallFrame();
                assertTrue(changedPixelFraction(first, readPresentedLuminance()) > 0.0005,
                        "station must animate in the visitor's frame #" + id);
            }
            GlDebug.checkError("exhibition.hall#" + id);
        }

        var volume = hall.exhibits().stream().filter(exhibit -> exhibit.stationId() == 38).findFirst().orElseThrow();
        faceExhibit(hall, volume, 2.6f, 1.75f, -15f);
        hallFrame();
        assertTrue(session.featureState().effective().volumetricParticles().active());
        for (int yaw = -90; yaw <= 270; yaw += 5) {
            hall.camera().setYaw(yaw);
            hallFrame();
            assertEquals(38, session.hallEffectId(), "turning in place keeps the visitor at the same exhibit");
            assertFalse(session.hallParticleBudgetLimited(), "off-screen particle must not count toward the budget at yaw " + yaw);
        }
        faceExhibit(hall, volume, 1.45f, 1.75f, -27f);
        hallFrame();
        assertTrue(session.hallParticleBudgetLimited(), "actual close coverage must trigger a safe fallback");
        assertFalse(session.featureState().effective().volumetricParticles().active());
        faceExhibit(hall, volume, 2.6f, 1.75f, -15f);
        hallFrame();
        assertTrue(session.featureState().effective().volumetricParticles().active(), "retreating restores the pass");

        assertFalse(hall.toggleViewpoint());
        hallFrame();
        assertEquals(-1, session.hallEffectId());
        assertTrue(hall.scene() == session.pipeline().scene());
        assertTrue(hall.toggleViewpoint());

        ExhibitionStation decal = ExhibitionCatalog.station(6);
        PhaseCapture baseline = renderPhase(decal, ExhibitionPhase.BASELINE);
        PhaseCapture effect = renderPhase(decal, ExhibitionPhase.EFFECT);
        assertTrue(changedPixelFraction(baseline.presented(), effect.presented()) > 0.0005,
                "after leaving the hall, #6 needs the full-resolution decal recorder");
    }

    private void hallFrame() {
        session.updateHall(FIXED_DELTA_SECONDS);
        session.frame(FIXED_DELTA_SECONDS, false);
    }

    private static void faceExhibit(ExhibitionMuseum hall, ExhibitionMuseum.Exhibit exhibit,
                                    float distance, float height, float pitch) {
        hall.camera().setPosition(new org.joml.Vector3f(exhibit.center().x, height, exhibit.center().z + distance));
        hall.camera().setYaw(-90);
        hall.camera().setPitch(pitch);
    }

    @Test
    void giExhibitsToggleLiveInPlaceAndTheProbeGridOnlyLightsItsOwnReceiver() {
        var hall = ExhibitionMuseum.open(session);
        session.showHall(hall);
        assertFalse(session.hallGiComparisonAvailable());
        for (int id : List.of(39, 44, 45)) {
            var exhibit = hall.exhibits().stream().filter(entry -> entry.stationId() == id)
                    .findFirst().orElseThrow();
            faceExhibit(hall, exhibit, 2.85f, 1.75f, -17f);
            for (int step = 0; step < ExhibitionCatalog.settleFrames(id); step++) hallFrame();
            assertEquals(id, session.hallEffectId());
            assertTrue(session.hallGiComparisonAvailable());
            assertFalse(session.hallGiBaseline());
            var active = session.featureState().effective();
            var scene = session.pipeline().scene();
            var cameraPosition = session.camera().position();
            double[] effect = readPresentedLuminance();

            session.toggleHallGiComparison();
            hallFrame();
            assertTrue(session.hallGiBaseline());
            assertEquals(scene, session.pipeline().scene());
            assertEquals(cameraPosition, session.camera().position());
            var baseline = session.featureState().effective();
            assertFalse(baseline.postProcess().ssgi().active());
            assertFalse(baseline.ddgi().active());
            assertFalse(baseline.vct().enabled());
            double[] noGi = readPresentedLuminance();
            assertTrue(meanAbsoluteDifference(effect, noGi) > 0.003,
                    "live GI must visibly affect the frame at #" + id);
            if (id == 44) {
                // Read the upper faces of the two white boards from the same pose.
                int row = HEIGHT - 1 - 154;
                int left = row * WIDTH + 244;
                int right = row * WIDTH + 393;
                assertTrue(effect[left] - noGi[left] > 0.08,
                        "the left board must receive the probe lighting");
                assertEquals(noGi[right], effect[right], 1.0 / 255,
                        "the right board is outside the probe grid");
            }

            session.toggleHallGiComparison();
            for (int step = 0; step < ExhibitionCatalog.settleFrames(id); step++) hallFrame();
            assertFalse(session.hallGiBaseline());
            assertEquals(active, session.featureState().effective());
            assertTrue(meanAbsoluteDifference(readPresentedLuminance(), effect) < 0.004,
                    "re-enabling GI must restore the same light at #" + id);
            session.toggleHallGiComparison();
        }
        assertFalse(hall.toggleViewpoint());
        hallFrame();
        assertFalse(session.hallGiComparisonAvailable());
        assertFalse(session.hallGiBaseline());
        GlDebug.checkError("exhibition.giComparison");
    }

    @Test
    void distortionUsesElapsedTimeRatherThanTheLastFrameInterval() {
        session.select(4);
        session.setPhase(ExhibitionPhase.EFFECT);
        session.reset();
        session.frame(0.125f, false);
        double[] first = readPresentedLuminance();
        for (int step = 1; step < 4; step++) session.frame(0.125f, false);
        session.frame(0.125f, false);
        double[] atHalfSecond = readPresentedLuminance();
        assertTrue(changedPixelFraction(first, atHalfSecond) > 0.0005,
                "heat haze must visibly evolve before testing replay");

        session.reset();
        for (int step = 0; step < 2; step++) session.frame(0.25f, false);
        session.frame(0.125f, false);
        org.junit.jupiter.api.Assertions.assertArrayEquals(atHalfSecond, readPresentedLuminance(),
                1.0 / 255.0, "the same elapsed time must render the same haze across frame schedules");
        GlDebug.checkError("exhibition.distortionElapsedTime");
    }

    @Test
    void smokeResetReplaysTheSameSimulationFrames() {
        session.select(43);
        session.setPhase(ExhibitionPhase.EFFECT);
        session.frame(FIXED_DELTA_SECONDS, false);
        double[] first = readPresentedLuminance();
        for (int frame = 0; frame < 30; frame++) {
            session.frame(FIXED_DELTA_SECONDS, false);
        }
        assertTrue(changedPixelFraction(first, readPresentedLuminance()) > 0.0005,
                "smoke must evolve before testing reset");
        session.reset();
        session.frame(FIXED_DELTA_SECONDS, false);
        org.junit.jupiter.api.Assertions.assertArrayEquals(first, readPresentedLuminance(),
                1.0 / 255.0, "Reset must reproduce the first smoke frame within one display code value");
        GlDebug.checkError("exhibition.smokeReset");
    }

    @Test
    void everyStationSelectsSwitchesAndRestoresWithoutProductionFailureOrGlError() {
        int wired = 0;
        int pending = 0;
        List<String> pendingBatches = new ArrayList<>();
        List<String> pixelFailures = new ArrayList<>();
        for (ExhibitionStation station : ExhibitionCatalog.stations()) {
            session.select(station.id());
            assertEquals(station.id(), session.station().id());
            assertEquals(ExhibitionPhase.BASELINE, session.phase());
            if (!session.wired()) {
                pending++;
                pendingBatches.add("#" + station.id() + "=" + session.pendingBatch().title());
                session.frame(FIXED_DELTA_SECONDS, false);
                GlDebug.checkError("exhibition#" + station.id() + ".pending");
                continue;
            }
            wired++;
            PhaseCapture baseline = renderPhase(station, ExhibitionPhase.BASELINE);
            PhaseCapture effect = renderPhase(station, ExhibitionPhase.EFFECT);
            assertAppliedOrRejected(station, ExhibitionPhase.EFFECT);
            if (station.id() == 18) {
                assertProbeReceiversShowOpposingReflections(baseline.linearHdrRgba(), effect.linearHdrRgba());
            }
            session.reset();
            session.frame(FIXED_DELTA_SECONDS, false);
            GlDebug.checkError("exhibition#" + station.id() + ".reset");

            double difference = meanAbsoluteDifference(baseline.presented(), effect.presented());
            double hdrDifference = meanAbsoluteDifference(baseline.hdr(), effect.hdr());
            double changed = changedPixelFraction(baseline.presented(), effect.presented());
            boolean productionRejection = hasRejectionReason(station, session.featureState());
            evidence.put("#" + station.id(), String.format(
                    "wired changedPixels=%.5f meanDelta=%.6f hdrDelta=%.6f rejection=%s "
                            + "note=%s", changed, difference, hdrDifference, productionRejection,
                    session.note()));
            if (changed < 0.0005 && !PIXEL_DELTA_OPTIONAL.contains(station.id())
                    && !productionRejection) {
                pixelFailures.add("station #" + station.id() + " produced an identical A/B frame "
                        + "(changed=" + changed + ", presentedMean=" + difference + ", hdrMean="
                        + hdrDifference + ") without a production rejection reason; wired effect "
                        + "is not observable; note=" + session.note());
            }
        }
        assertTrue(wired > 0, "no exhibition station is wired to a production scene");
        System.out.println("exhibition integration: wired=" + wired + " pending=" + pending
                + " pendingBatches=" + pendingBatches);
        for (Map.Entry<String, String> entry : evidence.entrySet()) {
            System.out.println("  " + entry.getKey() + " " + entry.getValue());
        }
        assertTrue(pixelFailures.isEmpty(), String.join("\n", pixelFailures));
    }

    private PhaseCapture renderPhase(ExhibitionStation station, ExhibitionPhase phase) {
        // Select the station from scratch for each phase: this is the exact
        // visible-demo interaction (station selection then A/B), and it keeps
        // the mid-run feature switch out of the pixel comparison.
        session.select(station.id());
        if (phase == ExhibitionPhase.EFFECT) {
            session.setPhase(ExhibitionPhase.EFFECT);
        }
        assertEquals(phase, session.phase());
        int settleFrames = ExhibitionCatalog.settleFrames(station.id());
        for (int frame = 0; frame < settleFrames; frame++) {
            session.frame(FIXED_DELTA_SECONDS, false);
        }
        GlDebug.checkError("exhibition#" + station.id() + "." + phase.key());
        float[] rgba = session.pipeline().captureLinearHdrRgbaFloat();
        assertNotNull(rgba);
        assertTrue(rgba.length > 0);
        double minimum = Double.MAX_VALUE;
        double maximum = -Double.MAX_VALUE;
        for (int pixel = 0; pixel < rgba.length / 4; pixel++) {
            float value = rgba[pixel * 4] * 0.2126f + rgba[pixel * 4 + 1] * 0.7152f
                    + rgba[pixel * 4 + 2] * 0.0722f;
            assertTrue(Float.isFinite(value), "non-finite HDR pixel at station "
                    + station.id() + " pixel " + pixel);
            minimum = Math.min(minimum, value);
            maximum = Math.max(maximum, value);
        }
        if (maximum - minimum <= 1.0e-4) {
            // The reduced-resolution upscaling chain may not expose the
            // full-resolution linear HDR target; the presented frame is then
            // the authoritative non-flat check below.
            assertTrue(com.kaleblangley.haikalat.subsystems.render3d.RenderFeature
                            .TEMPORAL_UPSCALING.equals(station.feature()),
                    "station #" + station.id() + " " + phase.key()
                            + " rendered a flat HDR frame feature=" + station.feature());
        }
        double[] presented = readPresentedLuminance();
        double presentedMin = java.util.Arrays.stream(presented).min().orElse(0.0);
        double presentedMax = java.util.Arrays.stream(presented).max().orElse(0.0);
        assertTrue(presentedMax - presentedMin > 4.0 / 255.0,
                "station #" + station.id() + " " + phase.key()
                        + " presented frame is blank: min=" + presentedMin
                        + " max=" + presentedMax
                        + " hdrRange=" + (maximum - minimum));
        return new PhaseCapture(hdrLuminance(rgba), presented, rgba);
    }

    private record PhaseCapture(double[] hdr, double[] presented, float[] linearHdrRgba) {
    }

    private void assertProbeReceiversShowOpposingReflections(float[] baselineHdr,
                                                              float[] effectHdr) {
        assertEquals(WIDTH * HEIGHT * 4, baselineHdr.length,
                "station #18 baseline must expose its full-resolution linear HDR target");
        assertEquals(WIDTH * HEIGHT * 4, effectHdr.length,
                "station #18 must expose its full-resolution linear HDR target");
        var camera = session.pipeline().scene().camera();
        Matrix4f viewProjection = CameraProjection.stable(camera, WIDTH, HEIGHT, new Matrix4f())
                .mul(camera.getViewMatrix());
        RgbPatch baselineWarm = sampleHdrPatch(baselineHdr, viewProjection,
                -2.1f, 1.2f, -1.8f);
        RgbPatch baselineCool = sampleHdrPatch(baselineHdr, viewProjection,
                2.1f, 1.2f, -1.8f);
        RgbPatch warm = sampleHdrPatch(effectHdr, viewProjection, -2.1f, 1.2f, -1.8f);
        RgbPatch cool = sampleHdrPatch(effectHdr, viewProjection, 2.1f, 1.2f, -1.8f);
        System.out.printf("station #18 probe readback baselineWarm=%s baselineCool=%s "
                + "effectWarm=%s effectCool=%s%n", baselineWarm, baselineCool, warm, cool);
        assertTrue(warm.red() > warm.blue() * 1.35 && warm.red() - warm.blue() > 0.02,
                "left probe receiver must show the warm +Z panel, RGB=" + warm);
        assertTrue(cool.blue() > cool.red() * 1.35 && cool.blue() - cool.red() > 0.02,
                "right rotated probe receiver must show the cool -Z panel, RGB=" + cool);
    }

    private static RgbPatch sampleHdrPatch(float[] hdr, Matrix4f viewProjection,
                                           float worldX, float worldY, float worldZ) {
        Vector4f clip = viewProjection.transform(new Vector4f(worldX, worldY, worldZ, 1.0f));
        int centerX = Math.round((clip.x / clip.w * 0.5f + 0.5f) * (WIDTH - 1));
        int centerY = Math.round((clip.y / clip.w * 0.5f + 0.5f) * (HEIGHT - 1));
        int halfExtent = 5;
        double red = 0.0;
        double green = 0.0;
        double blue = 0.0;
        int samples = 0;
        for (int y = Math.max(0, centerY - halfExtent);
             y <= Math.min(HEIGHT - 1, centerY + halfExtent); y++) {
            for (int x = Math.max(0, centerX - halfExtent);
                 x <= Math.min(WIDTH - 1, centerX + halfExtent); x++) {
                int offset = (y * WIDTH + x) * 4;
                red += hdr[offset];
                green += hdr[offset + 1];
                blue += hdr[offset + 2];
                samples++;
            }
        }
        return new RgbPatch(red / samples, green / samples, blue / samples);
    }

    private record RgbPatch(double red, double green, double blue) {
    }

    private static double[] hdrLuminance(float[] rgba) {
        double[] luminance = new double[rgba.length / 4];
        for (int pixel = 0; pixel < luminance.length; pixel++) {
            luminance[pixel] = rgba[pixel * 4] * 0.2126
                    + rgba[pixel * 4 + 1] * 0.7152 + rgba[pixel * 4 + 2] * 0.0722;
        }
        return luminance;
    }

    /**
     * Reads the final display-referred front buffer after an explicit swap.
     * Display-space effects (SMAA, vignette, grain, chromatic aberration,
     * tone-mapped DOF) are invisible in the pre-tone-mapping linear HDR target,
     * so the A/B delta uses the real presented image.
     */
    private double[] readPresentedLuminance() {
        // The HDR readback above may leave one of the pipeline's read
        // framebuffers bound; read the real window backbuffer explicitly.
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        ByteBuffer pixels = BufferUtils.createByteBuffer(WIDTH * HEIGHT * 4);
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, WIDTH, HEIGHT, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        double[] luminance = new double[WIDTH * HEIGHT];
        for (int pixel = 0; pixel < luminance.length; pixel++) {
            int offset = pixel * 4;
            int red = Byte.toUnsignedInt(pixels.get(offset));
            int green = Byte.toUnsignedInt(pixels.get(offset + 1));
            int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
            luminance[pixel] = (red * 0.2126 + green * 0.7152 + blue * 0.0722) / 255.0;
        }
        return luminance;
    }

    private void assertAppliedOrRejected(ExhibitionStation station, ExhibitionPhase phase) {
        RenderFeatureState state = session.featureState();
        assertTrue(state.status() == RenderFeatureStatus.APPLIED
                        || state.status() == RenderFeatureStatus.PENDING,
                "station #" + station.id() + " " + phase.key() + " feature state "
                        + state.status() + " reason=" + state.failureReason());
        assertFalse(state.status() == RenderFeatureStatus.FAILED,
                "station #" + station.id() + " " + phase.key()
                        + " production feature request failed: " + state.failureReason());
        if (!state.requested().equals(state.effective())) {
            assertFalse(state.failureReason().isBlank() || state.activities().stream()
                            .allMatch(RenderFeatureActivity::active),
                    "station #" + station.id() + " requested/effective diverged without a reported "
                            + "rejection: " + state.activities());
        }
    }

    private static boolean hasRejectionReason(ExhibitionStation station,
                                              RenderFeatureState state) {
        if (!state.failureReason().isBlank()) {
            return true;
        }
        if (station.feature() != null) {
            for (RenderFeatureActivity activity : state.activities()) {
                if (activity.feature() == station.feature() && !activity.active()
                        && (activity.reason() == RenderFeatureReason.UNAVAILABLE
                        || activity.reason() == RenderFeatureReason.DISABLED
                        || activity.reason() == RenderFeatureReason.PAUSED_BY_MASTER)) {
                    return true;
                }
            }
            return false;
        }
        return !state.requested().equals(state.effective())
                && state.activities().stream().noneMatch(RenderFeatureActivity::active);
    }

    private static double meanAbsoluteDifference(double[] first, double[] second) {
        if (first.length != second.length) {
            return Double.MAX_VALUE;
        }
        double sum = 0.0;
        for (int index = 0; index < first.length; index++) {
            sum += Math.abs(first[index] - second[index]);
        }
        return sum / Math.max(1, first.length);
    }

    /** Fraction of pixels whose 8-bit luminance changed by at least two levels. */
    private static double changedPixelFraction(double[] first, double[] second) {
        if (first.length != second.length || first.length == 0) {
            return 1.0;
        }
        int changed = 0;
        for (int index = 0; index < first.length; index++) {
            if (Math.abs(first[index] - second[index]) >= 2.0 / 255.0) {
                changed++;
            }
        }
        return changed / (double) first.length;
    }
}
