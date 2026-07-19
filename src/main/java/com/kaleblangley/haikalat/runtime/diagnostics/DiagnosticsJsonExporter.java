package com.kaleblangley.haikalat.runtime.diagnostics;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.kaleblangley.haikalat.core.graph.PassProfile;
import com.kaleblangley.haikalat.core.graph.RenderGraph;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Objects;
import java.util.HashSet;
import java.util.Set;

/** 将 frozen capture 原子地导出为字段顺序稳定的 schema v1 JSON。 */
public final class DiagnosticsJsonExporter {
    private static final JsonFactory JSON = new JsonFactory();

    private DiagnosticsJsonExporter() {
    }

    public static Path export(FrozenDiagnostics capture, Path destination) throws IOException {
        return export(capture, destination, ExportOptions.DEFAULT);
    }

    public static Path export(FrozenDiagnostics capture, Path destination,
                              ExportOptions options) throws IOException {
        Objects.requireNonNull(capture, "capture");
        Objects.requireNonNull(options, "options");
        validate(capture);
        Path target = Objects.requireNonNull(destination, "destination").toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null) throw new IllegalArgumentException("destination must have a parent directory");
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, target.getFileName().toString(), ".tmp");
        boolean moved = false;
        try {
            try (JsonGenerator json = JSON.createGenerator(Files.newOutputStream(temporary))) {
                json.useDefaultPrettyPrinter();
                writeCapture(json, capture, options);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
            moved = true;
            return target;
        } finally {
            if (!moved) Files.deleteIfExists(temporary);
        }
    }

    private static void writeCapture(JsonGenerator json, FrozenDiagnostics capture,
                                     ExportOptions options) throws IOException {
        json.writeStartObject();
        json.writeNumberField("schemaVersion", capture.schemaVersion());
        json.writeNumberField("epoch", capture.epoch());
        json.writeObjectFieldStart("metadata");
        json.writeStringField("engineVersion", capture.metadata().engineVersion());
        json.writeStringField("buildRevision", capture.metadata().buildRevision());
        json.writeStringField("glVendor", capture.metadata().glVendor());
        json.writeStringField("glRenderer", capture.metadata().glRenderer());
        json.writeStringField("glVersion", capture.metadata().glVersion());
        json.writeEndObject();
        json.writeArrayFieldStart("frames");
        for (DiagnosticsSnapshot frame : capture.history()) writeFrame(json, frame);
        json.writeEndArray();
        json.writeObjectFieldStart("resources");
        json.writeNumberField("estimatedBytes", capture.resources().estimatedBytes());
        json.writeNumberField("createdCount", capture.resources().createdCount());
        json.writeNumberField("closedCount", capture.resources().closedCount());
        json.writeNumberField("highWaterMark", capture.resources().highWaterMark());
        json.writeArrayFieldStart("live");
        for (FrozenDiagnostics.Resource resource : capture.resources().live()) {
            json.writeStartObject();
            json.writeNumberField("resourceSequence", resource.resourceSequence());
            json.writeStringField("kind", resource.kind());
            json.writeStringField("label", resource.label());
            if (options.includeNativeIds()) json.writeNumberField("nativeId", resource.nativeId());
            json.writeNumberField("createdFrameSequence", resource.createdFrameSequence());
            if (resource.estimatedBytes() < 0L) json.writeNullField("estimatedBytes");
            else json.writeNumberField("estimatedBytes", resource.estimatedBytes());
            json.writeNumberField("contextIdentity", resource.contextIdentity());
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeEndObject();
        json.writeObjectFieldStart("messages");
        json.writeNumberField("droppedCount", capture.messages().droppedCount());
        json.writeArrayFieldStart("entries");
        for (FrozenDiagnostics.Message message : capture.messages().entries()) {
            json.writeStartObject();
            json.writeNumberField("sequence", message.sequence());
            json.writeStringField("source", message.source());
            json.writeStringField("type", message.type());
            json.writeStringField("severity", message.severity());
            json.writeNumberField("driverId", message.driverId());
            json.writeStringField("message", message.text());
            json.writeNumberField("firstFrameSequence", message.firstFrameSequence());
            json.writeNumberField("lastFrameSequence", message.lastFrameSequence());
            json.writeNumberField("repeatCount", message.repeatCount());
            json.writeNumberField("contextIdentity", message.contextIdentity());
            if (message.phase() == null) json.writeNullField("phase");
            else json.writeStringField("phase", message.phase());
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeEndObject();
        json.writeEndObject();
    }

    /** 在创建临时文件前验证 capture，拒绝非有限数字、负计数和跨 section 不一致。 */
    static void validate(FrozenDiagnostics capture) {
        if (capture.schemaVersion() != 1 || capture.epoch() < 0L) {
            throw new IllegalArgumentException("invalid capture identity");
        }
        requireValidUnicode(capture.metadata().engineVersion(), "metadata.engineVersion");
        requireValidUnicode(capture.metadata().buildRevision(), "metadata.buildRevision");
        requireValidUnicode(capture.metadata().glVendor(), "metadata.glVendor");
        requireValidUnicode(capture.metadata().glRenderer(), "metadata.glRenderer");
        requireValidUnicode(capture.metadata().glVersion(), "metadata.glVersion");
        long previousSequence = -1L;
        for (DiagnosticsSnapshot frame : capture.history()) {
            requireNonNegative(frame.epoch(), "frame.epoch");
            if (frame.epoch() != capture.epoch()) {
                throw new IllegalArgumentException("frame epoch does not match capture epoch");
            }
            if (frame.frameSequence() <= previousSequence) {
                throw new IllegalArgumentException("frame sequences must be strictly increasing");
            }
            previousSequence = frame.frameSequence();
            requireNonNegative(frame.presentedFrameSequence(), "presentedFrameSequence");
            requireFiniteNonNegative(frame.presentFps(), "presentFps");
            requireNonNegative(frame.presentIntervalNanos(), "presentIntervalNanos");
            if (frame.frameProfile().frameSequence() != frame.frameSequence()) {
                throw new IllegalArgumentException("frame profile sequence does not match frame");
            }
            requireNonNegative(frame.frameProfile().cpuFrameNanos(), "cpuFrameNanos");
            requireNonNegative(frame.state().appliedChanges(), "state.appliedChanges");
            requireNonNegative(frame.state().avoidedChanges(), "state.avoidedChanges");
            frame.scene().ifPresent(scene -> {
                requireNonNegative(scene.drawCalls(), "scene.drawCalls");
                requireNonNegative(scene.instanceCount(), "scene.instanceCount");
                requireNonNegative(scene.ordinaryRenderers(), "scene.ordinaryRenderers");
                requireNonNegative(scene.instancedRenderers(), "scene.instancedRenderers");
                scene.visibility().ifPresent(DiagnosticsJsonExporter::validateVisibility);
            });
            requireNonNegative(frame.upload().frameBytes(), "upload.frameBytes");
            requireNonNegative(frame.upload().totalBytes(), "upload.totalBytes");
            requireNonNegative(frame.upload().queueDepth(), "upload.queueDepth");
            requireNonNegative(frame.upload().gpuUpdates(), "upload.gpuUpdates");
            if (frame.upload().frameBytes() > frame.upload().totalBytes()) {
                throw new IllegalArgumentException("upload frame bytes exceed total bytes");
            }
            frame.ui().ifPresent(ui -> {
                requireNonNegative(ui.visibleNodes(), "ui.visibleNodes");
                requireNonNegative(ui.quads(), "ui.quads");
                requireNonNegative(ui.glyphs(), "ui.glyphs");
                requireNonNegative(ui.drawCalls(), "ui.drawCalls");
                requireNonNegative(ui.updateNanos(), "ui.updateNanos");
                requireNonNegative(ui.vertexBytes(), "ui.vertexBytes");
                requireNonNegative(ui.indexBytes(), "ui.indexBytes");
                requireNonNegative(ui.atlasUploadBytes(), "ui.atlasUploadBytes");
            });
            for (PassProfile pass : frame.frameProfile().passes()) {
                requireValidUnicode(pass.passName(), "pass.name");
                requireNonNegative(pass.cpuRecordNanos(), "pass.cpuRecordNanos");
                requireNonNegative(pass.gpuNanos(), "pass.gpuNanos");
                requireNonNegative(pass.sampleAgeFrames(), "pass.sampleAgeFrames");
                requireNonNegative(pass.skippedSubmissions(), "pass.skippedSubmissions");
                if (pass.gpuStatus() == PassProfile.GpuTimingStatus.AVAILABLE
                        && pass.sampleFrameSequence() < 0L) {
                    throw new IllegalArgumentException("available GPU pass lacks sample sequence");
                }
            }
            if (frame.level() == DiagnosticsLevel.DETAILED && frame.graph().isEmpty()) {
                throw new IllegalArgumentException("detailed frame lacks graph description");
            }
            frame.graph().ifPresent(DiagnosticsJsonExporter::validateGraph);
        }
        FrozenDiagnostics.ResourceTable resources = capture.resources();
        requireNonNegative(resources.estimatedBytes(), "resources.estimatedBytes");
        requireNonNegative(resources.createdCount(), "resources.createdCount");
        requireNonNegative(resources.closedCount(), "resources.closedCount");
        requireNonNegative(resources.highWaterMark(), "resources.highWaterMark");
        if (resources.highWaterMark() < resources.live().size()) {
            throw new IllegalArgumentException("resource high-water mark is below live count");
        }
        if (resources.createdCount() < resources.live().size()
                || resources.closedCount() > resources.createdCount()
                || resources.highWaterMark() > resources.createdCount()) {
            throw new IllegalArgumentException("resource lifecycle counters are inconsistent");
        }
        Set<Long> resourceSequences = new HashSet<>();
        long estimatedBytes = 0L;
        for (FrozenDiagnostics.Resource resource : resources.live()) {
            if (resource.resourceSequence() <= 0L || !resourceSequences.add(resource.resourceSequence())) {
                throw new IllegalArgumentException("resource sequences must be positive and unique");
            }
            if (resource.nativeId() < 0 || resource.contextIdentity() <= 0L) {
                throw new IllegalArgumentException("invalid resource identity");
            }
            requireValidUnicode(resource.kind(), "resource.kind");
            requireValidUnicode(resource.label(), "resource.label");
            if (resource.estimatedBytes() >= 0L) {
                estimatedBytes = Math.addExact(estimatedBytes, resource.estimatedBytes());
            }
        }
        if (estimatedBytes != resources.estimatedBytes()) {
            throw new IllegalArgumentException("resource estimated byte total is inconsistent");
        }
        requireNonNegative(capture.messages().droppedCount(), "messages.droppedCount");
        Set<Long> messageSequences = new HashSet<>();
        for (FrozenDiagnostics.Message message : capture.messages().entries()) {
            if (message.sequence() <= 0L || !messageSequences.add(message.sequence())
                    || message.repeatCount() <= 0L || message.contextIdentity() <= 0L
                    || message.lastFrameSequence() < message.firstFrameSequence()) {
                throw new IllegalArgumentException("invalid message identity or range");
            }
            requireValidUnicode(message.source(), "message.source");
            requireValidUnicode(message.type(), "message.type");
            requireValidUnicode(message.severity(), "message.severity");
            requireValidUnicode(message.text(), "message.text");
            if (message.phase() != null) requireValidUnicode(message.phase(), "message.phase");
        }
    }

    private static void validateGraph(RenderGraph.Description graph) {
        if (graph.width() <= 0 || graph.height() <= 0 || graph.topologyRevision() < 0L
                || graph.executionOrder().size() != graph.passes().size()) {
            throw new IllegalArgumentException("invalid render graph description");
        }
        Set<String> names = new HashSet<>();
        for (RenderGraph.PassDescription pass : graph.passes()) {
            requireValidUnicode(pass.name(), "graph.pass.name");
            if (!names.add(pass.name()) || pass.width() <= 0 || pass.height() <= 0
                    || pass.samples() <= 0) {
                throw new IllegalArgumentException("invalid render graph pass");
            }
            for (String dependency : pass.directDependencies()) {
                requireValidUnicode(dependency, "graph.pass.dependency");
            }
        }
        if (!graph.executionOrder().equals(graph.passes().stream()
                .map(RenderGraph.PassDescription::name).toList())) {
            throw new IllegalArgumentException("graph execution order does not match pass table");
        }
    }

    private static void requireNonNegative(long value, String field) {
        if (value < 0L) throw new IllegalArgumentException(field + " must be non-negative");
    }

    private static void requireFiniteNonNegative(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException(field + " must be finite and non-negative");
        }
    }

    private static void requireValidUnicode(String value, String field) {
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(++index))) {
                    throw new IllegalArgumentException(field + " contains an unpaired surrogate");
                }
            } else if (Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException(field + " contains an unpaired surrogate");
            }
        }
    }

    public record ExportOptions(boolean includeNativeIds) {
        public static final ExportOptions DEFAULT = new ExportOptions(false);
        public static final ExportOptions WITH_NATIVE_IDS = new ExportOptions(true);
    }

    private static void writeFrame(JsonGenerator json, DiagnosticsSnapshot frame) throws IOException {
        json.writeStartObject();
        json.writeNumberField("epoch", frame.epoch());
        json.writeNumberField("frameSequence", frame.frameSequence());
        json.writeNumberField("presentedFrameSequence", frame.presentedFrameSequence());
        json.writeStringField("level", frame.level().name());
        json.writeBooleanField("complete", frame.complete());
        json.writeNumberField("cpuSubmitNanos", frame.frameProfile().cpuFrameNanos());
        json.writeNumberField("presentFps", frame.presentFps());
        json.writeNumberField("presentIntervalNanos", frame.presentIntervalNanos());
        json.writeNumberField("availableGpuNanos", frame.frameProfile().totalGpuNanos());
        json.writeBooleanField("gpuTotalComplete", frame.frameProfile().gpuTotalComplete());
        json.writeObjectFieldStart("state");
        json.writeNumberField("appliedChanges", frame.state().appliedChanges());
        json.writeNumberField("avoidedChanges", frame.state().avoidedChanges());
        json.writeNumberField("skipRatio", frame.state().skipRatio());
        json.writeEndObject();
        if (frame.scene().isPresent()) {
            DiagnosticsSnapshot.SceneSummary scene = frame.scene().orElseThrow();
            json.writeObjectFieldStart("scene");
            json.writeNumberField("drawCalls", scene.drawCalls());
            json.writeNumberField("instanceCount", scene.instanceCount());
            json.writeNumberField("ordinaryRenderers", scene.ordinaryRenderers());
            json.writeNumberField("instancedRenderers", scene.instancedRenderers());
            if (scene.visibility().isPresent()) {
                writeVisibility(json, scene.visibility().orElseThrow());
            } else {
                json.writeNullField("visibility");
            }
            json.writeEndObject();
        } else json.writeNullField("scene");
        json.writeObjectFieldStart("upload");
        json.writeNumberField("frameBytes", frame.upload().frameBytes());
        json.writeNumberField("totalBytes", frame.upload().totalBytes());
        json.writeNumberField("queueDepth", frame.upload().queueDepth());
        json.writeNumberField("gpuUpdates", frame.upload().gpuUpdates());
        json.writeEndObject();
        if (frame.ui().isPresent()) {
            DiagnosticsSnapshot.UiSummary ui = frame.ui().orElseThrow();
            json.writeObjectFieldStart("ui");
            json.writeNumberField("visibleNodes", ui.visibleNodes());
            json.writeNumberField("quads", ui.quads());
            json.writeNumberField("glyphs", ui.glyphs());
            json.writeNumberField("drawCalls", ui.drawCalls());
            json.writeNumberField("updateNanos", ui.updateNanos());
            json.writeNumberField("vertexBytes", ui.vertexBytes());
            json.writeNumberField("indexBytes", ui.indexBytes());
            json.writeNumberField("atlasUploadBytes", ui.atlasUploadBytes());
            json.writeEndObject();
        } else json.writeNullField("ui");
        json.writeArrayFieldStart("passes");
        for (PassProfile pass : frame.frameProfile().passes()) {
            json.writeStartObject();
            json.writeStringField("name", pass.passName());
            json.writeNumberField("cpuRecordNanos", pass.cpuRecordNanos());
            json.writeStringField("gpuStatus", pass.gpuStatus().name());
            if (pass.gpuStatus() == PassProfile.GpuTimingStatus.AVAILABLE) {
                json.writeNumberField("gpuNanos", pass.gpuNanos());
                json.writeNumberField("sampleFrameSequence", pass.sampleFrameSequence());
                json.writeNumberField("sampleAgeFrames", pass.sampleAgeFrames());
            } else {
                json.writeNullField("gpuNanos");
                json.writeNullField("sampleFrameSequence");
                json.writeNullField("sampleAgeFrames");
            }
            json.writeNumberField("skippedSubmissions", pass.skippedSubmissions());
            json.writeEndObject();
        }
        json.writeEndArray();
        if (frame.graph().isPresent()) writeGraph(json, frame.graph().orElseThrow());
        else json.writeNullField("graph");
        json.writeEndObject();
    }

    private static void validateVisibility(DiagnosticsSnapshot.VisibilitySummary visibility) {
        long[] values = {visibility.sceneRevision(), visibility.candidateRenderers(),
                visibility.finiteBoundsRenderers(), visibility.unboundedRenderers(),
                visibility.forwardVisible(), visibility.forwardCulled(),
                visibility.shadowCandidates(), visibility.shadowVisible(),
                visibility.shadowCulled(), visibility.staticRenderers(),
                visibility.dynamicRenderers(), visibility.modelCacheHits(),
                visibility.modelCacheMisses(), visibility.boundsCacheHits(),
                visibility.boundsCacheMisses(), visibility.modelUpdateNanos(),
                visibility.boundsTransformNanos(), visibility.frustumTestNanos(),
                visibility.queueSortNanos(), visibility.totalQueueBuildNanos(),
                visibility.opaqueDraws(), visibility.additiveDraws(), visibility.alphaDraws(),
                visibility.shaderChanges(), visibility.materialChanges(), visibility.meshChanges(),
                visibility.blendChanges(), visibility.mirroredChanges(),
                visibility.commandRecordNanos(), visibility.recordedCommands(),
                visibility.recordedMatrixSnapshots(), visibility.recordedObjectPayloads()};
        for (long value : values) requireNonNegative(value, "scene.visibility");
        if (visibility.finiteBoundsRenderers() + visibility.unboundedRenderers()
                != visibility.candidateRenderers()
                || visibility.forwardVisible() + visibility.forwardCulled()
                != visibility.candidateRenderers()
                || visibility.staticRenderers() + visibility.dynamicRenderers()
                != visibility.candidateRenderers()
                || visibility.modelCacheHits() + visibility.modelCacheMisses()
                != visibility.candidateRenderers()
                || visibility.boundsCacheHits() + visibility.boundsCacheMisses()
                != visibility.candidateRenderers()
                || visibility.shadowVisible() + visibility.shadowCulled()
                != visibility.shadowCandidates()
                || visibility.opaqueDraws() + visibility.additiveDraws() + visibility.alphaDraws()
                != visibility.forwardVisible()) {
            throw new IllegalArgumentException("scene visibility counters are inconsistent");
        }
        long adjacency = Math.max(0L, visibility.forwardVisible() - 1L);
        if (visibility.shaderChanges() > adjacency || visibility.materialChanges() > adjacency
                || visibility.meshChanges() > adjacency || visibility.blendChanges() > adjacency
                || visibility.mirroredChanges() > adjacency) {
            throw new IllegalArgumentException("scene queue changes exceed forward adjacency count");
        }
        if (visibility.forwardQueueReused() == visibility.forwardQueueRebuilt()
                || visibility.shadowQueueReused() == visibility.shadowQueueRebuilt()) {
            throw new IllegalArgumentException("scene queue reuse/rebuild flags are inconsistent");
        }
    }

    private static void writeVisibility(JsonGenerator json,
                                        DiagnosticsSnapshot.VisibilitySummary visibility)
            throws IOException {
        json.writeObjectFieldStart("visibility");
        json.writeBooleanField("cullingEnabled", visibility.cullingEnabled());
        json.writeNumberField("sceneRevision", visibility.sceneRevision());
        json.writeNumberField("candidateRenderers", visibility.candidateRenderers());
        json.writeNumberField("finiteBoundsRenderers", visibility.finiteBoundsRenderers());
        json.writeNumberField("unboundedRenderers", visibility.unboundedRenderers());
        json.writeNumberField("forwardVisible", visibility.forwardVisible());
        json.writeNumberField("forwardCulled", visibility.forwardCulled());
        json.writeNumberField("shadowCandidates", visibility.shadowCandidates());
        json.writeNumberField("shadowVisible", visibility.shadowVisible());
        json.writeNumberField("shadowCulled", visibility.shadowCulled());
        json.writeNumberField("staticRenderers", visibility.staticRenderers());
        json.writeNumberField("dynamicRenderers", visibility.dynamicRenderers());
        json.writeNumberField("modelCacheHits", visibility.modelCacheHits());
        json.writeNumberField("modelCacheMisses", visibility.modelCacheMisses());
        json.writeNumberField("boundsCacheHits", visibility.boundsCacheHits());
        json.writeNumberField("boundsCacheMisses", visibility.boundsCacheMisses());
        json.writeBooleanField("forwardQueueReused", visibility.forwardQueueReused());
        json.writeBooleanField("forwardQueueRebuilt", visibility.forwardQueueRebuilt());
        json.writeBooleanField("shadowQueueReused", visibility.shadowQueueReused());
        json.writeBooleanField("shadowQueueRebuilt", visibility.shadowQueueRebuilt());
        json.writeNumberField("modelUpdateNanos", visibility.modelUpdateNanos());
        json.writeNumberField("boundsTransformNanos", visibility.boundsTransformNanos());
        json.writeNumberField("frustumTestNanos", visibility.frustumTestNanos());
        json.writeNumberField("queueSortNanos", visibility.queueSortNanos());
        json.writeNumberField("totalQueueBuildNanos", visibility.totalQueueBuildNanos());
        json.writeNumberField("opaqueDraws", visibility.opaqueDraws());
        json.writeNumberField("additiveDraws", visibility.additiveDraws());
        json.writeNumberField("alphaDraws", visibility.alphaDraws());
        json.writeNumberField("shaderChanges", visibility.shaderChanges());
        json.writeNumberField("materialChanges", visibility.materialChanges());
        json.writeNumberField("meshChanges", visibility.meshChanges());
        json.writeNumberField("blendChanges", visibility.blendChanges());
        json.writeNumberField("mirroredChanges", visibility.mirroredChanges());
        json.writeNumberField("commandRecordNanos", visibility.commandRecordNanos());
        json.writeNumberField("recordedCommands", visibility.recordedCommands());
        json.writeNumberField("recordedMatrixSnapshots", visibility.recordedMatrixSnapshots());
        json.writeNumberField("recordedObjectPayloads", visibility.recordedObjectPayloads());
        json.writeEndObject();
    }

    private static void writeGraph(JsonGenerator json, RenderGraph.Description graph) throws IOException {
        json.writeObjectFieldStart("graph");
        json.writeNumberField("width", graph.width());
        json.writeNumberField("height", graph.height());
        json.writeNumberField("topologyRevision", graph.topologyRevision());
        json.writeBooleanField("sealed", graph.sealed());
        json.writeArrayFieldStart("executionOrder");
        for (String name : graph.executionOrder()) json.writeString(name);
        json.writeEndArray();
        json.writeArrayFieldStart("passes");
        for (RenderGraph.PassDescription pass : graph.passes()) {
            json.writeStartObject();
            json.writeStringField("name", pass.name());
            json.writeStringField("targetKind", pass.targetKind().name());
            json.writeNumberField("width", pass.width());
            json.writeNumberField("height", pass.height());
            json.writeNumberField("samples", pass.samples());
            json.writeArrayFieldStart("dependencies");
            for (String dependency : pass.directDependencies()) json.writeString(dependency);
            json.writeEndArray();
            json.writeArrayFieldStart("colors");
            for (RenderGraph.AttachmentDescription attachment : pass.colorAttachments()) {
                writeAttachment(json, attachment);
            }
            json.writeEndArray();
            json.writeFieldName("depth");
            if (pass.depthAttachment() == null) json.writeNull();
            else writeAttachment(json, pass.depthAttachment());
            json.writeBooleanField("clearColor", pass.clearColor());
            json.writeBooleanField("clearDepth", pass.clearDepth());
            json.writeEndObject();
        }
        json.writeEndArray();
        json.writeEndObject();
    }

    private static void writeAttachment(JsonGenerator json,
                                        RenderGraph.AttachmentDescription attachment) throws IOException {
        json.writeStartObject();
        json.writeStringField("logicalName", attachment.logicalName());
        json.writeStringField("format", attachment.format());
        json.writeStringField("storageKind", attachment.storageKind().name());
        json.writeEndObject();
    }
}
