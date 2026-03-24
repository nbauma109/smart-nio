package io.github.nbauma109.smartnio;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;

final class ArchiveTestFixtures {

    private ArchiveTestFixtures() {
    }

    static SmartArchiveFileSystem createFileSystem(Path archivePath) {
        ArchiveNode root = ArchiveNode.root();
        ArchiveNode docs = root.ensureDirectory("docs");
        docs.putFile("hello.txt", "hello from tar".getBytes(StandardCharsets.UTF_8), FileTime.fromMillis(1_000L));
        docs.putFile("notes (final).txt", "notes".getBytes(StandardCharsets.UTF_8), FileTime.fromMillis(2_000L));
        ArchiveNode nested = docs.ensureDirectory("nested");
        nested.putFile("deep.txt", "deep-value".getBytes(StandardCharsets.UTF_8), FileTime.fromMillis(3_000L));
        root.putFile("root.txt", "root-value".getBytes(StandardCharsets.UTF_8), FileTime.fromMillis(4_000L));
        root.putFile("space name.txt", "with space".getBytes(StandardCharsets.UTF_8), FileTime.fromMillis(5_000L));
        return new SmartArchiveFileSystem(new SmartArchiveFileSystemProvider(), archivePath, root);
    }

    static void createTarArchive(Path archive, boolean gzip) throws IOException {
        try (OutputStream outputStream = openArchiveOutputStream(archive, gzip);
             TarArchiveOutputStream tarOutputStream = new TarArchiveOutputStream(outputStream)) {
            tarOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            addDirectoryEntry(tarOutputStream, "docs/");
            addDirectoryEntry(tarOutputStream, "./docs/nested/");
            addFileEntry(tarOutputStream, "docs/hello.txt", "hello from tar");
            addFileEntry(tarOutputStream, "/docs/nested/deep.txt", "deep-value");
            addFileEntry(tarOutputStream, "root.txt", "root-value");
            addFileEntry(tarOutputStream, "space name.txt", "with space");
            tarOutputStream.finish();
        }
    }

    static void createSevenZipArchive(Path archive) throws IOException {
        try (SevenZOutputFile sevenZOutputFile = new SevenZOutputFile(archive.toFile())) {
            var directoryEntry = sevenZOutputFile.createArchiveEntry(archive.resolveSibling("folder").toFile(), "folder/");
            sevenZOutputFile.putArchiveEntry(directoryEntry);
            sevenZOutputFile.closeArchiveEntry();

            byte[] content = "seven-zip".getBytes(StandardCharsets.UTF_8);
            var fileEntry = sevenZOutputFile.createArchiveEntry(archive.resolveSibling("data.txt").toFile(), "folder/data.txt");
            fileEntry.setSize(content.length);
            sevenZOutputFile.putArchiveEntry(fileEntry);
            sevenZOutputFile.write(content);
            sevenZOutputFile.closeArchiveEntry();
            sevenZOutputFile.finish();
        }
    }

    private static OutputStream openArchiveOutputStream(Path archive, boolean gzip) throws IOException {
        OutputStream outputStream = Files.newOutputStream(archive);
        return gzip ? new GzipCompressorOutputStream(outputStream) : outputStream;
    }

    private static void addDirectoryEntry(TarArchiveOutputStream tarOutputStream, String name) throws IOException {
        TarArchiveEntry entry = new TarArchiveEntry(name);
        tarOutputStream.putArchiveEntry(entry);
        tarOutputStream.closeArchiveEntry();
    }

    private static void addFileEntry(TarArchiveOutputStream tarOutputStream, String name, String content)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        TarArchiveEntry entry = new TarArchiveEntry(name);
        entry.setSize(bytes.length);
        tarOutputStream.putArchiveEntry(entry);
        tarOutputStream.write(bytes);
        tarOutputStream.closeArchiveEntry();
    }
}
