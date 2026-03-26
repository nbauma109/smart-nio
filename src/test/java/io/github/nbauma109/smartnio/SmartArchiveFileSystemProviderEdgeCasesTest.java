package io.github.nbauma109.smartnio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.FileSystemAlreadyExistsException;
import java.nio.file.FileSystemNotFoundException;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class SmartArchiveFileSystemProviderEdgeCasesTest {

    @TempDir
    Path tempDir;

    @Test
    void providerRejectsMissingArchivesAndDuplicateMounts() throws Exception {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        Path missing = tempDir.resolve("missing.tar");
        Path archive = ArchiveTestFixtures.archivePath("sample.tar");
        URI helloUri = ArchiveTestFixtures.entryUri("sample.tar", "/docs/hello.txt");
        Map<String, Object> emptyEnv = Map.of();

        assertThrows(NoSuchFileException.class, () -> provider.newFileSystem(missing, emptyEnv));

        try (var fileSystem = provider.newFileSystem(archive, emptyEnv)) {
            assertThrows(FileSystemAlreadyExistsException.class, () -> provider.newFileSystem(archive, emptyEnv));
            assertEquals(fileSystem, provider.getFileSystem(helloUri));
        }
    }

    @Test
    void providerResolvesUrisAndReportsMissingMounts() {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        Path archive = ArchiveTestFixtures.archivePath("sample.tar");
        URI helloUri = ArchiveTestFixtures.entryUri("sample.tar", "/docs/hello.txt");
        URI archiveUri = URI.create("smartnio:" + archive.toUri());
        URI wrongSchemeUri = URI.create("wrong:" + archive.toUri());
        URI absentArchiveUri = uriFor(tempDir.resolve("absent.tar"), "/docs/hello.txt");

        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(helloUri));
        assertEquals("/", provider.getPath(archiveUri).toString());
        assertEquals("/docs/hello.txt", provider.getPath(helloUri).toString());
        assertThrows(IllegalArgumentException.class, () -> provider.getPath(wrongSchemeUri));
        assertThrows(IllegalArgumentException.class, () -> provider.getPath(absentArchiveUri));
    }

    @Test
    void providerListsDirectoriesAndWrapsFilterFailures() throws Exception {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        Path archive = ArchiveTestFixtures.archivePath("sample.tar");

        try (var fileSystem = provider.newFileSystem(archive, Map.of())) {
            Path root = fileSystem.getPath("/");
            Path hello = fileSystem.getPath("/docs/hello.txt");

            try (DirectoryStream<Path> stream = provider.newDirectoryStream(root, entry -> !entry.toString().endsWith("root.txt"))) {
                List<String> names = java.util.stream.StreamSupport.stream(stream.spliterator(), false)
                        .map(Path::toString)
                        .sorted()
                        .toList();
                assertEquals(List.of("/docs", "/space name.txt"), names);
            }

            try (DirectoryStream<Path> stream = provider.newDirectoryStream(root, entry -> {
                throw new IOException("boom");
            })) {
                var iterator = stream.iterator();
                assertThrows(DirectoryIteratorException.class, iterator::hasNext);
            }

            assertThrows(IOException.class, () -> provider.newDirectoryStream(hello, entry -> true));
        }
    }

    @Test
    void providerReadsStreamsAndChannels() throws Exception {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        Path archive = ArchiveTestFixtures.archivePath("sample.tar");

        try (var fileSystem = provider.newFileSystem(archive, Map.of())) {
            Path docs = fileSystem.getPath("/docs");
            Path hello = fileSystem.getPath("/docs/hello.txt");
            var readOptions = java.util.Set.of(StandardOpenOption.READ);
            var writeOptions = java.util.Set.of(StandardOpenOption.WRITE);

            assertEquals("hello from archive", new String(provider.newInputStream(hello).readAllBytes()));
            assertThrows(IOException.class, () -> provider.newInputStream(docs));

            ByteBuffer buffer = ByteBuffer.allocate(32);
            try (SeekableByteChannel channel = provider.newByteChannel(hello, readOptions)) {
                channel.read(buffer);
                assertTrue(channel.position() > 0);
            }
            assertEquals("hello from archive", new String(buffer.array(), 0, "hello from archive".length()));
            assertThrows(IOException.class, () -> provider.newByteChannel(docs, readOptions));
            assertThrows(ReadOnlyFileSystemException.class, () -> provider.newByteChannel(hello, writeOptions));
        }
    }

    @Test
    void providerExposesAttributesAndReadOnlyBehavior() throws Exception {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        Path archive = ArchiveTestFixtures.archivePath("sample.tar");

        try (var fileSystem = provider.newFileSystem(archive, Map.of())) {
            Path hello = fileSystem.getPath("/docs/hello.txt");
            Path rootFile = fileSystem.getPath("/root.txt");
            Path sameHello = fileSystem.getPath("/docs/./hello.txt");
            Path newDir = fileSystem.getPath("/newdir");

            assertTrue(provider.isSameFile(hello, sameHello));
            assertFalse(provider.isHidden(hello));
            assertEquals("sample.tar", provider.getFileStore(hello).name());
            provider.checkAccess(hello, AccessMode.READ);
            assertThrows(ReadOnlyFileSystemException.class, () -> provider.checkAccess(hello, AccessMode.WRITE));

            BasicFileAttributeView view = provider.getFileAttributeView(hello, BasicFileAttributeView.class);
            assertNotNull(view);
            assertNull(provider.getFileAttributeView(hello, FileOwnerAttributeView.class));
            assertNull(provider.getFileAttributeView(hello, PosixFileAttributeView.class));
            assertNull(provider.getFileAttributeView(hello, null));

            BasicFileAttributes attributes = provider.readAttributes(hello, BasicFileAttributes.class);
            assertTrue(attributes.isRegularFile());
            assertEquals("hello from archive".length(), attributes.size());
            assertThrows(UnsupportedOperationException.class, () -> provider.readAttributes(hello, PosixFileAttributes.class));

            Map<String, Object> subset = provider.readAttributes(hello, "basic:size,fileKey,lastModifiedTime");
            assertEquals((long) "hello from archive".length(), subset.get("size"));
            assertEquals("/docs/hello.txt", subset.get("fileKey"));
            assertTrue(subset.containsKey("lastModifiedTime"));
            assertEquals(7, provider.readAttributes(rootFile, "*").size());

            assertThrows(UnsupportedOperationException.class,
                    () -> provider.readAttributes(hello, (Class<BasicFileAttributes>) null));
            assertThrows(ReadOnlyFileSystemException.class, () -> provider.newOutputStream(hello));
            assertThrows(ReadOnlyFileSystemException.class, () -> provider.createDirectory(newDir));
            assertThrows(ReadOnlyFileSystemException.class, () -> provider.delete(hello));
            assertThrows(ReadOnlyFileSystemException.class, () -> provider.copy(hello, rootFile));
            assertThrows(ReadOnlyFileSystemException.class, () -> provider.move(hello, rootFile));
            assertThrows(ReadOnlyFileSystemException.class, () -> provider.setAttribute(hello, "basic:size", 1L));
            assertThrows(IllegalArgumentException.class, () -> provider.toSmartPath(tempDir));
        }
    }

    @Test
    void filesCopyToDefaultFileSystemDoesNotRequestUnsupportedPosixViewFromArchiveProvider() throws Exception {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        Path archive = ArchiveTestFixtures.archivePath("sample.tar");
        Path target = tempDir.resolve("hello-copy.txt");

        try (var fileSystem = provider.newFileSystem(archive, Map.of())) {
            Path hello = fileSystem.getPath("/docs/hello.txt");

            Files.copy(hello, target);

            assertEquals("hello from archive", Files.readString(target));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sample.tar", "sample.7z"})
    void readingEntriesDoesNotCloseMountedFileSystem(String archiveName) throws Exception {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        Path archive = ArchiveTestFixtures.archivePath(archiveName);

        try (SmartArchiveFileSystem fileSystem = (SmartArchiveFileSystem) provider.newFileSystem(archive, Map.of())) {
            Path hello = fileSystem.getPath("/docs/hello.txt");

            try (var inputStream = provider.newInputStream(hello)) {
                assertEquals("hello from archive", new String(inputStream.readAllBytes()));
            }
            assertTrue(fileSystem.isOpen());
            assertEquals("deep-value", Files.readString(fileSystem.getPath("/docs/nested/deep.txt")));

            try (SeekableByteChannel channel = provider.newByteChannel(hello, java.util.Set.of(StandardOpenOption.READ))) {
                assertTrue(channel.size() > 0L);
            }
            assertTrue(fileSystem.isOpen());
            assertEquals(fileSystem, provider.getFileSystem(ArchiveTestFixtures.entryUri(archiveName, "/docs/hello.txt")));
        }
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sample.tar", "sample.7z"})
    void uriReadsKeepAutoMountedFileSystemOpenUntilExplicitClose(String archiveName) throws Exception {
        SmartArchiveFileSystemProvider provider = new SmartArchiveFileSystemProvider();
        URI helloUri = ArchiveTestFixtures.entryUri(archiveName, "/docs/hello.txt");

        assertEquals("hello from archive", Files.readString(provider.getPath(helloUri)));

        SmartArchiveFileSystem fileSystem = (SmartArchiveFileSystem) provider.getFileSystem(helloUri);
        assertTrue(fileSystem.isOpen());
        assertEquals("root-value", Files.readString(fileSystem.getPath("/root.txt")));

        fileSystem.close();

        assertFalse(fileSystem.isOpen());
        assertThrows(FileSystemNotFoundException.class, () -> provider.getFileSystem(helloUri));
    }

    private static URI uriFor(Path archive, String entry) {
        return URI.create("smartnio:" + archive.toUri() + "!" + entry);
    }
}
