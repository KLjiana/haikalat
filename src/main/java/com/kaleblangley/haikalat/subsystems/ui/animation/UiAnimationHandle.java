package com.kaleblangley.haikalat.subsystems.ui.animation;

/** Cancellable handle for one retained UI transition. */
public final class UiAnimationHandle implements AutoCloseable {
    private final long id;
    private final UiAnimationSystem owner;
    private State state = State.ACTIVE;
    private float progress;

    UiAnimationHandle(long id, UiAnimationSystem owner) {
        this.id = id;
        this.owner = owner;
    }

    public long id() { return id; }
    public State state() { return state; }
    public float progress() { return progress; }
    public boolean isActive() { return state == State.ACTIVE || state == State.PAUSED; }
    public boolean isPaused() { return state == State.PAUSED; }
    public boolean isFinished() { return state == State.FINISHED; }
    public boolean isCancelled() { return state == State.CANCELLED; }

    public void pause() {
        if (state == State.ACTIVE) owner.pause(this);
    }

    public void resume() {
        if (state == State.PAUSED) owner.resume(this);
    }

    public void cancel() {
        if (isActive()) owner.cancel(this);
    }

    @Override
    public void close() {
        cancel();
    }

    void progress(float value) { progress = value; }
    void pauseInternal() { state = State.PAUSED; }
    void resumeInternal() { state = State.ACTIVE; }
    void finish() { progress = 1.0f; state = State.FINISHED; }
    void markCancelled() { state = State.CANCELLED; }

    public enum State { ACTIVE, PAUSED, FINISHED, CANCELLED }
}
