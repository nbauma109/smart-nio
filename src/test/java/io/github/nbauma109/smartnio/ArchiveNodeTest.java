package io.github.nbauma109.smartnio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ArchiveNodeTest {

    @Test
    void nodesReuseDirectoriesAndExposeMetadata() {
        FileTime modifiedTime = FileTime.fromMillis(42L);
        ArchiveNode root = ArchiveNode.root();
        ArchiveNode docs = root.ensureDirectory("docs");
        ArchiveNode sameDocs = root.ensureDirectory("docs");
        ArchiveNode hello = docs.putFile("hello.txt", -7L, modifiedTime, 3);

        assertSame(docs, sameDocs);
        assertEquals(0L, hello.size());
        assertEquals(modifiedTime, hello.lastModifiedTime());
        assertEquals("docs/hello.txt", hello.entryName());
        assertEquals("/docs/hello.txt", hello.absolutePath());
        assertEquals(3, hello.archiveEntryIndex());
        assertEquals(List.of(hello), new ArrayList<>(docs.children()));
    }

    @Test
    void nodesRejectDirectoryFileCollisions() {
        ArchiveNode root = ArchiveNode.root();
        root.putFile("docs", 1L, FileTime.fromMillis(0L), -1);

        assertThrows(IllegalStateException.class, () -> root.ensureDirectory("docs"));
    }
}
