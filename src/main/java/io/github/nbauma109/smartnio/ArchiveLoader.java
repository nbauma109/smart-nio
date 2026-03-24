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

final class ArchiveLoader {

    private static final int MARK_LIMIT = 8_192;

    private ArchiveLoader() {
    }

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

    private static InputStream wrapCompressedStream(BufferedInputStream inputStream) throws IOException {
        inputStream.mark(MARK_LIMIT);
        try {
            return new CompressorStreamFactory().createCompressorInputStream(inputStream);
        } catch (CompressorException exception) {
            inputStream.reset();
            return inputStream;
        }
    }

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

    private static FileTime toFileTime(Date lastModifiedDate) {
        return lastModifiedDate == null ? FileTime.fromMillis(0L) : FileTime.fromMillis(lastModifiedDate.getTime());
    }

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
        } catch (IOException exception) {
            closeQuietly(tarInputStream);
            throw exception;
        } catch (RuntimeException exception) {
            closeQuietly(tarInputStream);
            throw exception;
        }
        closeQuietly(tarInputStream);
        throw new NoSuchFileException(node.absolutePath());
    }

    private static InputStream openSevenZipEntryStream(Path archivePath, ArchiveNode node) throws IOException {
        SevenZFile sevenZFile = null;
        try {
            sevenZFile = openSevenZipFile(archivePath);
            SevenZArchiveEntry entry = entryAtIndex(sevenZFile, node.archiveEntryIndex());
            if (entry != null && entry.hasStream() && !entry.isDirectory()) {
                return new CloseOnCloseInputStream(sevenZFile.getInputStream(entry), sevenZFile);
            }
        } catch (IOException exception) {
            closeQuietly(sevenZFile);
            throw exception;
        } catch (RuntimeException exception) {
            closeQuietly(sevenZFile);
            throw exception;
        }
        closeQuietly(sevenZFile);
        throw new NoSuchFileException(node.absolutePath());
    }

    private static SevenZFile openSevenZipFile(Path archivePath) throws IOException {
        return SevenZFile.builder()
                .setFile(archivePath.toFile())
                .get();
    }

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

    private static void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
        }
    }

    private static final class BoundedArchiveEntryInputStream extends FilterInputStream {

        private long remaining;

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

    private static final class CloseOnCloseInputStream extends FilterInputStream {

        private final Closeable closeable;

        private CloseOnCloseInputStream(InputStream inputStream, Closeable closeable) {
            super(inputStream);
            this.closeable = closeable;
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
