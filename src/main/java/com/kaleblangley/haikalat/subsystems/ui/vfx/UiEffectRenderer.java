package com.kaleblangley.haikalat.subsystems.ui.vfx;

import com.kaleblangley.haikalat.subsystems.ui.render.UiDisplayList;
import com.kaleblangley.haikalat.subsystems.ui.render.UiSdfDecoration;
import com.kaleblangley.haikalat.subsystems.ui.render.UiSdfShape;
import com.kaleblangley.haikalat.subsystems.ui.render.UiScreenRect;
import com.kaleblangley.haikalat.subsystems.ui.style.UiColor;

import java.util.List;
import java.util.Objects;

/** Records frozen UI-effect snapshots through the existing SDF/quad batch path. */
public final class UiEffectRenderer {
    public void record(List<UiEffectSnapshot> snapshots, UiDisplayList output) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(output, "output");
        for (UiEffectSnapshot snapshot : snapshots) {
            output.paintBoundary();
            boolean clipped = snapshot.definition().clipPolicy()
                    != UiEffectDefinition.ClipPolicy.NONE;
            if (clipped) output.pushClip(snapshot.anchorBounds());
            try {
                record(snapshot, output);
            } finally {
                if (clipped) output.popClip();
                output.paintBoundary();
            }
        }
    }

    private static void record(UiEffectSnapshot snapshot, UiDisplayList output) {
        switch (snapshot.definition().type()) {
            case SHIMMER -> shimmer(snapshot, output);
            case RIPPLE -> ripple(snapshot, output);
            case SCANLINE -> scanline(snapshot, output);
            case GLITCH -> glitch(snapshot, output);
            case DISSOLVE, SPARK, CONFETTI, TRAIL -> particles(snapshot, output);
        }
    }

    private static void shimmer(UiEffectSnapshot snapshot, UiDisplayList output) {
        UiScreenRect bounds = snapshot.anchorBounds();
        double stripeWidth = Math.max(8.0, bounds.width() * 0.20);
        double x = bounds.x() - stripeWidth
                + (bounds.width() + stripeWidth * 2.0) * snapshot.normalizedTime();
        output.addSdfShape(new UiScreenRect(x, bounds.y(), stripeWidth, bounds.height()),
                UiSdfShape.pill(), UiSdfDecoration.solid(faded(snapshot, 0.45f)),
                snapshot.definition().blendMode());
    }

    private static void ripple(UiEffectSnapshot snapshot, UiDisplayList output) {
        UiScreenRect bounds = snapshot.anchorBounds();
        double diameter = Math.max(bounds.width(), bounds.height())
                * Math.max(0.10, snapshot.normalizedTime());
        double centerX = bounds.x() + bounds.width() * 0.5;
        double centerY = bounds.y() + bounds.height() * 0.5;
        float thickness = Math.max(2.5f, (float) diameter * 0.04f);
        output.addSdfShape(new UiScreenRect(centerX - diameter * 0.5,
                        centerY - diameter * 0.5, diameter, diameter),
                UiSdfShape.ring(Math.max(0.0f, (float) diameter * 0.5f - thickness), thickness),
                UiSdfDecoration.solid(faded(snapshot, 1.0f)),
                snapshot.definition().blendMode());
    }

    private static void particles(UiEffectSnapshot snapshot, UiDisplayList output) {
        boolean rectangles = snapshot.definition().type() == UiEffectDefinition.Type.CONFETTI;
        for (UiEffectSnapshot.Particle particle : snapshot.particles()) {
            double size = particle.size();
            UiScreenRect bounds = new UiScreenRect(
                    particle.x() - size * 0.5, particle.y() - size * 0.5, size, size);
            if (rectangles) {
                output.addSolidQuad(bounds, particle.color().packedPremultipliedRgba8(),
                        snapshot.definition().blendMode());
            } else {
                output.addSdfShape(bounds, UiSdfShape.circle(),
                        UiSdfDecoration.solid(particle.color()),
                        snapshot.definition().blendMode());
            }
        }
    }

    private static void scanline(UiEffectSnapshot snapshot, UiDisplayList output) {
        UiScreenRect bounds = snapshot.anchorBounds();
        double height = Math.max(1.0, bounds.height() * 0.04);
        double y = bounds.y() + (bounds.height() - height) * snapshot.normalizedTime();
        output.addSolidQuad(new UiScreenRect(bounds.x(), y, bounds.width(), height),
                faded(snapshot, 0.7f).packedPremultipliedRgba8(),
                snapshot.definition().blendMode());
    }

    private static void glitch(UiEffectSnapshot snapshot, UiDisplayList output) {
        UiScreenRect bounds = snapshot.anchorBounds();
        UiColor color = faded(snapshot, 0.55f);
        for (int band = 0; band < 3; band++) {
            double y = bounds.y() + bounds.height() * (0.2 + band * 0.27);
            double offset = Math.sin((snapshot.normalizedTime() * 23.0 + band) * Math.PI)
                    * bounds.width() * 0.04;
            output.addSolidQuad(new UiScreenRect(bounds.x() + offset, y,
                            bounds.width(), Math.max(1.0, bounds.height() * 0.035)),
                    color.packedPremultipliedRgba8(), snapshot.definition().blendMode());
        }
    }

    private static UiColor faded(UiEffectSnapshot snapshot, float alphaScale) {
        UiColor start = snapshot.definition().startColor();
        UiColor end = snapshot.definition().endColor();
        float time = snapshot.normalizedTime();
        float alpha = lerp(start.alpha(), end.alpha(), time)
                * (1.0f - time) * alphaScale;
        return new UiColor(lerp(start.red(), end.red(), time),
                lerp(start.green(), end.green(), time),
                lerp(start.blue(), end.blue(), time),
                Math.max(0.0f, Math.min(1.0f, alpha)));
    }

    private static float lerp(float start, float end, float time) {
        return start + (end - start) * time;
    }
}
