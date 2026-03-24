package io.github.nbauma109.smartnio;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

final class ArchiveTestFixtures {

    private static final String BASE_RESOURCE_PATH = "archive-fixtures/";

    private ArchiveTestFixtures() {
    }

    static Path archivePath(String archiveName) {
        try {
            URI resourceUri = Objects.requireNonNull(
                    ArchiveTestFixtures.class.getClassLoader().getResource(BASE_RESOURCE_PATH + archiveName),
                    () -> "Missing archive fixture: " + archiveName
            ).toURI();
            return Path.of(resourceUri);
        } catch (URISyntaxException exception) {
            throw new IllegalStateException("Invalid fixture URI for " + archiveName, exception);
        }
    }

    static SmartArchiveFileSystem openFileSystem(String archiveName) throws IOException {
        return (SmartArchiveFileSystem) new SmartArchiveFileSystemProvider().newFileSystem(archivePath(archiveName), Map.of());
    }

    static URI entryUri(String archiveName, String entry) {
        return URI.create("smartnio:" + archivePath(archiveName).toUri() + "!" + entry);
    }
}
