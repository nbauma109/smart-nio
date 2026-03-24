package io.github.nbauma109.smartnio;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * Provider-specific {@link Path} implementation for mounted archive entries.
 * <p>
 * Paths use {@code /} as their separator, normalize {@code .} and {@code ..} segments eagerly, and are always scoped
 * to a single {@link SmartArchiveFileSystem}.
 * </p>
 */
final class SmartArchivePath implements Path {

    private final SmartArchiveFileSystem fileSystem;
    private final String normalizedPath;

    /**
     * Creates a normalized archive path.
     *
     * @param fileSystem owning filesystem
     * @param rawPath user-supplied path string
     */
    SmartArchivePath(SmartArchiveFileSystem fileSystem, String rawPath) {
        this.fileSystem = Objects.requireNonNull(fileSystem, "fileSystem");
        this.normalizedPath = normalize(Objects.requireNonNull(rawPath, "rawPath"));
    }

    @Override
    public SmartArchiveFileSystem getFileSystem() {
        return fileSystem;
    }

    @Override
    public boolean isAbsolute() {
        return normalizedPath.startsWith("/");
    }

    @Override
    public Path getRoot() {
        return isAbsolute() ? new SmartArchivePath(fileSystem, "/") : null;
    }

    @Override
    public Path getFileName() {
        List<String> segments = segments();
        if (segments.isEmpty()) {
            return null;
        }
        return new SmartArchivePath(fileSystem, segments.get(segments.size() - 1));
    }

    @Override
    public Path getParent() {
        if ("/".equals(normalizedPath) || normalizedPath.isEmpty()) {
            return null;
        }
        List<String> segments = segments();
        if (segments.size() == 1) {
            return isAbsolute() ? new SmartArchivePath(fileSystem, "/") : null;
        }
        StringJoiner joiner = new StringJoiner("/");
        for (int index = 0; index < segments.size() - 1; index++) {
            joiner.add(segments.get(index));
        }
        return new SmartArchivePath(fileSystem, (isAbsolute() ? "/" : "") + joiner);
    }

    @Override
    public int getNameCount() {
        return segments().size();
    }

    @Override
    public Path getName(int index) {
        List<String> segments = segments();
        if (index < 0 || index >= segments.size()) {
            throw new IllegalArgumentException("Index out of bounds: " + index);
        }
        return new SmartArchivePath(fileSystem, segments.get(index));
    }

    @Override
    public Path subpath(int beginIndex, int endIndex) {
        List<String> segments = segments();
        if (beginIndex < 0 || endIndex > segments.size() || beginIndex >= endIndex) {
            throw new IllegalArgumentException("Invalid subpath range");
        }
        StringJoiner joiner = new StringJoiner("/");
        for (int index = beginIndex; index < endIndex; index++) {
            joiner.add(segments.get(index));
        }
        return new SmartArchivePath(fileSystem, joiner.toString());
    }

    @Override
    public boolean startsWith(Path other) {
        SmartArchivePath otherPath = requireSameFileSystem(other);
        return normalizedPath.startsWith(otherPath.normalizedPath);
    }

    @Override
    public boolean startsWith(String other) {
        return startsWith(new SmartArchivePath(fileSystem, other));
    }

    @Override
    public boolean endsWith(Path other) {
        SmartArchivePath otherPath = requireSameFileSystem(other);
        return normalizedPath.endsWith(otherPath.normalizedPath);
    }

    @Override
    public boolean endsWith(String other) {
        return endsWith(new SmartArchivePath(fileSystem, other));
    }

    @Override
    public Path normalize() {
        return new SmartArchivePath(fileSystem, normalizedPath);
    }

    @Override
    public Path resolve(Path other) {
        SmartArchivePath otherPath = requireSameFileSystem(other);
        if (otherPath.isAbsolute()) {
            return otherPath;
        }
        if (normalizedPath.isEmpty()) {
            return otherPath;
        }
        if ("/".equals(normalizedPath)) {
            return new SmartArchivePath(fileSystem, "/" + otherPath.normalizedPath);
        }
        return new SmartArchivePath(fileSystem, normalizedPath + "/" + otherPath.normalizedPath);
    }

    @Override
    public Path resolve(String other) {
        return resolve(new SmartArchivePath(fileSystem, other));
    }

    @Override
    public Path resolveSibling(Path other) {
        Path parent = getParent();
        return parent == null ? other : parent.resolve(other);
    }

    @Override
    public Path resolveSibling(String other) {
        return resolveSibling(new SmartArchivePath(fileSystem, other));
    }

    @Override
    public Path relativize(Path other) {
        SmartArchivePath otherPath = requireSameFileSystem(other);
        if (isAbsolute() != otherPath.isAbsolute()) {
            throw new IllegalArgumentException("Cannot relativize absolute path against relative path");
        }
        List<String> sourceSegments = segments();
        List<String> targetSegments = otherPath.segments();
        int common = 0;
        while (common < sourceSegments.size()
                && common < targetSegments.size()
                && sourceSegments.get(common).equals(targetSegments.get(common))) {
            common++;
        }
        StringJoiner joiner = new StringJoiner("/");
        for (int index = common; index < sourceSegments.size(); index++) {
            joiner.add("..");
        }
        for (int index = common; index < targetSegments.size(); index++) {
            joiner.add(targetSegments.get(index));
        }
        return new SmartArchivePath(fileSystem, joiner.toString());
    }

    @Override
    public URI toUri() {
        return URI.create(fileSystem.provider().getScheme() + ":" + fileSystem.archivePath().toUri() + "!"
                + absolutePath().replace(" ", "%20"));
    }

    @Override
    public Path toAbsolutePath() {
        if (isAbsolute()) {
            return this;
        }
        return normalizedPath.isEmpty() ? new SmartArchivePath(fileSystem, "/")
                : new SmartArchivePath(fileSystem, "/" + normalizedPath);
    }

    @Override
    public Path toRealPath(LinkOption... options) throws IOException {
        return fileSystem.provider().toSmartPath(this).toAbsolutePath().normalize();
    }

    @Override
    public File toFile() {
        throw new UnsupportedOperationException("Archive paths are not backed by java.io.File");
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>[] events, WatchEvent.Modifier... modifiers) {
        throw new UnsupportedOperationException("Watch service is not supported");
    }

    @Override
    public WatchKey register(WatchService watcher, WatchEvent.Kind<?>... events) {
        throw new UnsupportedOperationException("Watch service is not supported");
    }

    @Override
    public Iterator<Path> iterator() {
        List<Path> paths = new ArrayList<>();
        for (String segment : segments()) {
            paths.add(new SmartArchivePath(fileSystem, segment));
        }
        return paths.iterator();
    }

    @Override
    public int compareTo(Path other) {
        return normalizedPath.compareTo(requireSameFileSystem(other).normalizedPath);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (!(object instanceof SmartArchivePath other)) {
            return false;
        }
        return fileSystem.equals(other.fileSystem) && normalizedPath.equals(other.normalizedPath);
    }

    @Override
    public int hashCode() {
        return Objects.hash(fileSystem, normalizedPath);
    }

    @Override
    public String toString() {
        return normalizedPath;
    }

    /**
     * Returns the normalized path segments without a leading root marker.
     *
     * @return immutable list of path segments
     */
    List<String> segments() {
        if (normalizedPath.isEmpty() || "/".equals(normalizedPath)) {
            return List.of();
        }
        String value = isAbsolute() ? normalizedPath.substring(1) : normalizedPath;
        return List.of(value.split("/"));
    }

    /**
     * Returns the absolute normalized representation of this path.
     *
     * @return absolute path string beginning with {@code /}
     */
    String absolutePath() {
        return isAbsolute() ? normalizedPath : toAbsolutePath().toString();
    }

    /**
     * Ensures another path belongs to the same mounted archive filesystem.
     *
     * @param path candidate path
     * @return the cast archive path
     */
    private SmartArchivePath requireSameFileSystem(Path path) {
        if (!(path instanceof SmartArchivePath other) || !fileSystem.equals(other.fileSystem)) {
            throw new IllegalArgumentException("Path is not associated with this file system: " + path);
        }
        return other;
    }

    /**
     * Normalizes a raw archive path string.
     * <p>
     * Backslashes are converted to forward slashes and {@code .}/{@code ..} handling matches standard path semantics
     * within a read-only mounted archive.
     * </p>
     *
     * @param rawPath raw user-supplied path
     * @return normalized path string
     */
    private static String normalize(String rawPath) {
        String sanitized = rawPath.replace('\\', '/');
        boolean absolute = sanitized.startsWith("/");
        String[] parts = sanitized.split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String part : parts) {
            if (shouldSkipSegment(part)) {
                // Ignore repeated separators and explicit current-directory markers.
            } else if ("..".equals(part)) {
                applyParentSegment(stack, absolute);
            } else {
                validateSegment(rawPath, part);
                stack.addLast(part);
            }
        }
        StringJoiner joiner = new StringJoiner("/");
        for (String segment : stack) {
            joiner.add(segment);
        }
        String joined = joiner.toString();
        if (absolute) {
            return joined.isEmpty() ? "/" : "/" + joined;
        }
        return joined;
    }

    private static boolean shouldSkipSegment(String part) {
        return part.isEmpty() || ".".equals(part);
    }

    private static void applyParentSegment(Deque<String> stack, boolean absolute) {
        if (!stack.isEmpty() && !"..".equals(stack.peekLast())) {
            stack.removeLast();
        } else if (!absolute) {
            stack.addLast("..");
        }
    }

    private static void validateSegment(String rawPath, String part) {
        if (part.indexOf('\0') >= 0) {
            throw new InvalidPathException(rawPath, "NUL character not allowed");
        }
    }
}
