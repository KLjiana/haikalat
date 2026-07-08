package com.kaleblangley.haikalat.backend;

public class GlException extends RenderException {
    public GlException(String message) {
        super(RenderErrors.categoryFor(message), message);
    }

    public GlException(String message, Throwable cause) {
        super(RenderErrors.categoryFor(message), message, cause);
    }

    public GlException(RenderErrorCategory category, String message) {
        super(category, message);
    }

    public GlException(RenderErrorCategory category, String message, Throwable cause) {
        super(category, message, cause);
    }
}
