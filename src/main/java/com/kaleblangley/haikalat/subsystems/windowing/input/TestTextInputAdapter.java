package com.kaleblangley.haikalat.subsystems.windowing.input;

import java.util.Objects;
import java.util.Optional;

/** 不加载系统输入法、可由确定性 UI 脚本驱动的测试适配器。 */
public final class TestTextInputAdapter implements TextInputAdapter {
    private TextInputClient activeClient;
    private ImeComposition composition;
    private TextInputRect candidateRect = TextInputRect.EMPTY;
    private boolean closed;

    @Override
    public void activate(TextInputClient client) {
        ensureOpen();
        if (activeClient != null && activeClient != client) {
            throw new IllegalStateException("another text input client is active");
        }
        activeClient = Objects.requireNonNull(client, "client");
    }

    @Override
    public void deactivate(TextInputClient client) {
        ensureOpen();
        if (activeClient == client) {
            cancel();
            activeClient = null;
        }
    }

    @Override public void setCandidateRect(TextInputRect rect) {
        ensureOpen();
        candidateRect = Objects.requireNonNull(rect, "rect");
    }
    @Override public Optional<ImeComposition> composition() {
        ensureOpen();
        return Optional.ofNullable(composition);
    }
    @Override public boolean compositionAvailable() { return true; }
    public TextInputRect candidateRect() { return candidateRect; }

    public void start() {
        TextInputClient client = requireActive();
        composition = new ImeComposition("", 0, 0, 0);
        client.compositionStarted();
    }
    public void update(ImeComposition value) {
        TextInputClient client = requireActive();
        composition = Objects.requireNonNull(value, "composition");
        client.compositionUpdated(value);
    }
    public void commit(String text) {
        TextInputClient client = requireActive();
        composition = null;
        client.compositionCommitted(Objects.requireNonNull(text, "text"));
    }
    public void cancel() {
        if (activeClient != null && composition != null) activeClient.compositionCancelled();
        composition = null;
    }

    @Override
    public void close() {
        if (closed) return;
        cancel();
        activeClient = null;
        closed = true;
    }

    private TextInputClient requireActive() {
        ensureOpen();
        if (activeClient == null) throw new IllegalStateException("no active text input client");
        return activeClient;
    }
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("TestTextInputAdapter is closed");
    }
}
