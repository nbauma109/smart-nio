package io.github.nbauma109.smartnio;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.NoSuchFileException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Date;
import java.util.Objects;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;

/**
 * Builds and reuses archive metadata indexes for mounted filesystems.
 * <p>
 * The loader has two responsibilities:
 * </p>
 * <p>
 * First, it scans the archive once at mount time to build an {@link ArchiveNode} tree containing metadata only.
 * Second, it reopens the source archive on demand when a regular file entry is read.
 * </p>
 */
final class ArchiveLoader {

    private static final int MARK_LIMIT = 8_192;

    private ArchiveLoader() {
    }

    /**
     * Loads the metadata tree for an archive.
     *
     * @param archivePath host path to the archive file
     * @return root node of the indexed archive tree
     * @throws IOException if the archive cannot be scanned
     */
    static ArchiveNode load(Path archivePath) throws IOException {
        ArchiveNode root = ArchiveNode.root();
        if (isSevenZip(archivePath)) {
            loadSevenZipArchive(root, archivePath);
            return root;
        }
        loadTarArchive(root, archivePath);
        return root;
    }

    private static boolean isSevenZip(Path archivePath) {
        return archivePath.getFileName() != null
                && archivePath.getFileName().toString().toLowerCase().endsWith(".7z");
    }

    private static void loadTarArchive(ArchiveNode root, Path archivePath) throws IOException {
        try (InputStream fileInputStream = Files.newInputStream(archivePath);
             BufferedInputStream bufferedInputStream = new BufferedInputStream(fileInputStream);
             InputStream archiveInputStream = wrapCompressedStream(bufferedInputStream);
             TarArchiveInputStream tarInputStream = new TarArchiveInputStream(archiveInputStream)) {

            TarArchiveEntry entry;
            while ((entry = tarInputStream.getNextTarEntry()) != null) {
                if (!tarInputStream.canReadEntryData(entry)) {
                    continue;
                }
                addEntry(root, entry.getName(), entry.isDirectory(), entry.getSize(), entry.getModTime(), -1);
            }
        }
    }

    private static void loadSevenZipArchive(ArchiveNode root, Path archivePath) throws IOException {
        try (SevenZFile sevenZFile = openSevenZipFile(archivePath)) {
            int entryIndex = 0;
            for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
                if (!entry.hasStream() && !entry.isDirectory()) {
                    entryIndex++;
                    continue;
                }
                addEntry(root, entry.getName(), entry.isDirectory(), entry.getSize(), entry.getLastModifiedDate(),
                        entryIndex);
                entryIndex++;
            }
        }
    }

    /**
     * Opens a stream for a single archive entry without closing the mounted filesystem itself.
     *
     * @param archivePath host path to the archive file
     * @param node indexed file node to read
     * @return stream for the requested entry
     * @throws IOException if the entry cannot be reopened
     */
    static InputStream openInputStream(Path archivePath, ArchiveNode node) throws IOException {
        Objects.requireNonNull(archivePath, "archivePath");
        Objects.requireNonNull(node, "node");
        if (node.isDirectory()) {
            throw new IOException("Cannot open a directory entry: " + node.absolutePath());
        }
        return isSevenZip(archivePath)
                ? openSevenZipEntryStream(archivePath, node)
                : openTarEntryStream(archivePath, node);
    }

    /**
     * Detects and wraps compressed tar streams while leaving uncompressed streams untouched.
     *
     * @param inputStream buffered archive input
     * @return decompressed stream if compression was detected, otherwise the original buffered stream
     * @throws IOException if stream detection fails
     */
    private static InputStream wrapCompressedStream(BufferedInputStream inputStream) throws IOException {
        inputStream.mark(MARK_LIMIT);
        try {
            return new CompressorStreamFactory().createCompressorInputStream(inputStream);
        } catch (CompressorException exception) {
            inputStream.reset();
            return inputStream;
        }
    }

    /**
     * Adds a scanned archive entry to the in-memory metadata tree.
     *
     * @param root root node of the metadata tree
     * @param rawName raw entry name from the archive
     * @param directory whether the entry is a directory
     * @param size file size in bytes
     * @param lastModifiedDate last-modified date from the archive, if available
     * @param archiveEntryIndex format-specific entry index, or {@code -1}
     */
    private static void addEntry(ArchiveNode root, String rawName, boolean directory, long size,
                                 Date lastModifiedDate, int archiveEntryIndex) {
        String normalized = normalizeEntryName(rawName);
        if (normalized.isEmpty()) {
            return;
        }

        String[] segments = normalized.split("/");
        ArchiveNode current = root;
        for (int index = 0; index < segments.length - 1; index++) {
            current = current.ensureDirectory(segments[index]);
        }

        FileTime modifiedTime = toFileTime(lastModifiedDate);
        String leafName = segments[segments.length - 1];
        if (directory) {
            current.ensureDirectory(leafName).markDirectory(modifiedTime);
            return;
        }
        current.putFile(leafName, size, modifiedTime, archiveEntryIndex);
    }

    /**
     * Normalizes archive-native entry names to the provider's internal representation.
     *
     * @param rawName raw entry name from the archive
     * @return normalized relative entry name
     */
    private static String normalizeEntryName(String rawName) {
        String normalized = rawName.replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        while (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    /**
     * Converts a nullable {@link Date} to a {@link FileTime}.
     *
     * @param lastModifiedDate source date, possibly {@code null}
     * @return corresponding file time, or the epoch when absent
     */
    private static FileTime toFileTime(Date lastModifiedDate) {
        return lastModifiedDate == null ? FileTime.fromMillis(0L) : FileTime.fromMillis(lastModifiedDate.getTime());
    }

    /**
     * Reopens a tar-family archive and scans forward to the requested entry.
     *
     * @param archivePath host path to the archive file
     * @param node indexed file node to read
     * @return bounded stream exposing only the requested entry contents
     * @throws IOException if the entry cannot be found or reopened
     */
    private static InputStream openTarEntryStream(Path archivePath, ArchiveNode node) throws IOException {
        TarArchiveInputStream tarInputStream = null;
        try {
            tarInputStream = new TarArchiveInputStream(wrapCompressedStream(new BufferedInputStream(Files.newInputStream(archivePath))));
            TarArchiveEntry entry;
            while ((entry = tarInputStream.getNextTarEntry()) != null) {
                if (!tarInputStream.canReadEntryData(entry) || entry.isDirectory()) {
                    continue;
                }
                if (node.entryName().equals(normalizeEntryName(entry.getName()))) {
                    return new BoundedArchiveEntryInputStream(tarInputStream, entry.getSize());
                }
            }
        } catch (IOException | RuntimeException exception) {
            closeQuietly(tarInputStream);
            throw exception;
        }
        closeQuietly(tarInputStream);
        throw new NoSuchFileException(node.absolutePath());
    }

    /**
     * Reopens a {@code 7z} archive and resolves the requested entry using its indexed metadata position.
     *
     * @param archivePath host path to the archive file
     * @param node indexed file node to read
     * @return stream exposing only the requested entry contents
     * @throws IOException if the entry cannot be found or reopened
     */
    private static InputStream openSevenZipEntryStream(Path archivePath, ArchiveNode node) throws IOException {
        SevenZFile sevenZFile = null;
        try {
            sevenZFile = openSevenZipFile(archivePath);
            SevenZArchiveEntry entry = entryAtIndex(sevenZFile, node.archiveEntryIndex());
            if (entry != null && entry.hasStream() && !entry.isDirectory()) {
                return new CloseOnCloseInputStream(sevenZFile.getInputStream(entry), sevenZFile);
            }
        } catch (IOException | RuntimeException exception) {
            closeQuietly(sevenZFile);
            throw exception;
        }
        closeQuietly(sevenZFile);
        throw new NoSuchFileException(node.absolutePath());
    }

    /**
     * Opens a {@link SevenZFile} instance for indexed metadata and content access.
     *
     * @param archivePath host path to the archive file
     * @return opened {@code 7z} file reader
     * @throws IOException if the archive cannot be opened
     */
    private static SevenZFile openSevenZipFile(Path archivePath) throws IOException {
        return SevenZFile.builder()
                .setFile(archivePath.toFile())
                .get();
    }

    /**
     * Resolves a {@code 7z} entry by its archive index.
     *
     * @param sevenZFile opened archive reader
     * @param entryIndex zero-based archive entry index
     * @return matching entry or {@code null} if the index is invalid
     */
    private static SevenZArchiveEntry entryAtIndex(SevenZFile sevenZFile, int entryIndex) {
        if (entryIndex < 0) {
            return null;
        }
        int currentIndex = 0;
        for (SevenZArchiveEntry entry : sevenZFile.getEntries()) {
            if (currentIndex == entryIndex) {
                return entry;
            }
            currentIndex++;
        }
        return null;
    }

    /**
     * Closes an auxiliary stream or archive reader while intentionally discarding secondary failures.
     *
     * @param closeable resource to close, possibly {@code null}
     */
    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort cleanup only. The original failure path must not be masked by a secondary close failure.
        }
    }

    /**
     * Input stream wrapper that prevents callers from reading beyond the current tar entry.
     */
    private static final class BoundedArchiveEntryInputStream extends FilterInputStream {

        private long remaining;

        /**
         * Creates a bounded wrapper for the current tar entry stream.
         *
         * @param inputStream underlying tar stream positioned at the entry data
         * @param size number of bytes exposed to callers
         */
        private BoundedArchiveEntryInputStream(InputStream inputStream, long size) {
            super(inputStream);
            this.remaining = Math.max(0L, size);
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0L) {
                return -1;
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            if (remaining == 0L) {
                return -1;
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }

    /**
     * Input stream wrapper that closes both the entry stream and its owning archive reader together.
     */
    private static final class CloseOnCloseInputStream extends FilterInputStream {

        private final Closeable closeable;

        /**
         * Creates a close-coupled stream wrapper.
         *
         * @param inputStream entry input stream exposed to callers
         * @param closeable owning resource that must be closed with the stream
         */
        private CloseOnCloseInputStream(InputStream inputStream, Closeable closeable) {
            super(inputStream);
            this.closeable = closeable;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, buffer.length);
            return in.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;
            try {
                super.close();
            } catch (IOException exception) {
                failure = exception;
            }
            try {
                closeable.close();
            } catch (IOException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }
}
