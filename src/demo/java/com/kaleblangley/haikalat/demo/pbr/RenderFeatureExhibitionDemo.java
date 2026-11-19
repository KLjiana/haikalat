package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureState;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureStatus;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import com.kaleblangley.haikalat.subsystems.windowing.input.Key;
import com.kaleblangley.haikalat.subsystems.windowing.input.WindowInputSnapshot;
import com.kaleblangley.haikalat.runtime.FrameClock;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;

import java.util.Locale;

/**
 * Interactive 46-station exhibition hall.
 *
 * <p>The visible default is a walkable gallery. Explicit station and hidden
 * tour arguments support controlled baseline/effect comparisons.</p>
 *
 * <p>The demo owns no renderer of its own: stations publish real scenes and
 * complete {@code RenderFeatureSettings} requests into the existing
 * {@code RenderPipeline} through the production feature control.</p>
 */
public final class RenderFeatureExhibitionDemo {
    private static final float FIXED_DELTA_SECONDS = 1.0f / 60.0f;
    private static final int TOUR_FRAMES_PER_PHASE = 2;

    private RenderFeatureExhibitionDemo() {
    }

    public static void main(String[] arguments) {
        Options options = Options.parse(arguments);
        try (GlfwWindow window = new GlfwWindow.Builder()
                .dimensions(options.width(), options.height())
                .title("Haikalat Render Feature Exhibition")
                .visible(!options.hidden())
                .cursorMode(options.hidden() ? GlfwWindow.CursorMode.NORMAL
                        : GlfwWindow.CursorMode.DISABLED)
                .build()) {
            window.bindContext();
            GL.createCapabilities();
            GlDebug.enableDebugCallback();
            window.setVsync(!options.hidden());
            Summary summary = run(window, options);
            if (!GlDebug.resources().liveResources().isEmpty()) {
                throw new IllegalStateException("exhibition leaked GL resources: "
                        + GlDebug.resources().liveResources());
            }
            System.out.println(summary.format());
        }
    }

    private static Summary run(GlfwWindow window, Options options) {
        try (ExhibitionSession session = ExhibitionSession.create(window, options.hidden())) {
            ExhibitionMuseum museum = options.hall() ? ExhibitionMuseum.open(session) : null;
            if (options.hall()) {
                session.showHall(museum);
            } else {
                session.select(options.stationId());
                if (options.initialPhase() == ExhibitionPhase.EFFECT) {
                    session.setPhase(ExhibitionPhase.EFFECT);
                }
                if (options.tour()) {
                    if (options.captureDirectory() != null) {
                        return runCaptureTour(window, options, session);
                    }
                    return runTour(window, options, session);
                }
            }
            try (ExhibitionHud hud = options.hidden() ? null : ExhibitionHud.attach(window, session, museum != null)) {
                return runLoop(window, options, session, hud, museum);
            }
        }
    }

    /**
     * Hidden tour that archives one PNG per station and phase under
     * {@code --capture-dir}.  The index deliberately records the human visual
     * result as {@code NOT_EVALUATED}: this archive is evidence for a later
     * human pass, never a substitute for it.
     */
    private static Summary runCaptureTour(GlfwWindow window, Options options,
                                          ExhibitionSession session) {
        java.nio.file.Path directory = java.nio.file.Path.of(options.captureDirectory());
        try {
            java.nio.file.Files.createDirectories(directory);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot create capture directory " + directory, failure);
        }
        StringBuilder index = new StringBuilder(
                "station,wing,name_en,phase,png,human_result\n");
        int stationsVisited = 0;
        int wiredStations = 0;
        int failedStations = 0;
        long frames = 0L;
        for (ExhibitionStation station : ExhibitionCatalog.stations()) {
            session.select(station.id());
            stationsVisited++;
            if (session.wired()) {
                wiredStations++;
            }
            for (ExhibitionPhase phase : ExhibitionPhase.values()) {
                session.setPhase(phase);
                int settleFrames = ExhibitionCatalog.settleFrames(station.id());
                for (int step = 0; step < settleFrames; step++) {
                    session.frame(FIXED_DELTA_SECONDS, false);
                    frames++;
                }
                org.lwjgl.opengl.GL46.glBindFramebuffer(
                        org.lwjgl.opengl.GL46.GL_FRAMEBUFFER, 0);
                String fileName = String.format("station%02d-%s.png", station.id(), phase.key());
                SceneCaptureIO.display(window.width(), window.height(),
                        directory.resolve(fileName).toString());
                index.append(station.id()).append(',')
                        .append(station.wing().label()).append(',')
                        .append(station.nameEn().replace(',', ';')).append(',')
                        .append(phase.key()).append(',')
                        .append(fileName).append(',')
                        .append("NOT_EVALUATED\n");
                GlDebug.checkError("exhibition.capture#" + station.id());
                if (session.featureState().status() == RenderFeatureStatus.FAILED) {
                    failedStations++;
                }
            }
        }
        try {
            java.nio.file.Files.writeString(directory.resolve("index.csv"), index.toString());
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot write capture index", failure);
        }
        int width = window.width();
        int height = window.height();
        return new Summary(stationsVisited, wiredStations, failedStations, frames,
                width, height, true, true);
    }

    private static Summary runTour(GlfwWindow window, Options options, ExhibitionSession session) {
        int stationsVisited = 0;
        int wiredStations = 0;
        int failedStations = 0;
        long frames = 0L;
        for (ExhibitionStation station : ExhibitionCatalog.stations()) {
            session.select(station.id());
            stationsVisited++;
            if (session.wired()) {
                wiredStations++;
            }
            for (ExhibitionPhase phase : ExhibitionPhase.values()) {
                session.setPhase(phase);
                for (int step = 0; step < TOUR_FRAMES_PER_PHASE; step++) {
                    session.frame(FIXED_DELTA_SECONDS, false);
                    frames++;
                    GlDebug.checkError("exhibition.tour#" + station.id());
                }
                RenderFeatureState state = session.featureState();
                if (state.status() == RenderFeatureStatus.FAILED) {
                    failedStations++;
                    System.out.println("station #" + station.id() + " " + phase.key()
                            + " FAILED: " + state.failureReason());
                }
            }
        }
        int width = window.width();
        int height = window.height();
        boolean rendered = hasRenderedOutput(session);
        return new Summary(stationsVisited, wiredStations, failedStations, frames,
                width, height, rendered, true);
    }

    private static Summary runLoop(GlfwWindow window, Options options, ExhibitionSession session,
                                   ExhibitionHud hud, ExhibitionMuseum museum) {
        FrameClock clock = new FrameClock();
        int frame = 0;
        boolean rendered = false;
        boolean mouseLook = !options.hidden();
        boolean hallMode = museum != null;
        while (!window.shouldClose()) {
            if (window.consumeResize()) {
                session.resize(window.width(), window.height());
            }
            WindowInputSnapshot input = window.inputSnapshot();
            if (input.keyPressed(Key.ESCAPE)) {
                window.requestClose();
                break;
            }
            if (hallMode) {
                if (input.keyPressed(Key.TAB)) {
                    mouseLook = museum.toggleViewpoint();
                    window.setCursorMode(mouseLook ? GlfwWindow.CursorMode.DISABLED : GlfwWindow.CursorMode.NORMAL);
                    session.pipeline().resetTemporalHistory();
                }
            } else {
                if (input.keyPressed(Key.B)) session.togglePhase();
                if (input.keyPressed(Key.N)) session.next();
                if (input.keyPressed(Key.P)) session.previous();
                if (input.keyPressed(Key.R)) session.reset();
            }
            if (input.keyPressed(Key.M)) {
                mouseLook = !mouseLook;
                window.setCursorMode(mouseLook ? GlfwWindow.CursorMode.DISABLED
                        : GlfwWindow.CursorMode.NORMAL);
            }
            if (hud != null && input.keyPressed(Key.H)) {
                hud.toggleCard();
            }
            float delta = options.hidden() ? FIXED_DELTA_SECONDS : clock.tick().deltaSeconds();
            if (!options.hidden()) {
                Camera camera = hallMode ? museum.camera() : session.camera();
                if (mouseLook) {
                    camera.processMouseMovement((float) window.mouseDeltaX(), (float) window.mouseDeltaY());
                }
                boolean descend = window.isKeyDown(GLFW.GLFW_KEY_LEFT_SHIFT)
                        || window.isKeyDown(GLFW.GLFW_KEY_RIGHT_SHIFT);
                int vertical = (window.isKeyDown(GLFW.GLFW_KEY_SPACE) ? 1 : 0) - (descend ? 1 : 0);
                if (hallMode) {
                    int forward = keyAxis(window, GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_S);
                    int right = keyAxis(window, GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_A);
                    museum.move(forward, right, vertical, delta);
                } else {
                    updateFreeCamera(window, camera, vertical, delta);
                }
            }
            if (hallMode) {
                session.updateHall(delta);
                if (input.keyPressed(Key.B)) session.toggleHallGiComparison();
                if (hud != null) {
                    int active = session.hallEffectId();
                    hud.setNearest(active > 0 ? active : museum.aimedStationId());
                }
            }
            if (hud != null) {
                hud.update(input, delta);
            }
            session.frame(delta, false);
            frame++;
            if (options.capturePath() != null && frame == options.captureFrame()) {
                // The pipeline may leave one of its read framebuffers bound;
                // capture the real window backbuffer explicitly.
                org.lwjgl.opengl.GL46.glBindFramebuffer(
                        org.lwjgl.opengl.GL46.GL_FRAMEBUFFER, 0);
                SceneCaptureIO.display(window.width(), window.height(), options.capturePath());
                rendered = true;
            }
            if (!options.hidden()) {
                session.present();
                window.pollEvents();
            }
            if (options.frames() > 0 && frame >= options.frames()) {
                break;
            }
        }
        if (options.verifyPixels() && !rendered) {
            rendered = hasRenderedOutput(session);
        }
        int displayed = hallMode ? museum.exhibits().size() : 1;
        return new Summary(displayed, hallMode ? displayed : (session.wired() ? 1 : 0),
                session.featureState().status() == RenderFeatureStatus.FAILED ? 1 : 0,
                frame, window.width(), window.height(), rendered, false);
    }

    private static int keyAxis(GlfwWindow window, int positive, int negative) {
        return (window.isKeyDown(positive) ? 1 : 0) - (window.isKeyDown(negative) ? 1 : 0);
    }

    /**
     * First-person camera: WASD moves, Space/Shift fly up/down and the mouse
     * looks around.  Station selection restores the station's frozen preset
     * pose, so the exhibition still starts from the documented observation
     * camera.  Press M to release the pointer for HUD clicks.
     */
    private static void updateFreeCamera(GlfwWindow window, Camera camera, int vertical, float deltaSeconds) {
        if (window.isKeyDown(GLFW.GLFW_KEY_W)) {
            camera.processKeyboard(Camera.Movement.FORWARD, deltaSeconds);
        }
        if (window.isKeyDown(GLFW.GLFW_KEY_S)) {
            camera.processKeyboard(Camera.Movement.BACKWARD, deltaSeconds);
        }
        if (window.isKeyDown(GLFW.GLFW_KEY_A)) {
            camera.processKeyboard(Camera.Movement.LEFT, deltaSeconds);
        }
        if (window.isKeyDown(GLFW.GLFW_KEY_D)) {
            camera.processKeyboard(Camera.Movement.RIGHT, deltaSeconds);
        }
        if (vertical != 0) {
            var position = camera.position();
            position.y += vertical * camera.movementSpeed() * deltaSeconds;
            camera.setPosition(position);
        }
    }

    /** Reads the real HDR output and reports whether a meaningful area is non-black. */
    private static boolean hasRenderedOutput(ExhibitionSession session) {
        float[] rgba = session.pipeline().captureLinearHdrRgbaFloat();
        int bright = 0;
        for (int index = 0; index + 2 < rgba.length; index += 4) {
            if (rgba[index] + rgba[index + 1] + rgba[index + 2] > 1.0e-4f) {
                bright++;
            }
        }
        return bright > Math.max(1, rgba.length / 4 / 100);
    }

    /** Recorded summary of one exhibition run; printed once at exit. */
    public record Summary(int stationsVisited, int wiredStations, int failedStations,
                          long frames, int width, int height, boolean renderedPixels,
                          boolean tour) {
        public String format() {
            return String.format(Locale.ROOT,
                    "exhibition: stations=%d wired=%d failed=%d frames=%d size=%dx%d "
                            + "rendered=%s tour=%s",
                    stationsVisited, wiredStations, failedStations, frames, width, height,
                    renderedPixels, tour);
        }
    }

    /** Visible/hidden run options; unknown arguments are rejected. */
    record Options(boolean hidden, int frames, int width, int height, int stationId,
                   ExhibitionPhase initialPhase, boolean verifyPixels, String capturePath,
                   int captureFrame, boolean tour, String captureDirectory, boolean hall) {
        static Options parse(String[] arguments) {
            boolean hidden = false;
            int frames = -1;
            int width = 1280;
            int height = 720;
            int stationId = 1;
            boolean stationExplicit = false;
            ExhibitionPhase phase = ExhibitionPhase.BASELINE;
            boolean verifyPixels = false;
            String capturePath = null;
            int captureFrame = Integer.MAX_VALUE;
            boolean tour = false;
            String captureDirectory = null;
            boolean hallRequested = false;
            for (String argument : arguments) {
                if (argument.equals("--hidden") || argument.equals("--deterministic")) {
                    hidden = true;
                } else if (argument.startsWith("--frames=")) {
                    frames = Integer.parseInt(argument.substring("--frames=".length()));
                } else if (argument.startsWith("--size=")) {
                    String[] parts = argument.substring("--size=".length()).split("x");
                    width = Integer.parseInt(parts[0]);
                    height = Integer.parseInt(parts[1]);
                } else if (argument.startsWith("--station=")) {
                    stationId = Integer.parseInt(argument.substring("--station=".length()));
                    stationExplicit = true;
                } else if (argument.startsWith("--phase=")) {
                    phase = argument.substring("--phase=".length()).equalsIgnoreCase("B")
                            ? ExhibitionPhase.EFFECT : ExhibitionPhase.BASELINE;
                } else if (argument.equals("--verify")) {
                    verifyPixels = true;
                } else if (argument.startsWith("--capture=")) {
                    capturePath = argument.substring("--capture=".length());
                } else if (argument.startsWith("--capture-frame=")) {
                    captureFrame = Integer.parseInt(
                            argument.substring("--capture-frame=".length()));
                } else if (argument.equals("--tour")) {
                    tour = true;
                } else if (argument.startsWith("--capture-dir=")) {
                    captureDirectory = argument.substring("--capture-dir=".length());
                    tour = true;
                } else if (argument.equals("--hall")) {
                    hallRequested = true;
                } else {
                    throw new IllegalArgumentException("unknown exhibition option: " + argument);
                }
            }
            if (hidden && frames < 0 && !tour) {
                frames = 4;
            }
            if (capturePath == null) {
                captureFrame = Integer.MAX_VALUE;
            } else if (captureFrame == Integer.MAX_VALUE) {
                captureFrame = frames > 0 ? frames : 1;
            }
            boolean hall = !tour && !stationExplicit && (hallRequested || (!hidden && capturePath == null));
            return new Options(hidden, frames, width, height, stationId, phase, verifyPixels,
                    capturePath, captureFrame, tour, captureDirectory, hall);
        }
    }
}
