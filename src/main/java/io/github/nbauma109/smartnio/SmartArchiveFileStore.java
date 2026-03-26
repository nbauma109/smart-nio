package io.github.nbauma109.smartnio;

import java.nio.file.FileStore;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

/**
 * Synthetic {@link FileStore} describing a mounted archive filesystem.
 * <p>
 * The provider currently exposes only a minimal read-only file store view and does not attempt to report host storage
 * capacity or archive-specific quotas.
 * </p>
 */
final class SmartArchiveFileStore extends FileStore {

    private final String name;

    /**
     * Creates a file store view for a mounted archive.
     *
     * @param name display name, typically the archive file name
     */
    SmartArchiveFileStore(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public String type() {
        return "smart-archive";
    }

    @Override
    public boolean isReadOnly() {
        return true;
    }

    @Override
    public long getTotalSpace() {
        return 0L;
    }

    @Override
    public long getUsableSpace() {
        return 0L;
    }

    @Override
    public long getUnallocatedSpace() {
        return 0L;
    }

    @Override
    public boolean supportsFileAttributeView(Class<? extends FileAttributeView> type) {
        return type == java.nio.file.attribute.BasicFileAttributeView.class
                || type == SmartArchiveFileAttributeView.class;
    }

    @Override
    public boolean supportsFileAttributeView(String name) {
        return "basic".equalsIgnoreCase(name);
    }

    @Override
    public <V extends FileStoreAttributeView> V getFileStoreAttributeView(Class<V> type) {
        return null;
    }

    @Override
    public Object getAttribute(String attribute) {
        throw new UnsupportedOperationException("File store attributes are not supported");
    }
}
