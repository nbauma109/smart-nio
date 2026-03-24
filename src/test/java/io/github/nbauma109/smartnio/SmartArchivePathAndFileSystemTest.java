package io.github.nbauma109.smartnio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SmartArchivePathAndFileSystemTest {

    @TempDir
    Path tempDir;

    @Test
    void pathOperationsNormalizeResolveAndRelativize() throws Exception {
        try (SmartArchiveFileSystem fileSystem = ArchiveTestFixtures.createFileSystem(tempDir.resolve("fixture.tar"))) {
            SmartArchivePath absolute = (SmartArchivePath) fileSystem.getPath("/docs/./nested/../hello.txt");
            SmartArchivePath relative = (SmartArchivePath) fileSystem.getPath("docs\\nested\\..\\hello.txt");

            assertTrue(absolute.isAbsolute());
            assertEquals("/", absolute.getRoot().toString());
            assertEquals("hello.txt", absolute.getFileName().toString());
            assertEquals("/docs", absolute.getParent().toString());
            assertEquals(2, absolute.getNameCount());
            assertEquals("docs", absolute.getName(0).toString());
            assertEquals("docs/hello.txt", absolute.subpath(0, 2).toString());
            assertEquals("/docs/hello.txt", absolute.normalize().toString());
            assertEquals("/docs/hello.txt", relative.toAbsolutePath().toString());
            assertEquals("/docs/hello.txt", relative.toRealPath().toString());
            assertEquals("/docs/hello.txt", absolute.absolutePath());
            assertEquals(List.of("docs", "hello.txt"), absolute.segments());
            assertTrue(absolute.startsWith("/docs"));
            assertTrue(absolute.endsWith("hello.txt"));
            assertEquals("hello.txt", fileSystem.getPath("/docs").relativize(absolute).toString());
            assertEquals("/docs/other.txt", absolute.resolveSibling("other.txt").toString());
            assertEquals("/docs/hello.txt", fileSystem.getPath("/").resolve(relative).toString());
            assertEquals("smartnio", absolute.toUri().getScheme());
            assertTrue(fileSystem.getPath("/space name.txt").toUri().toString().contains("/space%20name.txt"));

            List<String> iterated = new ArrayList<>();
            absolute.iterator().forEachRemaining(path -> iterated.add(path.toString()));
            assertIterableEquals(List.of("docs", "hello.txt"), iterated);
            assertEquals(0, absolute.compareTo(fileSystem.getPath("/docs/hello.txt")));
            assertEquals(absolute, fileSystem.getPath("/docs/hello.txt"));
            assertEquals(absolute.hashCode(), fileSystem.getPath("/docs/hello.txt").hashCode());
            assertNotEquals(absolute, fileSystem.getPath("/docs/nested/deep.txt"));
            assertNull(fileSystem.getPath("").getFileName());
            assertNull(fileSystem.getPath("/").getParent());
            assertEquals("/", fileSystem.getPath("").toAbsolutePath().toString());
        }
    }

    @Test
    void pathOperationsRejectInvalidInputsAndForeignFileSystems() throws Exception {
        try (SmartArchiveFileSystem first = ArchiveTestFixtures.createFileSystem(tempDir.resolve("first.tar"));
             SmartArchiveFileSystem second = ArchiveTestFixtures.createFileSystem(tempDir.resolve("second.tar"))) {
            SmartArchivePath path = (SmartArchivePath) first.getPath("/docs/hello.txt");

            assertThrows(IllegalArgumentException.class, () -> path.getName(-1));
            assertThrows(IllegalArgumentException.class, () -> path.getName(2));
            assertThrows(IllegalArgumentException.class, () -> path.subpath(1, 1));
            assertThrows(IllegalArgumentException.class, () -> path.relativize(first.getPath("docs/hello.txt")));
            assertThrows(IllegalArgumentException.class, () -> path.startsWith(second.getPath("/docs")));
            assertThrows(IllegalArgumentException.class, () -> path.compareTo(second.getPath("/docs/hello.txt")));
            assertThrows(UnsupportedOperationException.class, path::toFile);
            assertThrows(UnsupportedOperationException.class,
                    () -> path.register(null, StandardWatchEventKinds.ENTRY_CREATE));
            assertThrows(UnsupportedOperationException.class,
                    () -> path.register(null, new java.nio.file.WatchEvent.Kind<?>[]{StandardWatchEventKinds.ENTRY_CREATE}));
            assertThrows(InvalidPathException.class, () -> first.getPath("bad" + '\0' + "path"));
        }
    }

    @Test
    void fileSystemExposesMatchersAndMetadataAndCloseBehavior() throws Exception {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        Path archivePath = tempDir.resolve("fixture.tar");
        try (SmartArchiveFileSystem fileSystem = ArchiveTestFixtures.createFileSystem(archivePath)) {
            assertSame(provider.getScheme(), fileSystem.provider().getScheme());
            assertTrue(fileSystem.isOpen());
            assertTrue(fileSystem.isReadOnly());
            assertEquals("/", fileSystem.getSeparator());
            assertEquals("/", fileSystem.getRootDirectories().iterator().next().toString());
            assertEquals("basic", fileSystem.supportedFileAttributeViews().iterator().next());
            assertEquals("fixture.tar", fileSystem.getFileStores().iterator().next().name());
            assertEquals("/docs/hello.txt", fileSystem.getPath("/docs", "hello.txt").toString());
            assertEquals("docs/hello.txt", fileSystem.getPath("docs", "hello.txt").toString());

            PathMatcher regex = fileSystem.getPathMatcher("regex:.*/hello\\.txt");
            PathMatcher glob = fileSystem.getPathMatcher("glob:/docs/{hello.txt,notes (final).txt}");
            assertTrue(regex.matches(fileSystem.getPath("/docs/hello.txt")));
            assertTrue(glob.matches(fileSystem.getPath("/docs/hello.txt")));
            assertTrue(fileSystem.getPathMatcher("glob:/docs/*.txt").matches(fileSystem.getPath("/docs/hello.txt")));
            assertThrows(IllegalArgumentException.class, () -> fileSystem.getPathMatcher("glob"));
            assertThrows(UnsupportedOperationException.class, () -> fileSystem.getPathMatcher("syntax:/docs/*.txt"));
            assertThrows(UnsupportedOperationException.class, fileSystem::getUserPrincipalLookupService);
            assertThrows(UnsupportedOperationException.class, fileSystem::newWatchService);

            assertInstanceOf(ArchiveNode.class, fileSystem.lookup((SmartArchivePath) fileSystem.getPath("/docs")));
            fileSystem.close();
            fileSystem.close();
            assertFalse(fileSystem.isOpen());
            assertThrows(IllegalStateException.class, () -> fileSystem.lookup((SmartArchivePath) fileSystem.getPath("/docs")));
        }
    }

    @Test
    void archiveNodeFileStoreAndAttributeViewExposeReadOnlyMetadata() {
        ArchiveNode root = ArchiveNode.root();
        ArchiveNode docs = root.ensureDirectory("docs");
        byte[] original = "payload".getBytes();
        ArchiveNode file = docs.putFile("file.txt", original, FileTime.fromMillis(123L));
        original[0] = 'X';

        assertTrue(root.isDirectory());
        assertTrue(file.isRegularFile());
        assertSame(docs, root.ensureDirectory("docs"));
        assertEquals("/docs/file.txt", file.absolutePath());
        assertEquals(1, root.children().size());
        assertEquals(docs, root.child("docs"));
        assertEquals("payload", new String(file.data()));
        byte[] copy = file.data();
        copy[0] = 'Y';
        assertEquals("payload", new String(file.data()));
        docs.markDirectory(FileTime.fromMillis(222L));
        assertEquals(FileTime.fromMillis(222L), docs.lastModifiedTime());
        assertThrows(IllegalStateException.class, () -> {
            root.putFile("docs", "x".getBytes(), FileTime.fromMillis(1L));
            root.ensureDirectory("docs");
        });

        ArchiveFileAttributes attributes = new ArchiveFileAttributes(file);
        assertEquals(file.lastModifiedTime(), attributes.lastModifiedTime());
        assertEquals(file.lastModifiedTime(), attributes.lastAccessTime());
        assertEquals(file.lastModifiedTime(), attributes.creationTime());
        assertTrue(attributes.isRegularFile());
        assertFalse(attributes.isDirectory());
        assertFalse(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
        assertEquals(file.size(), attributes.size());
        assertEquals("/docs/file.txt", attributes.fileKey());

        SmartArchiveFileAttributeView attributeView = new SmartArchiveFileAttributeView(file);
        assertEquals("basic", attributeView.name());
        assertInstanceOf(ArchiveFileAttributes.class, attributeView.readAttributes());
        assertThrows(java.nio.file.ReadOnlyFileSystemException.class,
                () -> attributeView.setTimes(FileTime.fromMillis(1L), null, null));

        SmartArchiveFileStore fileStore = new SmartArchiveFileStore("fixture.tar");
        assertEquals("fixture.tar", fileStore.name());
        assertEquals("smart-archive", fileStore.type());
        assertTrue(fileStore.isReadOnly());
        assertEquals(0L, fileStore.getTotalSpace());
        assertEquals(0L, fileStore.getUsableSpace());
        assertEquals(0L, fileStore.getUnallocatedSpace());
        assertTrue(fileStore.supportsFileAttributeView(BasicFileAttributeView.class));
        assertFalse(fileStore.supportsFileAttributeView((Class<? extends java.nio.file.attribute.FileAttributeView>) null));
        assertTrue(fileStore.supportsFileAttributeView("basic"));
        assertFalse(fileStore.supportsFileAttributeView("posix"));
        assertNull(fileStore.getFileStoreAttributeView(null));
        assertThrows(UnsupportedOperationException.class, () -> fileStore.getAttribute("size"));
    }
}
