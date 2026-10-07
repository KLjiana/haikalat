package com.kaleblangley.haikalat.subsystems.render3d;

import java.util.Objects;

/** Per-generation successful-frame transaction; failure never advances camera, time or identity. */
final class VolumetricFrameState {
    record Frame(long sequence, ExternalCamera camera, VolumetricGrid grid,
                 VolumetricFogSettings settings, float timeSeconds, long successfulIndex, float samplePhase,
                 float samplePhaseX,float samplePhaseY) { }
    private Frame current, previous;
    private boolean valid;
    private long successfulFrames;
    private final org.joml.Matrix4f currentView=new org.joml.Matrix4f(),previousView=new org.joml.Matrix4f();
    private final org.joml.Matrix4f currentProjection=new org.joml.Matrix4f(),previousProjection=new org.joml.Matrix4f();

    Frame prepare(RenderFrameContext context, VolumetricFogSettings settings) {
        Objects.requireNonNull(context, "context"); Objects.requireNonNull(settings, "settings");
        if (current != null) throw new IllegalStateException("a volume frame is already pending");
        VolumetricGrid grid = unchangedGrid(context,settings) ? previous.grid()
                : new VolumetricGrid(context.camera(), context.width(), context.height(),
                        settings.quality().tilePixels(), settings.quality().depthSlices(), settings.fogDistance());
        float time = (previous == null ? 0 : previous.timeSeconds()) + context.deltaSeconds();
        if (!Float.isFinite(time)) throw new IllegalArgumentException("volume simulation time is not finite");
        // Failed attempts do not consume this sequence. Boundaries and ray lengths stay fixed.
        float phase = settings.history() ? 0.15f + 0.7f * radicalInverse(successfulFrames + 1) : 0.5f;
        float x=settings.history() ? .15f+.7f*radicalInverse(successfulFrames+1,3) : .5f;
        float y=settings.history() ? .15f+.7f*radicalInverse(successfulFrames+1,5) : .5f;
        return current = new Frame(context.frameSequence(), context.camera(), grid, settings, time,
                successfulFrames, phase,x,y);
    }

    private boolean unchangedGrid(RenderFrameContext context,VolumetricFogSettings settings) {
        if(previous==null || previous.grid().width()!=context.width() || previous.grid().height()!=context.height()
                || previous.settings().quality()!=settings.quality() || previous.settings().fogDistance()!=settings.fogDistance())return false;
        var camera=context.camera();var old=previous.camera();
        return camera.nearPlane()==old.nearPlane() && camera.farPlane()==old.farPlane()
                && camera.getViewMatrix(currentView).equals(old.getViewMatrix(previousView))
                && camera.projection(currentProjection).equals(old.projection(previousProjection));
    }

    Frame current() { return current; }
    Frame previous() { return previous; }
    boolean valid() { return valid; }
    void commit() {
        if (current == null) throw new IllegalStateException("no pending volume frame");
        previous = current; current = null; valid = true;
        successfulFrames++;
    }
    void discard() { current = null; valid = false; }
    void invalidate() { valid = false; }
    private static float radicalInverse(long index) {
        return (float) (Integer.toUnsignedLong(Integer.reverse((int) index)) * 0x1.0p-32);
    }
    private static float radicalInverse(long index,int base) {
        double value=0,place=1.0/base;
        for(long remaining=index;remaining>0;remaining/=base){value+=(remaining%base)*place;place/=base;}
        return (float)value;
    }
}
