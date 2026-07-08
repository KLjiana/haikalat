package com.kaleblangley.haikalat.backend;

import java.util.Locale;
import java.util.Objects;

public final class RenderErrors {
    private RenderErrors() {
    }

    public static RenderException classify(String message) {
        return classify(message, null);
    }

    public static RenderException classify(String message, Throwable cause) {
        RenderErrorCategory category = categoryFor(message);
        return cause == null
                ? new RenderException(category, message)
                : new RenderException(category, message, cause);
    }

    public static RenderErrorCategory categoryFor(String message) {
        String normalized = Objects.requireNonNull(message, "message").toLowerCase(Locale.ROOT);
        if (normalized.contains("shader") || normalized.contains("program") || normalized.contains("uniform")) {
            return RenderErrorCategory.SHADER;
        }
        if (normalized.contains("framebuffer") || normalized.contains("fbo")) {
            return RenderErrorCategory.FRAMEBUFFER;
        }
        if (normalized.contains("state") || normalized.contains("blend") || normalized.contains("depth")) {
            return RenderErrorCategory.STATE;
        }
        if (normalized.contains("resource") || normalized.contains("texture") || normalized.contains("buffer")
                || normalized.contains("mesh") || normalized.contains("asset")) {
            return RenderErrorCategory.RESOURCE;
        }
        return RenderErrorCategory.UNKNOWN;
    }
}
