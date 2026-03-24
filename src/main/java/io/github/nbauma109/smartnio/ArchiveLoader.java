package io.github.nbauma109.smartnio;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Date;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.CompressorException;
import org.apache.commons.compress.compressors.CompressorStreamFactory;
import org.apache.commons.compress.utils.IOUtils;

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
                byte[] content = entry.isDirectory() ? new byte[0] : IOUtils.toByteArray(tarInputStream);
                addEntry(root, entry.getName(), entry.isDirectory(), content, entry.getModTime());
            }
        }
    }

    private static void loadSevenZipArchive(ArchiveNode root, Path archivePath) throws IOException {
        try (SevenZFile sevenZFile = new SevenZFile(archivePath.toFile())) {
            SevenZArchiveEntry entry;
            while ((entry = sevenZFile.getNextEntry()) != null) {
                if (!entry.hasStream() && !entry.isDirectory()) {
                    continue;
                }
                byte[] content = entry.isDirectory() ? new byte[0] : readSevenZipEntry(sevenZFile, entry);
                addEntry(root, entry.getName(), entry.isDirectory(), content, entry.getLastModifiedDate());
            }
        }
    }

    private static byte[] readSevenZipEntry(SevenZFile sevenZFile, SevenZArchiveEntry entry) throws IOException {
        long size = entry.getSize();
        if (size > Integer.MAX_VALUE) {
            throw new IOException("Entry too large to load in memory: " + entry.getName());
        }
        byte[] content = new byte[(int) size];
        int offset = 0;
        while (offset < content.length) {
            int read = sevenZFile.read(content, offset, content.length - offset);
            if (read < 0) {
                break;
            }
            offset += read;
        }
        return content;
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

    private static void addEntry(ArchiveNode root, String rawName, boolean directory, byte[] content, Date lastModifiedDate) {
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
        current.putFile(leafName, content, modifiedTime);
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
}
