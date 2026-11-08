package com.kaleblangley.haikalat.subsystems.render3d;

import org.junit.jupiter.api.Test;
import com.kaleblangley.haikalat.core.graph.RenderGraph;
import com.kaleblangley.haikalat.core.graph.GraphRecordingFixture;
import com.kaleblangley.haikalat.core.graph.PassResources;

import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.kaleblangley.haikalat.subsystems.render3d.SceneBufferChannel.*;
import static com.kaleblangley.haikalat.subsystems.render3d.SceneSurfaceLayout.ReactiveMode.*;
import static com.kaleblangley.haikalat.backend.RenderFormat.R8;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SceneBufferRequirementsTest {
    @Test
    void velocityImpliesValidationCompanions() {
        SceneBufferRequirements requirements = SceneBufferRequirements
                .of(SceneBufferChannel.VELOCITY).validated();
        assertTrue(requirements.requires(SceneBufferChannel.VELOCITY));
        assertTrue(requirements.requires(SceneBufferChannel.PREVIOUS_SURFACE_DEPTH));
        assertTrue(requirements.requires(SceneBufferChannel.VALIDITY));
        assertFalse(requirements.requires(SceneBufferChannel.REACTIVE));
    }

    @Test
    void mergeIsCommutativeForChannels() {
        SceneBufferRequirements a = SceneBufferRequirements.fog();
        SceneBufferRequirements b = SceneBufferRequirements.gtao(true);
        assertEquals(a.merge(b).channels(), b.merge(a).channels());
        assertTrue(a.merge(b).requires(SceneBufferChannel.DEPTH));
        assertTrue(a.merge(b).requires(SceneBufferChannel.NORMAL));
    }

    @Test
    void noneRequiresNoSurfacePass() {
        SceneBufferRequirements none = SceneBufferRequirements.none();
        assertTrue(none.isEmpty());
        assertFalse(none.requiresSurfacePass());
        assertFalse(SceneBufferRequirements.none().withSamples(4).requiresSurfacePass());
    }

    @Test
    void gtaoAndTaaMergeCoversSharedChannels() {
        SceneBufferRequirements merged = SceneBufferRequirements.gtao(true)
                .merge(SceneBufferRequirements.taa(false));
        for (SceneBufferChannel channel : new SceneBufferChannel[]{
                SceneBufferChannel.DEPTH, SceneBufferChannel.NORMAL,
                SceneBufferChannel.VELOCITY, SceneBufferChannel.PREVIOUS_SURFACE_DEPTH,
                SceneBufferChannel.VALIDITY}) {
            assertTrue(merged.requires(channel), channel.name());
        }
        assertFalse(merged.requires(SceneBufferChannel.REACTIVE));
    }

    @Test
    void invalidSamplesRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SceneBufferRequirements.of(SceneBufferChannel.DEPTH).withSamples(0));
        assertThrows(IllegalArgumentException.class, () -> SceneBufferRequirements.of(Set.of(), 0));
    }

    @Test
    void layoutsAllocateOnlyRequestedColorsAndKeepDepthAsASeparateAttachment() {
        var empty = SceneBufferRequirements.none();
        var depth = SceneSurfaceLayout.plan(1, empty, SceneBufferRequirements.fog(), NONE);
        var ao = SceneSurfaceLayout.plan(1, empty, SceneBufferRequirements.gtao(false), NONE);
        var motion = SceneSurfaceLayout.plan(1, empty, SceneBufferRequirements.of(VELOCITY), NONE);

        assertEquals(List.of(), depth.rasterColors());
        assertEquals(List.of(NORMAL), ao.rasterColors().stream().map(SceneSurfaceLayout.Attachment::channel).toList());
        assertEquals(List.of(VELOCITY, PREVIOUS_SURFACE_DEPTH, VALIDITY),
                motion.rasterColors().stream().map(SceneSurfaceLayout.Attachment::channel).toList());
        assertFalse(motion.requests().requires(DEPTH));
        assertTrue(motion.raw(DEPTH).depthTexture());
        assertEquals(0, motion.raw(VELOCITY).colorSlot());
        assertEquals(2, motion.raw(VALIDITY).colorSlot());
        assertEquals(13L * 33 * 17, motion.storage(33, 17).bytes());
    }

    @Test
    void multisampleAndSingleSampleRequestsKeepIndependentViews() {
        var raw = SceneBufferRequirements.of(Set.of(DEPTH, NORMAL), 4);
        var onlyRaw = SceneSurfaceLayout.plan(4, raw, SceneBufferRequirements.none(), NONE);
        assertFalse(onlyRaw.hasResolve());
        var depthGuide = SceneSurfaceLayout.plan(4, raw, SceneBufferRequirements.fog(), NONE);
        assertEquals(4, depthGuide.raw(DEPTH).samples());
        assertEquals(1, depthGuide.single(DEPTH).samples());
        assertEquals(com.kaleblangley.haikalat.backend.RenderFormat.R32F, depthGuide.single(DEPTH).format());
        assertEquals(List.of(DEPTH), depthGuide.resolve().stream().map(SceneSurfaceLayout.Attachment::channel).toList());
        assertEquals(null, depthGuide.single(NORMAL));
        assertEquals(new SceneSurfaceLayout.Storage(32L * 33 * 17, 4L * 33 * 17, 0), depthGuide.storage(33, 17));
    }

    @Test
    void incompatibleSamplesAndUnproducedChannelsFailBeforeAllocation() {
        var empty = SceneBufferRequirements.none();
        var depth = SceneBufferRequirements.fog();
        assertThrows(IllegalArgumentException.class, () -> depth.withSamples(2).mergeView(depth.withSamples(4)));
        assertThrows(IllegalArgumentException.class, () -> depth.withSamples(4).mergeView(depth.withSamples(2)));
        assertThrows(IllegalArgumentException.class, () -> SceneSurfaceLayout.plan(4, depth.withSamples(2), depth, NONE));
        assertThrows(IllegalArgumentException.class, () -> SceneSurfaceLayout.plan(4, empty, depth.withSamples(4), NONE));
        assertThrows(IllegalArgumentException.class, () -> SceneSurfaceLayout.plan(1, empty,
                SceneBufferRequirements.of(LINEAR_DEPTH), NONE));
        assertThrows(IllegalArgumentException.class, () -> SceneSurfaceLayout.plan(1, empty,
                SceneBufferRequirements.of(REACTIVE), NONE));
        assertThrows(IllegalArgumentException.class, () -> SceneSurfaceLayout.plan(4,
                SceneBufferRequirements.of(REACTIVE).withSamples(4), empty, SCENE));
    }

    @Test
    void mergedRequirementsAreIdempotentAndReactiveOnlyAllocatesLateStorage() {
        var empty = SceneBufferRequirements.none();
        var motion = SceneBufferRequirements.of(VELOCITY);
        var ao = SceneBufferRequirements.gtao(false);
        assertEquals(SceneSurfaceLayout.plan(1, empty, motion.merge(ao), NONE),
                SceneSurfaceLayout.plan(1, empty, ao.merge(motion).merge(ao), NONE));
        var reactive = SceneBufferRequirements.of(REACTIVE);
        var late = SceneSurfaceLayout.plan(1, empty, reactive, SCENE);
        var aliased = SceneSurfaceLayout.plan(1, empty, reactive, VOLUME_ALIAS);
        assertFalse(late.hasSurface());
        assertEquals(33L * 17, late.storage(33, 17).bytes());
        assertEquals(0, aliased.storage(33, 17).bytes());
    }

    @Test
    void reactiveStorageMustMatchTheDeclaredOwnerAndSlot() {
        String composite = VolumetricPassBuilder.COMPOSITE_PASS;
        int reactiveSlot = VolumetricPassBuilder.OPAQUE_REACTIVE_SLOT;
        validateReactiveStorage(SCENE, null, 0);
        validateReactiveStorage(VOLUME_ALIAS, composite, reactiveSlot);
        assertThrows(IllegalArgumentException.class, () -> validateReactiveStorage(VOLUME_ALIAS, null, 0));
        assertThrows(IllegalArgumentException.class, () -> validateReactiveStorage(SCENE, composite, reactiveSlot));
        assertThrows(IllegalArgumentException.class, () -> validateReactiveStorage(VOLUME_ALIAS, composite, 0));
        assertThrows(IllegalArgumentException.class, () -> validateReactiveStorage(VOLUME_ALIAS, "OtherMask", reactiveSlot));
    }

    @Test
    void candidateRejectsWrongRasterExtentAndSampleCountBeforePublication() {
        var layout = SceneSurfaceLayout.plan(1, SceneBufferRequirements.none(), SceneBufferRequirements.fog(), NONE);
        var depth = layout.raw(DEPTH);
        try (var wrongExtent = GraphRecordingFixture.graph(33, 17);
             var wrongSamples = GraphRecordingFixture.graph(33, 17)) {
            wrongExtent.addPass(depth.producer()).createDepthTexture(depth.name()).fixedSize(32, 17)
                    .execute((res, cmd) -> {});
            wrongSamples.addPass(depth.producer()).createDepthTextureMS(depth.name(), 4)
                    .execute((res, cmd) -> {});
            for (var graph : List.of(wrongExtent, wrongSamples)) {
                var failure = assertThrows(IllegalArgumentException.class,
                        () -> new SceneSurfaceInputs(1, graph, layout).validate());
                assertTrue(failure.getMessage().contains("invalid surface extent or samples"));
            }
        }
    }

    private static void validateReactiveStorage(SceneSurfaceLayout.ReactiveMode mode, String source, int slot) {
        var layout = SceneSurfaceLayout.plan(1, SceneBufferRequirements.none(), SceneBufferRequirements.of(REACTIVE), mode);
        var reactive = layout.reactive();
        try (var graph = GraphRecordingFixture.graph(33, 17)) {
            if (source != null) {
                graph.addPass(source).createColors(List.of("otherMask", VolumetricPassBuilder.OPAQUE_REACTIVE),
                        List.of(R8, R8)).execute((res, cmd) -> {});
            }
            var pass = graph.addPass(reactive.producer());
            if (source == null) pass.createColor(reactive.name(), reactive.format());
            else pass.shareColorAttachments(List.of(reactive.name()), List.of(reactive.format()), source, List.of(slot), 1).noClear();
            pass.execute((res, cmd) -> {});
            new SceneSurfaceInputs(1, graph, layout).validate();
        }
    }

    @Test
    void frameLeaseExpiresEvenWhenTheNextWorldUsesTheSameGraphAndExtent() {
        var layout = SceneSurfaceLayout.plan(1, SceneBufferRequirements.none(), SceneBufferRequirements.fog(), NONE);
        try (var graph = new RenderGraph(33, 17)) {
            var inputs = new SceneSurfaceInputs(1, graph, layout);
            Scene first = new Scene(new Camera());
            inputs.beginFrame(RenderFrameContext.capture(first, first.camera(), 33, 17, 0, 0, 0, 0, null));
            var oldFrame = inputs.frame();
            inputs.endFrame();

            Scene second = new Scene(new Camera());
            var next = RenderFrameContext.capture(second, second.camera(), 33, 17, 0, 1, 1, 0, null);
            inputs.beginFrame(next);
            var expired = assertThrows(IllegalStateException.class,
                    () -> oldFrame.require(null, layout.raw(DEPTH)));
            assertTrue(expired.getMessage().contains("expired scene surface frame 0"));
            inputs.retire();
            assertThrows(IllegalStateException.class, () -> inputs.beginFrame(next));
        }
    }

    @Test
    void sharedInputChecksActualGraphAndProducerOnTheFirstCallback() {
        var layout = SceneSurfaceLayout.plan(4, SceneBufferRequirements.none(),
                SceneBufferRequirements.gtao(false), NONE);
        var rawDepth = layout.raw(DEPTH);
        var resolvedDepth = layout.single(DEPTH);
        try (var graph = GraphRecordingFixture.graph(33, 17);
             var foreign = GraphRecordingFixture.graph(33, 17)) {
            var inputs = new SceneSurfaceInputs(1, graph, layout);
            Scene scene = new Scene(new Camera());
            inputs.beginFrame(RenderFrameContext.capture(scene, scene.camera(), 33, 17, 0, 0, 0, 0, null));
            var frame = inputs.frame();
            var captured = new AtomicReference<PassResources>();

            foreign.addPass(rawDepth.producer()).computeOnly().execute((res, cmd) -> {
                assertThrows(IllegalStateException.class, () -> frame.rasterProduced(res));
                assertThrows(IllegalStateException.class, () -> frame.require(res, rawDepth));
            });
            GraphRecordingFixture.record(foreign);

            // Deliberately register resolve before the raster target. Its explicit dependency controls order.
            var resolve = graph.addPass(resolvedDepth.producer());
            layout.configureResolve(resolve);
            resolve.execute((res, cmd) -> {
                var early = assertThrows(IllegalStateException.class, () -> frame.require(res, resolvedDepth));
                assertTrue(early.getMessage().contains("read before " + resolvedDepth.producer()));
                frame.resolveProduced(res);
                assertThrows(IllegalStateException.class, () -> frame.resolveProduced(res));
                captured.set(res);
            });
            graph.addPass("BeforeSurface").computeOnly().execute((res, cmd) -> {
                var wrongPass = assertThrows(IllegalStateException.class, () -> frame.rasterProduced(res));
                assertTrue(wrongPass.getMessage().contains("recorded by " + rawDepth.producer()));
            });
            var raster = graph.addPass(rawDepth.producer()).dependsOn("BeforeSurface");
            layout.configureRaster(raster);
            raster.execute((res, cmd) -> frame.rasterProduced(res));
            inputs.validate();
            GraphRecordingFixture.record(graph);

            var outside = assertThrows(IllegalStateException.class, () -> frame.require(captured.get(), resolvedDepth));
            assertTrue(outside.getMessage().contains("active callback"));
            inputs.endFrame();
        }
    }
}
