package com.kaleblangley.haikalat.subsystems.windowing.input;

import java.util.Objects;
import java.util.Optional;

/** preedit 不可用时仍保留 GLFW committed-char 输入的安全降级。 */
public final class UnavailableTextInputAdapter implements TextInputAdapter {
    private final String reason;
    private boolean closed;

    public UnavailableTextInputAdapter(String reason) {
        this.reason = Objects.requireNonNullElse(reason, "composition unavailable");
    }

    public String reason() { return reason; }
    @Override public void activate(TextInputClient client) { ensureOpen(); Objects.requireNonNull(client, "client"); }
    @Override public void deactivate(TextInputClient client) { ensureOpen(); Objects.requireNonNull(client, "client"); }
    @Override public void setCandidateRect(TextInputRect rect) { ensureOpen(); Objects.requireNonNull(rect, "rect"); }
    @Override public Optional<ImeComposition> composition() { ensureOpen(); return Optional.empty(); }
    @Override public boolean compositionAvailable() { return false; }
    @Override public void close() { closed = true; }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UnavailableTextInputAdapter is closed");
    }
}
