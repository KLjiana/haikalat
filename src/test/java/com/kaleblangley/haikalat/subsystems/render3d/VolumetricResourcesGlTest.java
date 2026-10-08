package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import com.kaleblangley.haikalat.subsystems.windowing.GlfwWindow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46.*;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricResourcesGlTest {
    private static final long BUDGET = 128L * 1024 * 1024;
    private static final String FILL = """
            #version 460 core
            layout(local_size_x=4,local_size_y=4,local_size_z=4) in;
            layout(rgba16f,binding=0) writeonly uniform image3D uOutput;
            uniform float uValue;
            void main() {
                ivec3 p=ivec3(gl_GlobalInvocationID);
                if (any(greaterThanEqual(p,imageSize(uOutput)))) return;
                imageStore(uOutput,p,vec4(uValue));
            }
            """;

    private static GlfwWindow window() {
        return new GlfwWindow.Builder().dimensions(32,32).title("Volume resources").visible(false).build();
    }

    @Test
    void everyTextureAndParameterAllocationFailurePreservesTheActiveStorageWithoutLeaks() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking();
                 var resources = new VolumetricResources(3, 5, 7, true, BUDGET, BUDGET*2)) {
                int read = resources.historyRead().id(), source = resources.source().id();
                int parameters = resources.parameters().id();
                long liveBytes = GlDebug.resources().estimatedBytes();
                int liveCount = GlDebug.resources().liveResources().size();
                assertEquals(resources.bytes(), liveBytes);
                for (int allocation = 0; allocation < 14; allocation++) {
                    System.setProperty("haikalat.test.failVolumeAllocation", Integer.toString(allocation));
                    try {
                        assertThrows(IllegalStateException.class, () -> resources.prepareResize(5,7,9,true));
                    } finally { System.clearProperty("haikalat.test.failVolumeAllocation"); }
                    assertEquals(read, resources.historyRead().id());
                    assertEquals(source, resources.source().id());
                    assertEquals(parameters, resources.parameters().id());
                    assertEquals(liveBytes, GlDebug.resources().estimatedBytes());
                    assertEquals(liveCount, GlDebug.resources().liveResources().size());
                    assertEquals(0, resources.revision());
                }
                GlDebug.assertNoError("volume allocation rollback");
                resources.close(); resources.close();
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    @Test
    void resizePublishesAllResourcesOnceRejectsForeignStaleAndClosedCandidates() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking();
                 var resources = new VolumetricResources(3,5,7,true,BUDGET,BUDGET*2);
                 var other = new VolumetricResources(1,1,2,false,BUDGET,BUDGET*2)) {
                Texture3D oldMedium = resources.medium(), oldRead = resources.historyRead();
                var oldParameters = resources.parameters();
                try (var candidate = resources.prepareResize(5,7,9,false);
                     var stale = resources.prepareResize(7,9,11,true)) {
                    assertSame(oldMedium, resources.medium());
                    assertThrows(IllegalArgumentException.class, () -> other.commitResize(candidate));
                    resources.commitResize(candidate);
                    assertThrows(IllegalStateException.class, () -> resources.commitResize(candidate));
                    assertThrows(IllegalStateException.class, () -> resources.commitResize(stale));
                    assertFalse(oldMedium.isClosed());
                    assertEquals(5, resources.source().width());
                    assertEquals(7, resources.source().height());
                    assertEquals(9, resources.source().depth());
                    assertEquals(18, resources.prefix().depth());
                    assertNull(resources.emission());
                    assertNotEquals(oldRead.id(), resources.historyRead().id());
                    assertNotEquals(oldParameters.id(), resources.parameters().id());
                    assertFalse(resources.historyValid());
                }
                assertTrue(oldMedium.isClosed()); assertTrue(oldParameters.isClosed());
                assertEquals(1, resources.revision());
                var closed = resources.prepareResize(3,3,3,false);
                closed.close(); closed.close();
                assertThrows(IllegalStateException.class, () -> resources.commitResize(closed));
                var ownerClosed = resources.prepareResize(3,3,3,false);
                resources.close();
                try { assertThrows(IllegalStateException.class, () -> resources.commitResize(ownerClosed)); }
                finally { ownerClosed.close(); }
                GlDebug.assertNoError("volume resize candidate lifecycle");
            }
        }
    }

    @Test
    void resourceAndTransactionBudgetsRejectBeforeAllocatingAnyCandidate() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                long exact = VolumetricResources.estimatedBytes(3,5,7,true);
                assertThrows(IllegalArgumentException.class,
                        () -> new VolumetricResources(3,5,7,true,exact-1,exact*2));
                assertTrue(GlDebug.resources().liveResources().isEmpty());
                try (var resources = new VolumetricResources(1,1,2,false,32768,32768)) {
                    long created = GlDebug.resources().createdCount();
                    assertThrows(IllegalArgumentException.class, () -> resources.prepareResize(3,5,7,true));
                    assertThrows(IllegalArgumentException.class, () -> resources.prepareResize(240,135,64,true));
                    assertEquals(created, GlDebug.resources().createdCount());
                    assertEquals(1, resources.medium().width());
                    try (var pending = resources.prepareResize(2,2,3,true)) {
                        assertThrows(IllegalArgumentException.class, () -> resources.prepareResize(1,1,2,true));
                        resources.commitResize(pending);
                        // The retired storage still counts until the candidate scope is closed.
                        assertThrows(IllegalArgumentException.class, () -> resources.prepareResize(1,1,2,true));
                    }
                    try (var next = resources.prepareResize(1,1,2,true)) { resources.commitResize(next); }
                }
                try (var exactResources = new VolumetricResources(3,5,7,true,exact,exact*2)) {
                    assertEquals(exact, exactResources.bytes());
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    @Test
    void dualViewsAndFailedHistoryWritesStayIsolatedThenResizeHasAValidFirstWrite() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var a = new VolumetricResources(3,5,7,true,BUDGET,BUDGET*2);
                 var b = new VolumetricResources(3,5,7,true,BUDGET,BUDGET*2);
                 var shader = ShaderProgram.fromComputeSource(FILL)) {
                GlRenderDevice device = new GlRenderDevice();
                write(device, shader, a.historyWrite(), 0.25f);
                a.stageHistoryWrite(); a.commitHistory();
                write(device, shader, b.historyWrite(), 0.75f);
                b.stageHistoryWrite(); b.commitHistory();
                int aRead = a.historyRead().id(), bRead = b.historyRead().id();
                var failed = device.createCommandBuffer().memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                        .bindShader(shader).setUniformFloat(shader,"uValue",1)
                        .bindImageTexture(0,a.historyWrite(),0,ImageAccess.WRITE_ONLY,RenderFormat.RGBA16F)
                        .dispatchCompute(1,2,2).custom(() -> { throw new IllegalStateException("injected failed frame"); });
                a.stageHistoryWrite();
                assertThrows(IllegalStateException.class, () -> device.execute(failed));
                a.discardHistory(); a.commitHistory();
                assertEquals(aRead,a.historyRead().id()); assertFalse(a.historyValid());
                assertEquals(bRead,b.historyRead().id()); assertTrue(b.historyValid());
                assertValue(a.historyRead(),0.25f); assertValue(b.historyRead(),0.75f);
                try (var candidate = a.prepareResize(1,1,2,false)) { a.commitResize(candidate); }
                assertFalse(a.historyValid());
                write(device,shader,a.historyWrite(),0.5f);
                a.stageHistoryWrite(); a.commitHistory();
                assertTrue(a.historyValid()); assertValue(a.historyRead(),0.5f);
                assertValue(b.historyRead(),0.75f);
                GlDebug.assertNoError("volume two-view/history recovery");
            }
        }
    }

    private static void write(GlRenderDevice device, ShaderProgram shader, Texture3D texture, float value) {
        device.execute(device.createCommandBuffer().memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                .bindShader(shader).setUniformFloat(shader,"uValue",value)
                .bindImageTexture(0,texture,0,ImageAccess.WRITE_ONLY,texture.format())
                .dispatchCompute((texture.width()+3)/4,(texture.height()+3)/4,(texture.depth()+3)/4)
                .memoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT));
    }

    @Test
    void nativeBalanced4kStorageAndItsResizeCandidateFitTheFrozenOwnedBudget() {
        try (var window = window()) {
            window.bindContext(); GL.createCapabilities();
            try (var tracking = GlDebug.acquireResourceTracking()) {
                try (var resources = new VolumetricResources(240,135,64,true,BUDGET,BUDGET*2);
                     var shader = ShaderProgram.fromComputeSource(FILL.replace("rgba16f","rgba32f"))) {
                    assertEquals(RenderFormat.RGBA32F,resources.source().format());
                    long storageBytes = resources.bytes();
                    assertEquals(124_722_960L, storageBytes);
                    assertTrue(storageBytes < BUDGET);
                    GlRenderDevice device = new GlRenderDevice();
                    write(device,shader,resources.source(),0.12345679f);
                    var sample = BufferUtils.createFloatBuffer(4);
                    glGetTextureSubImage(resources.source().id(),0,239,134,63,1,1,1,GL_RGBA,GL_FLOAT,sample);
                    assertEquals(0.12345679f,sample.get(0),0,"current source must retain full float precision");
                    try (var candidate = resources.prepareResize(240,136,64,true)) {
                        long projected = storageBytes + VolumetricResources.estimatedBytes(240,136,64,true);
                        long actualTexturesAndBuffers = GlDebug.resources().liveResources().stream()
                                .filter(resource -> resource.kind().equals("TEXTURE") || resource.kind().equals("BUFFER"))
                                .mapToLong(GlDebug.ResourceInfo::estimatedBytes).sum();
                        assertEquals(projected,actualTexturesAndBuffers);
                        assertTrue(actualTexturesAndBuffers <= BUDGET*2);
                        resources.commitResize(candidate);
                    }
                    write(device,shader,resources.source(),0.5f);
                    glGetTextureSubImage(resources.source().id(),0,239,135,63,1,1,1,GL_RGBA,GL_FLOAT,sample);
                    assertEquals(0.5f,sample.get(0));
                    GlDebug.assertNoError("balanced 4K volume storage/resize");
                }
                assertTrue(GlDebug.resources().liveResources().isEmpty());
            }
        }
    }

    private static void assertValue(Texture3D texture, float expected) {
        glMemoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT);
        var data = BufferUtils.createFloatBuffer(texture.width()*texture.height()*texture.depth()*4);
        glGetTextureImage(texture.id(),0,GL_RGBA,GL_FLOAT,data);
        for (int index=0; index<data.capacity(); index++) assertEquals(expected,data.get(index),0.00001f);
    }
}
