package com.kaleblangley.haikalat.core.assets.gltf;

import com.kaleblangley.haikalat.core.assets.AssetByteResolver;
import com.kaleblangley.haikalat.core.assets.AssetRef;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only, root-confined resolver for a glTF animation library ZIP. */
final class GltfAnimationArchiveResolver implements AssetByteResolver, AutoCloseable {
    private static final int MAX_ENTRIES = 4_096;

    private final ZipFile archive;
    private final Map<String, ZipEntry> entries;
    private final AssetRef manifest;

    private GltfAnimationArchiveResolver(ZipFile archive,
                                         Map<String, ZipEntry> entries,
                                         AssetRef manifest) {
        this.archive = archive;
        this.entries = Map.copyOf(entries);
        this.manifest = manifest;
    }

    static GltfAnimationArchiveResolver open(Path source) throws IOException {
        Path archivePath = Objects.requireNonNull(source, "source")
                .toAbsolutePath().normalize();
        if (!Files.isRegularFile(archivePath)) {
            throw new IOException("animation library archive is not a regular file: "
                    + archivePath);
        }
        ZipFile archive = new ZipFile(archivePath.toFile(), StandardCharsets.UTF_8);
        try {
            Map<String, ZipEntry> entries = new LinkedHashMap<>();
            List<String> manifests = new ArrayList<>();
            Enumeration<? extends ZipEntry> enumeration = archive.entries();
            int count = 0;
            while (enumeration.hasMoreElements()) {
                ZipEntry entry = enumeration.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw new IOException("animation library archive contains more than "
                            + MAX_ENTRIES + " entries");
                }
                String name = validateEntryName(entry.getName(), entry.isDirectory());
                if (entry.isDirectory()) continue;
                if (entries.putIfAbsent(name, entry) != null) {
                    throw new IOException("duplicate animation library archive entry: " + name);
                }
                if (name.equals("animation-library.json")
                        || name.endsWith("/animation-library.json")) {
                    manifests.add(name);
                }
            }
            if (manifests.size() != 1) {
                throw new IOException("animation library archive must contain exactly one "
                        + "animation-library.json, found " + manifests.size());
            }
            return new GltfAnimationArchiveResolver(archive, entries,
                    AssetRef.of(manifests.getFirst()));
        } catch (IOException | RuntimeException | Error failure) {
            try {
                archive.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    AssetRef manifest() {
        return manifest;
    }

    @Override
    public byte[] readBytes(AssetRef ref, long maxBytes) {
        Objects.requireNonNull(ref, "ref");
        if (maxBytes < 0 || maxBytes >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "maxBytes must be in 0.." + (Integer.MAX_VALUE - 1L));
        }
        String path = validateAssetPath(ref.path());
        ZipEntry entry = entries.get(path);
        if (entry == null) {
            throw new IllegalArgumentException("archive asset not found: " + path);
        }
        long declared = entry.getSize();
        if (declared > maxBytes) {
            throw new IllegalArgumentException(
                    "archive asset exceeds byte limit " + maxBytes + ": " + path);
        }
        try (InputStream input = archive.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(Math.toIntExact(maxBytes + 1L));
            if (bytes.length > maxBytes) {
                throw new IllegalArgumentException(
                        "archive asset exceeds byte limit " + maxBytes + ": " + path);
            }
            return bytes;
        } catch (IOException failure) {
            throw new IllegalArgumentException("failed to read archive asset: " + path,
                    failure);
        }
    }

    @Override
    public boolean exists(AssetRef ref) {
        Objects.requireNonNull(ref, "ref");
        try {
            return entries.containsKey(validateAssetPath(ref.path()));
        } catch (IllegalArgumentException failure) {
            return false;
        }
    }

    @Override
    public AssetRef resolveRelative(AssetRef owner, String uri) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(uri, "uri");
        if (uri.isBlank() || uri.startsWith("//") || uri.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException(
                    "asset URI must be a local relative path: " + uri);
        }
        final URI parsed;
        try {
            parsed = new URI(uri);
        } catch (URISyntaxException failure) {
            throw new IllegalArgumentException("invalid asset URI: " + uri, failure);
        }
        if (parsed.getScheme() != null || parsed.getRawQuery() != null
                || parsed.getRawFragment() != null) {
            throw new IllegalArgumentException("asset URI must be a local relative path "
                    + "without query or fragment: " + uri);
        }
        String decoded = parsed.getPath();
        if (decoded == null || decoded.isBlank() || decoded.startsWith("/")
                || decoded.indexOf('\\') >= 0 || decoded.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("asset URI must be relative: " + uri);
        }
        String ownerPath = validateAssetPath(owner.path());
        int slash = ownerPath.lastIndexOf('/');
        String directory = slash < 0 ? "" : ownerPath.substring(0, slash + 1);
        ArrayDeque<String> segments = new ArrayDeque<>();
        for (String segment : (directory + decoded).split("/")) {
            if (segment.isEmpty() || segment.equals(".")) continue;
            if (segment.equals("..")) {
                if (segments.isEmpty()) {
                    throw new IllegalArgumentException(
                            "asset URI escapes archive root: " + uri);
                }
                segments.removeLast();
            } else {
                segments.addLast(segment);
            }
        }
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("asset URI resolves to archive root: " + uri);
        }
        return AssetRef.of(String.join("/", segments));
    }

    @Override
    public void close() throws IOException {
        archive.close();
    }

    private static String validateEntryName(String raw, boolean directory)
            throws IOException {
        if (raw == null || raw.isBlank() || raw.startsWith("/")
                || raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0
                || raw.matches("^[A-Za-z]:.*")) {
            throw new IOException("unsafe animation library archive entry: " + raw);
        }
        String value = directory && raw.endsWith("/")
                ? raw.substring(0, raw.length() - 1) : raw;
        if (value.isEmpty()) return value;
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IOException("unsafe animation library archive entry: " + raw);
            }
        }
        return value;
    }

    private static String validateAssetPath(String raw) {
        if (raw == null || raw.isBlank() || raw.startsWith("/")
                || raw.indexOf('\\') >= 0 || raw.indexOf('\0') >= 0
                || raw.matches("^[A-Za-z]:.*")) {
            throw new IllegalArgumentException("unsafe archive asset path: " + raw);
        }
        for (String segment : raw.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("unsafe archive asset path: " + raw);
            }
        }
        return raw;
    }
}
