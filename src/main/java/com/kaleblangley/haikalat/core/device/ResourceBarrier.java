package com.kaleblangley.haikalat.core.device;

import java.util.Objects;

public record ResourceBarrier(
        ResourceKind resourceKind,
        String resourceName,
        ResourceLayout before,
        ResourceLayout after
) {
    public ResourceBarrier {
        resourceKind = Objects.requireNonNull(resourceKind, "resourceKind");
        resourceName = Objects.requireNonNull(resourceName, "resourceName");
        before = Objects.requireNonNull(before, "before");
        after = Objects.requireNonNull(after, "after");
    }

    public static ResourceBarrier texture(String name, ResourceLayout before, ResourceLayout after) {
        return new ResourceBarrier(ResourceKind.TEXTURE, name, before, after);
    }

    public static ResourceBarrier framebuffer(String name, ResourceLayout before, ResourceLayout after) {
        return new ResourceBarrier(ResourceKind.FRAMEBUFFER, name, before, after);
    }
}
