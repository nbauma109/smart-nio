package io.github.nbauma109.smartnio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SmartArchiveFileSystemProviderTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "sample.tar",
            "sample.tar.gz",
            "sample.tar.bz2",
            "sample.tar.xz",
            "sample.7z"
    })
    void readsFixtureArchivesThroughFilesApi(String archiveName) throws Exception {
        try (var fileSystem = ArchiveTestFixtures.openFileSystem(archiveName)) {
            Path rootFile = fileSystem.getPath("/root.txt");
            Path nestedFile = fileSystem.getPath("/docs/hello.txt");
            Path docsDirectory = fileSystem.getPath("/docs");
            Path deepFile = fileSystem.getPath("/docs/nested/deep.txt");

            assertEquals("root-value", Files.readString(rootFile));
            assertEquals("hello from archive", Files.readString(nestedFile));
            assertEquals("deep-value", Files.readString(deepFile));
            assertTrue(Files.isDirectory(docsDirectory));

            try (Stream<Path> children = Files.list(fileSystem.getPath("/"))) {
                List<String> names = children.map(path -> path.getFileName().toString()).sorted().toList();
                assertEquals(List.of("docs", "root.txt", "space name.txt"), names);
            }

            BasicFileAttributes attributes = Files.readAttributes(nestedFile, BasicFileAttributes.class);
            assertTrue(attributes.isRegularFile());
            assertEquals("hello from archive".getBytes(StandardCharsets.UTF_8).length, attributes.size());
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
            "sample.tar",
            "sample.tar.gz",
            "sample.tar.bz2",
            "sample.tar.xz",
            "sample.7z"
    })
    void resolvesFixtureEntriesByUri(String archiveName) throws Exception {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        String content = Files.readString(provider.getPath(ArchiveTestFixtures.entryUri(archiveName, "/docs/hello.txt")));

        assertEquals("hello from archive", content);
    }
}
