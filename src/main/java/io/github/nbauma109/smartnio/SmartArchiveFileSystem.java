package io.github.nbauma109.smartnio;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.WatchService;
import java.nio.file.attribute.UserPrincipalLookupService;
import java.util.Collections;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only {@link FileSystem} implementation backed by a mounted archive.
 * <p>
 * The filesystem holds the normalized archive path, a metadata tree of entries, and the provider-side registration used
 * to find the mount again from provider URIs. Closing the filesystem unregisters the mount but does not happen
 * implicitly when individual entry streams are closed.
 * </p>
 */
final class SmartArchiveFileSystem extends FileSystem {

    private final SmartArchiveFileSystemProvider provider;
    private final Path archivePath;
    private final ArchiveNode rootNode;
    private final SmartArchiveFileStore fileStore;
    private volatile boolean open;

    /**
     * Creates a mounted archive filesystem.
     *
     * @param provider owning provider
     * @param archivePath normalized archive path on the host filesystem
     * @param rootNode root of the indexed archive entry tree
     */
    SmartArchiveFileSystem(SmartArchiveFileSystemProvider provider, Path archivePath, ArchiveNode rootNode) {
        this.provider = provider;
        this.archivePath = archivePath;
        this.rootNode = rootNode;
        this.fileStore = new SmartArchiveFileStore(
                archivePath.getFileName() == null ? archivePath.toString() : archivePath.getFileName().toString());
        this.open = true;
    }

    @Override
    public SmartArchiveFileSystemProvider provider() {
        return provider;
    }

    @Override
    public void close() throws IOException {
        if (!open) {
            return;
        }
        open = false;
        provider.unregister(archivePath);
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public String getSeparator() {
        return "/";
    }

    @Override
    public Iterable<Path> getRootDirectories() {
        return Collections.singletonList(new SmartArchivePath(this, "/"));
    }

    @Override
    public Iterable<FileStore> getFileStores() {
        return Collections.singletonList(fileStore);
    }

    @Override
    public Set<String> supportedFileAttributeViews() {
        return Collections.singleton("basic");
    }

    @Override
    public Path getPath(String first, String... more) {
        StringBuilder builder = new StringBuilder(first == null ? "" : first);
        for (String segment : more) {
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '/') {
                builder.append('/');
            }
            builder.append(segment);
        }
        return new SmartArchivePath(this, builder.toString());
    }

    @Override
    public PathMatcher getPathMatcher(String syntaxAndPattern) {
        String[] parts = syntaxAndPattern.split(":", 2);
        if (parts.length != 2) {
            throw new IllegalArgumentException("Expected syntax:pattern");
        }
        Pattern pattern = switch (parts[0]) {
            case "regex" -> Pattern.compile(parts[1]);
            case "glob" -> Pattern.compile(globToRegex(parts[1]));
            default -> throw new UnsupportedOperationException("Unsupported syntax: " + parts[0]);
        };
        return path -> pattern.matcher(path.toString()).matches();
    }

    @Override
    public UserPrincipalLookupService getUserPrincipalLookupService() {
        throw new UnsupportedOperationException("User principals are not supported");
    }

    @Override
    public WatchService newWatchService() {
        throw new UnsupportedOperationException("Watch service is not supported");
    }

    Path archivePath() {
        return archivePath;
    }

    /**
     * Returns the synthetic file store representing the mounted archive.
     *
     * @return archive file store
     */
    SmartArchiveFileStore fileStore() {
        return fileStore;
    }

    /**
     * Resolves a normalized archive path against the indexed entry tree.
     *
     * @param path path inside this filesystem
     * @return the matching archive node, or {@code null} if no entry exists
     */
    ArchiveNode lookup(SmartArchivePath path) {
        ensureOpen();
        ArchiveNode current = rootNode;
        for (String segment : path.segments()) {
            current = current.child(segment);
            if (current == null) {
                return null;
            }
        }
        return current;
    }

    private void ensureOpen() {
        if (!open) {
            throw new IllegalStateException("File system is closed");
        }
    }

    /**
     * Converts a glob expression to the regex understood by {@link Pattern}.
     *
     * @param globPattern glob pattern as accepted by {@link #getPathMatcher(String)}
     * @return regex equivalent for the supported glob subset
     */
    private String globToRegex(String globPattern) {
        StringBuilder regex = new StringBuilder("^");
        boolean escaping = false;
        for (int index = 0; index < globPattern.length(); index++) {
            char current = globPattern.charAt(index);
            if (escaping) {
                regex.append('\\').append(current);
                escaping = false;
                continue;
            }
            switch (current) {
                case '\\' -> escaping = true;
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.' -> regex.append("\\.");
                case '(' -> regex.append("\\(");
                case ')' -> regex.append("\\)");
                case '+' -> regex.append("\\+");
                case '|' -> regex.append("\\|");
                case '^' -> regex.append("\\^");
                case '$' -> regex.append("\\$");
                case '{' -> regex.append('(');
                case '}' -> regex.append(')');
                case ',' -> regex.append('|');
                default -> regex.append(current);
            }
        }
        regex.append('$');
        return regex.toString();
    }
}
