package io.github.nbauma109.smartnio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Arrays;

import org.junit.jupiter.api.Test;

class ArchiveLoaderTest {

    @Test
    void archiveNodesKeepMetadataOnlyAndTrackSevenZipEntryIndexes() throws Exception {
        assertFalse(Arrays.stream(ArchiveNode.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(byte[].class::equals));

        Path tarArchive = ArchiveTestFixtures.archivePath("sample.tar.gz");
        ArchiveNode tarRoot = ArchiveLoader.load(tarArchive);
        ArchiveNode tarHello = tarRoot.child("docs").child("hello.txt");

        assertNotNull(tarHello);
        assertEquals("docs/hello.txt", tarHello.entryName());
        assertEquals("hello from archive".getBytes(StandardCharsets.UTF_8).length, tarHello.size());
        assertEquals(-1, tarHello.archiveEntryIndex());

        Path sevenZipArchive = ArchiveTestFixtures.archivePath("sample.7z");
        ArchiveNode sevenZipRoot = ArchiveLoader.load(sevenZipArchive);
        ArchiveNode sevenZipHello = sevenZipRoot.child("docs").child("hello.txt");

        assertNotNull(sevenZipHello);
        assertTrue(sevenZipHello.archiveEntryIndex() >= 0);

        try (var inputStream = ArchiveLoader.openInputStream(sevenZipArchive, sevenZipHello)) {
            assertEquals('h', inputStream.read());
            assertEquals("ello from archive", new String(inputStream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void tarStreamsAreBoundedAndDirectoriesCannotBeOpened() throws Exception {
        Path archive = ArchiveTestFixtures.archivePath("sample.tar.gz");
        ArchiveNode root = ArchiveLoader.load(archive);
        ArchiveNode docs = root.child("docs");
        ArchiveNode hello = docs.child("hello.txt");

        try (var inputStream = ArchiveLoader.openInputStream(archive, hello)) {
            byte[] buffer = new byte[64];
            int read = inputStream.read(buffer, 0, buffer.length);

            assertEquals("hello from archive".length(), read);
            assertEquals("hello from archive", new String(buffer, 0, read, StandardCharsets.UTF_8));
            assertEquals(-1, inputStream.read(buffer, 0, buffer.length));
            assertEquals(-1, inputStream.read());
        }

        assertThrows(Exception.class, () -> ArchiveLoader.openInputStream(archive, docs));
    }

    @Test
    void sevenZipLookupsUseStoredEntryIndexAndRejectUnknownIndexes() throws Exception {
        Path archive = ArchiveTestFixtures.archivePath("sample.7z");
        ArchiveNode root = ArchiveLoader.load(archive);
        ArchiveNode hello = root.child("docs").child("hello.txt");

        var inputStream = ArchiveLoader.openInputStream(archive, hello);
        assertEquals('h', inputStream.read());
        inputStream.close();
        inputStream.close();

        ArchiveNode syntheticRoot = ArchiveNode.root();
        ArchiveNode missing = syntheticRoot.putFile("missing.txt", 1L, FileTime.fromMillis(0L), 999);
        assertThrows(NoSuchFileException.class, () -> ArchiveLoader.openInputStream(archive, missing));
    }
}
