package io.github.nbauma109.smartnio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.attribute.BasicFileAttributeView;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class SmartArchivePathAndFileSystemTest {

    @Test
    void pathOperationsNormalizeResolveAndRelativize() throws Exception {
        try (SmartArchiveFileSystem fileSystem = ArchiveTestFixtures.openFileSystem("sample.tar")) {
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
        try (SmartArchiveFileSystem first = ArchiveTestFixtures.openFileSystem("sample.tar");
             SmartArchiveFileSystem second = ArchiveTestFixtures.openFileSystem("sample.tar.gz")) {
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
        try (SmartArchiveFileSystem fileSystem = ArchiveTestFixtures.openFileSystem("sample.tar")) {
            assertEquals("smartnio", fileSystem.provider().getScheme());
            assertTrue(fileSystem.isOpen());
            assertTrue(fileSystem.isReadOnly());
            assertEquals("/", fileSystem.getSeparator());
            assertEquals("/", fileSystem.getRootDirectories().iterator().next().toString());
            assertEquals("basic", fileSystem.supportedFileAttributeViews().iterator().next());
            assertEquals("sample.tar", fileSystem.getFileStores().iterator().next().name());
            assertEquals("/docs/hello.txt", fileSystem.getPath("/docs", "hello.txt").toString());
            assertEquals("docs/hello.txt", fileSystem.getPath("docs", "hello.txt").toString());

            PathMatcher regex = fileSystem.getPathMatcher("regex:.*/hello\\.txt");
            PathMatcher glob = fileSystem.getPathMatcher("glob:/docs/{hello.txt,deep.txt}");
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
    void fileStoreAndAttributeViewExposeReadOnlyMetadata() throws Exception {
        try (SmartArchiveFileSystem fileSystem = ArchiveTestFixtures.openFileSystem("sample.tar")) {
            SmartArchivePath docs = (SmartArchivePath) fileSystem.getPath("/docs");
            SmartArchivePath hello = (SmartArchivePath) fileSystem.getPath("/docs/hello.txt");

            ArchiveNode docsNode = fileSystem.lookup(docs);
            ArchiveNode helloNode = fileSystem.lookup(hello);

            assertTrue(docsNode.isDirectory());
            assertTrue(helloNode.isRegularFile());

            ArchiveFileAttributes attributes = new ArchiveFileAttributes(helloNode);
            assertEquals(helloNode.lastModifiedTime(), attributes.lastModifiedTime());
            assertEquals(helloNode.lastModifiedTime(), attributes.lastAccessTime());
            assertEquals(helloNode.lastModifiedTime(), attributes.creationTime());
            assertTrue(attributes.isRegularFile());
            assertFalse(attributes.isDirectory());
            assertFalse(attributes.isSymbolicLink());
            assertFalse(attributes.isOther());
            assertEquals(helloNode.size(), attributes.size());
            assertEquals("/docs/hello.txt", attributes.fileKey());

            SmartArchiveFileAttributeView attributeView = new SmartArchiveFileAttributeView(helloNode);
            assertEquals("basic", attributeView.name());
            assertInstanceOf(ArchiveFileAttributes.class, attributeView.readAttributes());
            assertThrows(java.nio.file.ReadOnlyFileSystemException.class,
                    () -> attributeView.setTimes(null, null, null));

            SmartArchiveFileStore fileStore = fileSystem.fileStore();
            assertEquals("sample.tar", fileStore.name());
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
}
