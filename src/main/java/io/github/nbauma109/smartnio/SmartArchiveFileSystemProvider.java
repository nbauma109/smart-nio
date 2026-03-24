package io.github.nbauma109.smartnio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.FileSystem;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.spi.FileSystemProvider;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;

/**
 * Read-only {@link FileSystemProvider} implementation for archive-backed filesystems.
 * <p>
 * Supported archive families currently include plain tar streams, compressed tar streams recognized by Apache Commons
 * Compress, and {@code 7z}. The provider follows the same broad lifecycle model as the JDK ZIP filesystem provider:
 * mounting an archive builds an in-memory index of entries, while file content is opened lazily when a path is read.
 * </p>
 * <p>
 * Filesystems are identified by the custom {@code smartnio:} URI scheme and remain open until
 * {@link FileSystem#close()} is called explicitly.
 * </p>
 */
public final class SmartArchiveFileSystemProvider extends FileSystemProvider {

    private static final String SCHEME = "smartnio";

    private final Map<Path, SmartArchiveFileSystem> fileSystems = new ConcurrentHashMap<>();

    /**
     * Returns the provider URI scheme.
     *
     * @return {@code smartnio}
     */
    @Override
    public String getScheme() {
        return SCHEME;
    }

    /**
     * Opens an archive filesystem from a provider URI.
     *
     * @param uri archive URI using the {@code smartnio:...!/entry} syntax
     * @param env provider environment, currently unused
     * @return the mounted archive filesystem
     * @throws IOException if the archive cannot be opened or indexed
     */
    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return newFileSystem(extractArchivePath(uri), env);
    }

    /**
     * Opens an archive filesystem from a regular {@link Path}.
     *
     * @param path archive path on the default filesystem
     * @param env provider environment, currently unused
     * @return the mounted archive filesystem
     * @throws IOException if the archive cannot be opened or indexed
     */
    @Override
    public FileSystem newFileSystem(Path path, Map<String, ?> env) throws IOException {
        Objects.requireNonNull(path, "path");
        Path archivePath = path.toAbsolutePath().normalize();
        if (!Files.exists(archivePath)) {
            throw new NoSuchFileException(archivePath.toString());
        }
        ArchiveNode root = ArchiveLoader.load(archivePath);
        SmartArchiveFileSystem fileSystem = new SmartArchiveFileSystem(this, archivePath, root);
        SmartArchiveFileSystem existing = fileSystems.putIfAbsent(archivePath, fileSystem);
        if (existing != null) {
            throw new FileSystemAlreadyExistsException("Archive is already mounted: " + archivePath);
        }
        return fileSystem;
    }

    /**
     * Looks up an already-mounted filesystem by URI.
     *
     * @param uri provider URI pointing at an archive
     * @return the mounted filesystem
     * @throws FileSystemNotFoundException if the archive is not mounted
     */
    @Override
    public FileSystem getFileSystem(URI uri) {
        SmartArchiveFileSystem fileSystem = fileSystems.get(extractArchivePath(uri));
        if (fileSystem == null) {
            throw new FileSystemNotFoundException(uri.toString());
        }
        return fileSystem;
    }

    /**
     * Resolves a provider URI to an archive path.
     * <p>
     * If the target archive is not mounted yet, it is mounted automatically and remains open until explicitly closed.
     * </p>
     *
     * @param uri provider URI using the {@code smartnio:...!/entry} syntax
     * @return the resolved path inside the mounted archive filesystem
     */
    @Override
    public Path getPath(URI uri) {
        Path archivePath = extractArchivePath(uri);
        SmartArchiveFileSystem fileSystem = fileSystems.get(archivePath);
        if (fileSystem == null) {
            try {
                fileSystem = (SmartArchiveFileSystem) newFileSystem(archivePath, Map.of());
            } catch (IOException exception) {
                throw new IllegalArgumentException("Unable to open archive for URI: " + uri, exception);
            }
        }
        String entryPath = extractEntryPath(uri);
        return fileSystem.getPath(entryPath == null || entryPath.isBlank() ? "/" : entryPath);
    }

    /**
     * Opens a lazy input stream for an archive entry.
     *
     * @param path archive path to a regular file entry
     * @param options read-only options; only {@link StandardOpenOption#READ} is accepted
     * @return a stream for the requested entry
     * @throws IOException if the entry cannot be read
     */
    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        ensureReadOnlyOptions(options);
        SmartArchivePath archivePath = toSmartPath(path);
        ArchiveNode node = requireNode(archivePath);
        if (node.isDirectory()) {
            throw new IOException("Cannot open directory as a stream: " + path);
        }
        return ArchiveLoader.openInputStream(archivePath.getFileSystem().archivePath(), node);
    }

    /**
     * Always fails because this provider is read-only.
     *
     * @throws ReadOnlyFileSystemException always
     */
    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /**
     * Opens a seekable read channel for an archive entry.
     * <p>
     * The channel is backed by the bytes of the requested entry only, not by the entire archive.
     * </p>
     *
     * @param path archive path to a regular file entry
     * @param options read-only options; only {@link StandardOpenOption#READ} is accepted
     * @param attrs ignored file attributes
     * @return a read-only seekable channel for the requested entry
     * @throws IOException if the entry cannot be read
     */
    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        ensureReadOnlyOptions(options);
        SmartArchivePath archivePath = toSmartPath(path);
        ArchiveNode node = requireNode(archivePath);
        if (node.isDirectory()) {
            throw new IOException("Cannot open directory as a byte channel: " + path);
        }
        try (InputStream inputStream = ArchiveLoader.openInputStream(archivePath.getFileSystem().archivePath(), node)) {
            return new SeekableInMemoryByteChannel(inputStream.readAllBytes());
        }
    }

    /**
     * Opens a directory stream for a directory inside the mounted archive.
     *
     * @param dir directory path
     * @param filter optional entry filter
     * @return a lazily-evaluated directory stream
     * @throws IOException if the path is not a directory or does not exist
     */
    @Override
    public DirectoryStream<Path> newDirectoryStream(Path dir, DirectoryStream.Filter<? super Path> filter)
            throws IOException {
        SmartArchivePath archivePath = toSmartPath(dir);
        ArchiveNode node = requireNode(archivePath);
        if (!node.isDirectory()) {
            throw new IOException("Path is not a directory: " + dir);
        }
        return new DirectoryStream<>() {
            @Override
            public Iterator<Path> iterator() {
                return node.children().stream()
                        .map(child -> archivePath.resolve(child.name()))
                        .filter(path -> accept(filter, path))
                        .iterator();
            }

            @Override
            public void close() {
                // The directory stream is backed by immutable in-memory metadata, so there is nothing to release.
            }
        };
    }

    @Override
    public void createDirectory(Path dir, FileAttribute<?>... attrs) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void delete(Path path) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void copy(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public void move(Path source, Path target, CopyOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public boolean isSameFile(Path path, Path path2) {
        SmartArchivePath first = toSmartPath(path);
        SmartArchivePath second = toSmartPath(path2);
        return first.toAbsolutePath().normalize().equals(second.toAbsolutePath().normalize());
    }

    @Override
    public boolean isHidden(Path path) {
        return false;
    }

    @Override
    public FileStore getFileStore(Path path) {
        return toSmartPath(path).getFileSystem().fileStore();
    }

    @Override
    public void checkAccess(Path path, AccessMode... modes) throws IOException {
        requireNode(toSmartPath(path));
        for (AccessMode mode : modes) {
            if (mode != AccessMode.READ) {
                throw new ReadOnlyFileSystemException();
            }
        }
    }

    @Override
    public <V extends FileAttributeView> V getFileAttributeView(Path path, Class<V> type, LinkOption... options) {
        if (type == null || !BasicFileAttributeView.class.isAssignableFrom(type)) {
            return null;
        }
        ArchiveNode node = requireNodeUnchecked(toSmartPath(path));
        return type.cast(new SmartArchiveFileAttributeView(node));
    }

    @Override
    public <A extends BasicFileAttributes> A readAttributes(Path path, Class<A> type, LinkOption... options)
            throws IOException {
        if (type == null || !BasicFileAttributes.class.isAssignableFrom(type)) {
            throw new UnsupportedOperationException("Only basic attributes are supported");
        }
        return type.cast(new ArchiveFileAttributes(requireNode(toSmartPath(path))));
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options) throws IOException {
        BasicFileAttributes basicAttributes = readAttributes(path, BasicFileAttributes.class, options);
        Map<String, Object> values = new HashMap<>();
        String attributeList = attributes.startsWith("basic:") ? attributes.substring("basic:".length()) : attributes;

        putAttribute(values, attributeList, "*", "size", basicAttributes.size());
        putAttribute(values, attributeList, "*", "isRegularFile", basicAttributes.isRegularFile());
        putAttribute(values, attributeList, "*", "isDirectory", basicAttributes.isDirectory());
        putAttribute(values, attributeList, "*", "lastModifiedTime", basicAttributes.lastModifiedTime());
        putAttribute(values, attributeList, "*", "creationTime", basicAttributes.creationTime());
        putAttribute(values, attributeList, "*", "lastAccessTime", basicAttributes.lastAccessTime());
        putAttribute(values, attributeList, "*", "fileKey", basicAttributes.fileKey());
        return values;
    }

    @Override
    public void setAttribute(Path path, String attribute, Object value, LinkOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    /**
     * Casts a generic NIO path to the provider-specific path implementation.
     *
     * @param path path expected to belong to this provider
     * @return the provider-specific archive path
     */
    SmartArchivePath toSmartPath(Path path) {
        if (!(path instanceof SmartArchivePath archivePath)) {
            throw new IllegalArgumentException("Path is not managed by " + getScheme() + ": " + path);
        }
        return archivePath;
    }

    /**
     * Removes a filesystem from the provider's mount registry.
     *
     * @param archivePath normalized archive path used as the mount key
     */
    void unregister(Path archivePath) {
        fileSystems.remove(archivePath.toAbsolutePath().normalize());
    }

    private Path extractArchivePath(URI uri) {
        if (!SCHEME.equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("Unsupported URI scheme: " + uri);
        }
        String schemeSpecificPart = uri.getSchemeSpecificPart();
        int separator = schemeSpecificPart.indexOf('!');
        String archivePart = separator >= 0 ? schemeSpecificPart.substring(0, separator) : schemeSpecificPart;
        return Path.of(URI.create(archivePart)).toAbsolutePath().normalize();
    }

    private String extractEntryPath(URI uri) {
        String schemeSpecificPart = uri.getSchemeSpecificPart();
        int separator = schemeSpecificPart.indexOf('!');
        return separator >= 0 ? schemeSpecificPart.substring(separator + 1) : "/";
    }

    private ArchiveNode requireNode(SmartArchivePath path) throws IOException {
        ArchiveNode node = path.getFileSystem().lookup((SmartArchivePath) path.toAbsolutePath().normalize());
        if (node == null) {
            throw new NoSuchFileException(path.toString());
        }
        return node;
    }

    private ArchiveNode requireNodeUnchecked(SmartArchivePath path) {
        try {
            return requireNode(path);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Path does not exist: " + path, exception);
        }
    }

    private static void ensureReadOnlyOptions(OpenOption... options) {
        for (OpenOption option : options) {
            if (option != null && option != StandardOpenOption.READ) {
                throw new ReadOnlyFileSystemException();
            }
        }
    }

    private static void ensureReadOnlyOptions(Set<? extends OpenOption> options) {
        if (options == null) {
            return;
        }
        for (OpenOption option : options) {
            if (option != null && option != StandardOpenOption.READ) {
                throw new ReadOnlyFileSystemException();
            }
        }
    }

    private static void putAttribute(Map<String, Object> target, String attributeList, String wildcard,
                                     String attributeName, Object value) {
        if (wildcard.equals(attributeList) || attributeList.contains(attributeName)) {
            target.put(attributeName, value);
        }
    }

    private static boolean accept(DirectoryStream.Filter<? super Path> filter, Path path) {
        try {
            return filter == null || filter.accept(path);
        } catch (IOException exception) {
            throw new DirectoryIteratorException(exception);
        }
    }
}
