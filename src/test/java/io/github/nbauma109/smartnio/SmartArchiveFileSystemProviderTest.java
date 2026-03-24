package io.github.nbauma109.smartnio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmartArchiveFileSystemProviderTest {

    @TempDir
    Path tempDir;

    @Test
    void readsTarEntriesThroughFilesApi() throws Exception {
        Path archive = tempDir.resolve("sample.tar");
        ArchiveTestFixtures.createTarArchive(archive, false);

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
                assertEquals(List.of("docs", "root.txt", "space name.txt"), names);
            }

            BasicFileAttributes attributes = Files.readAttributes(nestedFile, BasicFileAttributes.class);
            assertTrue(attributes.isRegularFile());
            assertEquals("hello from tar".getBytes(StandardCharsets.UTF_8).length, attributes.size());
        }
    }

    @Test
    void resolvesCompressedTarEntriesByUri() throws Exception {
        Path archive = tempDir.resolve("sample.tar.gz");
        ArchiveTestFixtures.createTarArchive(archive, true);

        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        URI uri = URI.create("smartnio:" + archive.toUri() + "!/docs/hello.txt");

        String content = Files.readString(provider.getPath(uri));

        assertEquals("hello from tar", content);
    }

    @Test
    void readsSevenZipEntries() throws Exception {
        Path archive = tempDir.resolve("sample.7z");
        ArchiveTestFixtures.createSevenZipArchive(archive);

        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        try (var fileSystem = provider.newFileSystem(archive, Map.of())) {
            Path dataFile = fileSystem.getPath("/folder/data.txt");
            BasicFileAttributes attributes = Files.readAttributes(fileSystem.getPath("/folder"), BasicFileAttributes.class);

            assertEquals("seven-zip", Files.readString(dataFile));
            assertTrue(attributes.isDirectory());
            assertFalse(attributes.isRegularFile());
        }
    }
}
