package com.kaleblangley.haikalat.core.assets;

import com.kaleblangley.haikalat.backend.GlException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

public final class ResourceLocator implements AssetByteResolver {
    private final Class<?> anchor;
    private final List<Path> roots = new ArrayList<>();

    private ResourceLocator(Class<?> anchor) {
        this.anchor = Objects.requireNonNull(anchor, "anchor");
    }

    public static ResourceLocator classpath(Class<?> anchor) {
        return new ResourceLocator(anchor);
    }

    public ResourceLocator addRoot(Path root) {
        roots.add(Objects.requireNonNull(root, "root"));
        return this;
    }

    public byte[] readBytes(AssetRef ref) {
        return readBytes(ref, Integer.MAX_VALUE - 8L);
    }

    /** 在分配完整结果前限制本地或 classpath 资源的最大字节数。 */
    public byte[] readBytes(AssetRef ref, long maxBytes) {
        Objects.requireNonNull(ref, "ref");
        if (maxBytes < 0 || maxBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("maxBytes must be in 0.." + (Integer.MAX_VALUE - 1L));
        }
        Optional<Path> file = resolveFile(ref);
        if (file.isPresent()) {
            try {
                if (Files.size(file.get()) > maxBytes) {
                    throw new GlException("Asset exceeds byte limit " + maxBytes + ": " + ref.path());
                }
                byte[] bytes = Files.readAllBytes(file.get());
                if (bytes.length > maxBytes) {
                    throw new GlException("Asset exceeds byte limit " + maxBytes + ": " + ref.path());
                }
                return bytes;
            } catch (IOException e) {
                throw new GlException("Failed to read asset: " + ref.path(), e);
            }
        }
        try (InputStream stream = openClasspath(ref)) {
            if (stream == null) {
                throw new GlException("Asset not found: " + ref.path());
            }
            byte[] bytes = stream.readNBytes(Math.toIntExact(maxBytes + 1L));
            if (bytes.length > maxBytes) {
                throw new GlException("Asset exceeds byte limit " + maxBytes + ": " + ref.path());
            }
            return bytes;
        } catch (IOException e) {
            throw new GlException("Failed to read asset: " + ref.path(), e);
        }
    }

    public String readString(AssetRef ref) {
        return new String(readBytes(ref), StandardCharsets.UTF_8);
    }

    public boolean exists(AssetRef ref) {
        Objects.requireNonNull(ref, "ref");
        if (resolveFile(ref).isPresent()) return true;
        try (InputStream stream = openClasspath(ref)) {
            return stream != null;
        } catch (IOException failure) {
            return false;
        }
    }

    /**
     * 相对于拥有者资产解析本地资源 URI，并拒绝远程 scheme、查询参数、片段和根目录逃逸。
     */
    public AssetRef resolveRelative(AssetRef owner, String uri) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(uri, "uri");
        if (uri.isBlank()) throw new IllegalArgumentException("relative asset URI must not be blank");
        if (uri.startsWith("//") || uri.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("absolute or UNC asset URI is not allowed: " + uri);
        }
        final URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("invalid asset URI: " + uri, error);
        }
        if (parsed.getScheme() != null || parsed.getRawQuery() != null || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("asset URI must be a local relative path without query or fragment: " + uri);
        }
        String decoded = parsed.getPath();
        if (decoded == null || decoded.isBlank() || decoded.startsWith("/")
                || decoded.indexOf('\\') >= 0 || decoded.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("asset URI must be relative: " + uri);
        }
        String ownerPath = owner.path();
        int slash = ownerPath.lastIndexOf('/');
        String directory = slash < 0 ? "" : ownerPath.substring(0, slash + 1);
        boolean absolute = ownerPath.startsWith("/");
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : (directory + decoded).split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException("asset URI escapes owner resource root: " + uri);
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        String normalized = String.join("/", segments);
        return AssetRef.of(absolute ? "/" + normalized : normalized);
    }

    public Optional<Path> resolveFile(AssetRef ref) {
        Path raw = Path.of(ref.path());
        for (Path root : roots) {
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path candidate = normalizedRoot.resolve(ref.path().replace('/', java.io.File.separatorChar)).normalize();
            if (!candidate.startsWith(normalizedRoot)) continue;
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private InputStream openClasspath(AssetRef ref) {
        String path = ref.path().startsWith("/") ? ref.path() : "/" + ref.path();
        return anchor.getResourceAsStream(path);
    }
}
