package com.kaleblangley.haikalat.demo.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * {@link UiDemo} 的强类型启动参数。
 *
 * <p>确定性模式固定使用隐藏窗口、关闭垂直同步，并在未指定帧数时运行 12 帧。
 * resize 的 frame 从 0 开始，与其他引擎 Demo 的确定性参数保持一致。</p>
 */
public record UiDemoOptions(boolean deterministic, boolean hidden, boolean vsync,
                            int maximumFrames, int maximumSeconds, List<ResizeStep> resizeSteps,
                            ContentScale contentScale, ScriptMode scriptMode,
                            boolean verifyPixels) {
    public static final int DEFAULT_DETERMINISTIC_FRAMES = 12;

    public UiDemoOptions {
        if (maximumFrames == 0 || maximumFrames < -1) {
            throw new IllegalArgumentException("maximumFrames must be positive or -1");
        }
        if (maximumSeconds == 0 || maximumSeconds < -1) {
            throw new IllegalArgumentException("maximumSeconds must be positive or -1");
        }
        if (maximumFrames > 0 && maximumSeconds > 0) {
            throw new IllegalArgumentException("--frames and --seconds are mutually exclusive");
        }
        resizeSteps = List.copyOf(Objects.requireNonNull(resizeSteps, "resizeSteps"));
        Objects.requireNonNull(scriptMode, "scriptMode");
        int previousFrame = -1;
        for (ResizeStep step : resizeSteps) {
            if (step.frame() <= previousFrame) {
                throw new IllegalArgumentException("resize frames must be unique and increasing");
            }
            previousFrame = step.frame();
        }
        if (maximumFrames > 0 && previousFrame >= maximumFrames - 1) {
            throw new IllegalArgumentException(
                    "--frames must include one complete frame after the final --resize");
        }
        if (verifyPixels && maximumFrames < 1) {
            throw new IllegalArgumentException("--verify-pixels requires a finite --frames value");
        }
    }

    /** 解析命令行并拒绝未知或含糊参数。 */
    public static UiDemoOptions parse(String... arguments) {
        Objects.requireNonNull(arguments, "arguments");
        boolean deterministic = false;
        boolean hidden = false;
        boolean vsync = true;
        boolean verifyPixels = false;
        boolean scriptExplicit = false;
        int maximumFrames = -1;
        int maximumSeconds = -1;
        ContentScale contentScale = null;
        ScriptMode scriptMode = ScriptMode.NONE;
        List<ResizeStep> resizeSteps = new ArrayList<>();

        for (String argument : arguments) {
            if ("--deterministic".equals(argument)) {
                deterministic = true;
            } else if ("--hidden".equals(argument)) {
                hidden = true;
            } else if ("--vsync".equals(argument)) {
                vsync = true;
            } else if ("--no-vsync".equals(argument)) {
                vsync = false;
            } else if ("--verify-pixels".equals(argument)) {
                verifyPixels = true;
            } else if (argument.startsWith("--frames=")) {
                maximumFrames = positiveInteger(argument.substring("--frames=".length()),
                        "--frames");
            } else if (argument.startsWith("--seconds=")) {
                maximumSeconds = positiveInteger(argument.substring("--seconds=".length()),
                        "--seconds");
            } else if (argument.startsWith("--resize=")) {
                resizeSteps.add(ResizeStep.parse(argument.substring("--resize=".length())));
            } else if (argument.startsWith("--content-scale=")) {
                if (contentScale != null) {
                    throw new IllegalArgumentException("--content-scale may only be specified once");
                }
                contentScale = ContentScale.parse(
                        argument.substring("--content-scale=".length()));
            } else if (argument.startsWith("--script=")) {
                if (scriptExplicit) {
                    throw new IllegalArgumentException("--script may only be specified once");
                }
                scriptMode = ScriptMode.parse(argument.substring("--script=".length()));
                scriptExplicit = true;
            } else {
                throw new IllegalArgumentException("Unknown UiDemo argument: " + argument);
            }
        }

        resizeSteps.sort(Comparator.comparingInt(ResizeStep::frame));
        Set<Integer> frames = new HashSet<>();
        for (ResizeStep step : resizeSteps) {
            if (!frames.add(step.frame())) {
                throw new IllegalArgumentException(
                        "Only one --resize is allowed for frame " + step.frame());
            }
        }
        if (deterministic) {
            if (maximumSeconds > 0) {
                throw new IllegalArgumentException(
                        "--deterministic requires frame-based termination, not --seconds");
            }
            hidden = true;
            vsync = false;
            if (maximumFrames < 0) maximumFrames = DEFAULT_DETERMINISTIC_FRAMES;
            if (!scriptExplicit) scriptMode = ScriptMode.BUILTIN;
        }
        return new UiDemoOptions(deterministic, hidden, vsync, maximumFrames, maximumSeconds,
                resizeSteps, contentScale, scriptMode, verifyPixels);
    }

    private static int positiveInteger(String value, String option) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new NumberFormatException();
            return parsed;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(option + " must be a positive integer", invalid);
        }
    }

    /** 一次确定性窗口 resize。 */
    public record ResizeStep(int frame, int width, int height) {
        public ResizeStep {
            if (frame < 0 || width <= 0 || height <= 0) {
                throw new IllegalArgumentException(
                        "resize frame must be non-negative and dimensions must be positive");
            }
        }

        static ResizeStep parse(String value) {
            try {
                int colon = value.indexOf(':');
                int separator = Math.max(value.indexOf('x', colon + 1),
                        value.indexOf('X', colon + 1));
                if (colon <= 0 || separator <= colon + 1 || separator == value.length() - 1) {
                    throw new IllegalArgumentException();
                }
                return new ResizeStep(Integer.parseInt(value.substring(0, colon)),
                        Integer.parseInt(value.substring(colon + 1, separator)),
                        Integer.parseInt(value.substring(separator + 1)));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "--resize must use <frame>:<positive-width>x<positive-height>", invalid);
            }
        }
    }

    /** Demo 注入的逻辑像素到 framebuffer 像素缩放。 */
    public record ContentScale(float x, float y) {
        public ContentScale {
            if (!Float.isFinite(x) || x <= 0.0f || !Float.isFinite(y) || y <= 0.0f) {
                throw new IllegalArgumentException("content scale must be finite and positive");
            }
        }

        static ContentScale parse(String value) {
            try {
                int separator = Math.max(value.indexOf('x'), value.indexOf('X'));
                if (separator <= 0 || separator == value.length() - 1) {
                    throw new IllegalArgumentException();
                }
                return new ContentScale(Float.parseFloat(value.substring(0, separator)),
                        Float.parseFloat(value.substring(separator + 1)));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException(
                        "--content-scale must use <positive-x>x<positive-y>", invalid);
            }
        }
    }

    /** 可重复的 Demo 操作脚本。 */
    public enum ScriptMode {
        NONE,
        BUILTIN;

        static ScriptMode parse(String value) {
            try {
                return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("--script must be none or builtin", invalid);
            }
        }
    }
}
