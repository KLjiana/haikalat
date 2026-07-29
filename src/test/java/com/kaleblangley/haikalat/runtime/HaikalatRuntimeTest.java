package com.kaleblangley.haikalat.runtime;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import com.kaleblangley.haikalat.core.device.ExecutionModel;
import com.kaleblangley.haikalat.core.device.RenderBackendKind;
import com.kaleblangley.haikalat.core.device.RenderDevice;
import com.kaleblangley.haikalat.core.device.ResourceBarrier;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class HaikalatRuntimeTest {
    @Test
    void embeddedRuntimeBorrowsDeviceAndNeverCreatesPlatformObjects() {
        FakeDevice device = new FakeDevice();
        HaikalatRuntime runtime = HaikalatRuntime.createEmbedded(device);

        assertSame(device, runtime.renderDevice());
        assertEquals(HaikalatRuntime.State.READY, runtime.state());
        assertEquals(42, runtime.execute(() -> 42));
        runtime.close();
        runtime.close();

        assertEquals(HaikalatRuntime.State.CLOSED, runtime.state());
        assertFalse(device.closed);
        assertThrows(IllegalStateException.class, runtime::renderDevice);
    }

    @Test
    void rejectsWorkFromAnotherThread() throws Exception {
        HaikalatRuntime runtime = HaikalatRuntime.createEmbedded(new FakeDevice());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                runtime.execute(() -> {});
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        thread.start();
        thread.join();

        assertInstanceOf(IllegalStateException.class, failure.get());
        runtime.close();
    }

    private static final class FakeDevice implements RenderDevice {
        boolean closed;

        @Override public RenderBackendKind backendKind() { return RenderBackendKind.OPENGL; }
        @Override public ExecutionModel executionModel() { return ExecutionModel.EXPLICIT; }
        @Override public CommandBuffer createCommandBuffer() { return new CommandBuffer(); }
        @Override public void execute(CommandBuffer buffer) {}
        @Override public void invalidateState() {}
        @Override public void transition(ResourceBarrier... barriers) {}
    }
}
