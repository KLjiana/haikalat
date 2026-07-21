package com.kaleblangley.haikalat.subsystems.render3d.preview;

import java.util.Objects;

/**
 * 不包含 native handle 的预览源逻辑身份。
 *
 * <p>该类型是 v0.17.1 的 internal inspection seam，不构成稳定资产标识。</p>
 */
public record PreviewSourceKey(long pipelineGeneration, String passName,
                               String attachmentName, PreviewAspect aspect,
                               String registeredName, long ownerGeneration) {
    public PreviewSourceKey {
        if (pipelineGeneration <= 0L) {
            throw new IllegalArgumentException("pipelineGeneration must be positive");
        }
        passName = normalized(passName);
        attachmentName = normalized(attachmentName);
        registeredName = normalized(registeredName);
        aspect = Objects.requireNonNull(aspect, "aspect");
        if (ownerGeneration < 0L) {
            throw new IllegalArgumentException("ownerGeneration must be non-negative");
        }
        if (aspect == PreviewAspect.CUBE) {
            if (registeredName.isEmpty() || ownerGeneration == 0L) {
                throw new IllegalArgumentException(
                        "cube key requires registeredName and positive ownerGeneration");
            }
            if (!passName.isEmpty() || !attachmentName.isEmpty()) {
                throw new IllegalArgumentException("cube key must not contain graph attachment names");
            }
        } else {
            if (passName.isEmpty() || attachmentName.isEmpty()) {
                throw new IllegalArgumentException("graph key requires passName and attachmentName");
            }
            if (!registeredName.isEmpty() || ownerGeneration != 0L) {
                throw new IllegalArgumentException("graph key must not contain registered owner fields");
            }
        }
    }

    public static PreviewSourceKey graph(long pipelineGeneration, String passName,
                                         String attachmentName, PreviewAspect aspect) {
        if (aspect == PreviewAspect.CUBE) {
            throw new IllegalArgumentException("graph attachment cannot use CUBE aspect");
        }
        return new PreviewSourceKey(pipelineGeneration, passName, attachmentName,
                aspect, "", 0L);
    }

    public static PreviewSourceKey cube(long pipelineGeneration, String registeredName,
                                        long ownerGeneration) {
        return new PreviewSourceKey(pipelineGeneration, "", "", PreviewAspect.CUBE,
                registeredName, ownerGeneration);
    }

    /** @return 可写入 diagnostics/JSON 的无 native id 稳定文本 */
    public String logicalName() {
        return aspect == PreviewAspect.CUBE
                ? "cube:" + registeredName + "@" + ownerGeneration
                : passName + "/" + attachmentName + ":" + aspect;
    }

    private static String normalized(String value) {
        return Objects.requireNonNullElse(value, "").trim();
    }
}
