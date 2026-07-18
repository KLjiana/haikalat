package com.kaleblangley.haikalat.subsystems.render3d.gltf;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** glTF runtime 部分构造失败时使用的包内逆序回滚工具。 */
final class CloseStack implements AutoCloseable {
    private final List<AutoCloseable> resources = new ArrayList<>();
    private boolean released;
    private boolean closed;

    <T extends AutoCloseable> T own(T resource) {
        if (closed || released) {
            throw new IllegalStateException("CloseStack no longer accepts resources");
        }
        resources.add(Objects.requireNonNull(resource, "resource"));
        return resource;
    }

    void releaseOwnership() {
        if (closed) {
            throw new IllegalStateException("CloseStack is closed");
        }
        resources.clear();
        released = true;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (int index = resources.size() - 1; index >= 0; index--) {
            try {
                resources.get(index).close();
            } catch (Exception error) {
                RuntimeException cleanup = error instanceof RuntimeException runtime
                        ? runtime : new RuntimeException(error);
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        resources.clear();
        if (failure != null) throw failure;
    }
}
