package com.kaleblangley.haikalat.core.material;

/**
 * 描述 runtime material 是否负责关闭其引用的 OpenGL 资源。
 *
 * <p>shader、texture 和 sampler 由 asset manager/cache 或外围 Demo 管理时使用 {@link #BORROWED}；
 * 只有 runtime material 独占这些资源并应一并关闭时才使用 {@link #OWNED}。</p>
 */
public enum ResourceOwnership {
    BORROWED,
    OWNED
}
