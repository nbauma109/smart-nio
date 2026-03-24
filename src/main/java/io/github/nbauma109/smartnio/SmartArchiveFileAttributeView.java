package io.github.nbauma109.smartnio;

import java.nio.file.ReadOnlyFileSystemException;
import java.nio.file.attribute.BasicFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

final class SmartArchiveFileAttributeView implements BasicFileAttributeView {

    private final ArchiveNode node;

    SmartArchiveFileAttributeView(ArchiveNode node) {
        this.node = node;
    }

    @Override
    public String name() {
        return "basic";
    }

    @Override
    public BasicFileAttributes readAttributes() {
        return new ArchiveFileAttributes(node);
    }

    @Override
    public void setTimes(FileTime lastModifiedTime, FileTime lastAccessTime, FileTime createTime) {
        throw new ReadOnlyFileSystemException();
    }
}
