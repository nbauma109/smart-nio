package io.github.nbauma109.smartnio;

import java.io.ByteArrayInputStream;
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

public final class SmartArchiveFileSystemProvider extends FileSystemProvider {

    private static final String SCHEME = "smartnio";

    private final Map<Path, SmartArchiveFileSystem> fileSystems = new ConcurrentHashMap<>();

    @Override
    public String getScheme() {
        return SCHEME;
    }

    @Override
    public FileSystem newFileSystem(URI uri, Map<String, ?> env) throws IOException {
        return newFileSystem(extractArchivePath(uri), env);
    }

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

    @Override
    public FileSystem getFileSystem(URI uri) {
        SmartArchiveFileSystem fileSystem = fileSystems.get(extractArchivePath(uri));
        if (fileSystem == null) {
            throw new FileSystemNotFoundException(uri.toString());
        }
        return fileSystem;
    }

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

    @Override
    public InputStream newInputStream(Path path, OpenOption... options) throws IOException {
        ensureReadOnlyOptions(options);
        ArchiveNode node = requireNode(toSmartPath(path));
        if (node.isDirectory()) {
            throw new IOException("Cannot open directory as a stream: " + path);
        }
        return new ByteArrayInputStream(node.data());
    }

    @Override
    public OutputStream newOutputStream(Path path, OpenOption... options) {
        throw new ReadOnlyFileSystemException();
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
                                              FileAttribute<?>... attrs) throws IOException {
        ensureReadOnlyOptions(options);
        ArchiveNode node = requireNode(toSmartPath(path));
        if (node.isDirectory()) {
            throw new IOException("Cannot open directory as a byte channel: " + path);
        }
        return new SeekableInMemoryByteChannel(node.data());
    }

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

    SmartArchivePath toSmartPath(Path path) {
        if (!(path instanceof SmartArchivePath archivePath)) {
            throw new IllegalArgumentException("Path is not managed by " + getScheme() + ": " + path);
        }
        return archivePath;
    }

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
