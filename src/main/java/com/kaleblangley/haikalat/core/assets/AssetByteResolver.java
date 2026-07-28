package com.kaleblangley.haikalat.core.assets;

/**
 * Minimal byte-resolution boundary used by format decoders.
 *
 * <p>The core asset layer deliberately knows nothing about namespaces,
 * generations, executors or OpenGL. Applications adapt their resource
 * catalog to this interface.</p>
 */
public interface AssetByteResolver {
    byte[] readBytes(AssetRef ref, long maxBytes);

    boolean exists(AssetRef ref);

    AssetRef resolveRelative(AssetRef owner, String uri);
}
