package com.kaleblangley.haikalat.backend;

import java.util.Objects;

public class RenderException extends RuntimeException {
    private final RenderErrorCategory category;

    public RenderException(RenderErrorCategory category, String message) {
        super(message);
        this.category = Objects.requireNonNull(category, "category");
    }

    public RenderException(RenderErrorCategory category, String message, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "category");
    }

    public RenderErrorCategory category() {
        return category;
    }
}
