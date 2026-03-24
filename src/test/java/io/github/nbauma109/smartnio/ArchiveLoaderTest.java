package io.github.nbauma109.smartnio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ArchiveLoaderTest {

    @Test
    void archiveNodesKeepMetadataOnlyAndStreamFileContentOnDemand() throws Exception {
        assertFalse(Arrays.stream(ArchiveNode.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(byte[].class::equals));

        Path archive = ArchiveTestFixtures.archivePath("sample.tar.gz");
        ArchiveNode root = ArchiveLoader.load(archive);
        ArchiveNode docs = root.child("docs");
        ArchiveNode hello = docs.child("hello.txt");

        assertNotNull(docs);
        assertNotNull(hello);
        assertEquals("docs/hello.txt", hello.entryName());
        assertEquals("hello from archive".getBytes(StandardCharsets.UTF_8).length, hello.size());

        try (var inputStream = ArchiveLoader.openInputStream(archive, hello)) {
            assertEquals("hello from archive", new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
