package io.github.nbauma109.smartnio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmartArchiveFileSystemProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTarEntriesThroughFilesApi() throws Exception {
        Path archive = tempDir.resolve("sample.tar");
        createTarArchive(archive, false);

        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        try (var fileSystem = provider.newFileSystem(archive, Map.of())) {
            Path rootFile = fileSystem.getPath("/root.txt");
            Path nestedFile = fileSystem.getPath("/docs/hello.txt");
            Path docsDirectory = fileSystem.getPath("/docs");

            assertEquals("root-value", Files.readString(rootFile));
            assertEquals("hello from tar", Files.readString(nestedFile));
            assertTrue(Files.isDirectory(docsDirectory));

            try (Stream<Path> children = Files.list(fileSystem.getPath("/"))) {
                List<String> names = children.map(path -> path.getFileName().toString()).sorted().toList();
                assertEquals(List.of("docs", "root.txt"), names);
            }

            BasicFileAttributes attributes = Files.readAttributes(nestedFile, BasicFileAttributes.class);
            assertTrue(attributes.isRegularFile());
            assertEquals("hello from tar".getBytes(StandardCharsets.UTF_8).length, attributes.size());
        }
    }

    @Test
    void resolvesCompressedTarEntriesByUri() throws Exception {
        Path archive = tempDir.resolve("sample.tar.gz");
        createTarArchive(archive, true);

        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        URI uri = URI.create("smartnio:" + archive.toUri() + "!/docs/hello.txt");

        String content = Files.readString(provider.getPath(uri));

        assertEquals("hello from tar", content);
    }

    @Test
    void readsSevenZipEntries() throws Exception {
        Path archive = tempDir.resolve("sample.7z");
        createSevenZipArchive(archive);

        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        try (var fileSystem = provider.newFileSystem(archive, Map.of())) {
            Path dataFile = fileSystem.getPath("/folder/data.txt");
            BasicFileAttributes attributes = Files.readAttributes(fileSystem.getPath("/folder"), BasicFileAttributes.class);

            assertEquals("seven-zip", Files.readString(dataFile));
            assertTrue(attributes.isDirectory());
            assertFalse(attributes.isRegularFile());
        }
    }

    private static void createTarArchive(Path archive, boolean gzip) throws IOException {
        try (OutputStream outputStream = openArchiveOutputStream(archive, gzip);
             TarArchiveOutputStream tarOutputStream = new TarArchiveOutputStream(outputStream)) {
            tarOutputStream.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            addDirectoryEntry(tarOutputStream, "docs/");
            addFileEntry(tarOutputStream, "docs/hello.txt", "hello from tar");
            addFileEntry(tarOutputStream, "root.txt", "root-value");
            tarOutputStream.finish();
        }
    }

    private static OutputStream openArchiveOutputStream(Path archive, boolean gzip) throws IOException {
        OutputStream outputStream = Files.newOutputStream(archive);
        return gzip ? new GzipCompressorOutputStream(outputStream) : outputStream;
    }

    private static void createSevenZipArchive(Path archive) throws IOException {
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
