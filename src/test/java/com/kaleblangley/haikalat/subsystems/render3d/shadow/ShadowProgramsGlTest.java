package com.kaleblangley.haikalat.subsystems.render3d.shadow;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.opengl.GL;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class ShadowProgramsGlTest {
    @Test
    void partialCreationClosesProgramsAtEveryFailurePosition() {
        try (var window = new GlfwWindow.Builder().title("Shadow program rollback")
                .dimensions(32, 32).visible(false).build()) {
            window.bindContext();
            GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                for (int position = 0; position < 3; position++) {
                    int failurePosition = position;
                    var attempts = new AtomicInteger();
                    var failure = new IllegalStateException("shader loader failure");
                    var before = GlDebug.resources();
                    assertSame(failure, assertThrows(IllegalStateException.class, () ->
                            ShadowPrograms.create(true, (vertex, fragment) -> {
                                if (attempts.getAndIncrement() == failurePosition) throw failure;
                                return ShaderProgram.fromResource(ShadowPrograms.class,
                                        "/shaders/shadows/" + vertex, "/shaders/shadows/" + fragment);
                            })));
                    var after = GlDebug.resources();
                    assertEquals(position + 1, attempts.get());
                    assertEquals(position, after.createdCount() - before.createdCount());
                    assertEquals(position, after.closedCount() - before.closedCount());
                    assertEquals(before.liveResources(), after.liveResources());
                }
                GlDebug.assertNoError("shadow program creation rollback");
            }
        }
    }
}
