package io.github.nbauma109.smartnio;

import java.nio.file.FileStore;
import java.nio.file.attribute.FileAttributeView;
import java.nio.file.attribute.FileStoreAttributeView;

final class SmartArchiveFileStore extends FileStore {

    private final String name;

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
        return type != null && "BasicFileAttributeView".equals(type.getSimpleName());
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
