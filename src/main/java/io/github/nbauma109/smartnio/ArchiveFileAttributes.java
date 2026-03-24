package io.github.nbauma109.smartnio;

import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;

final class ArchiveFileAttributes implements BasicFileAttributes {

    private final ArchiveNode node;

    ArchiveFileAttributes(ArchiveNode node) {
        this.node = node;
    }

    @Override
    public FileTime lastModifiedTime() {
        return node.lastModifiedTime();
    }

    @Override
    public FileTime lastAccessTime() {
        return node.lastModifiedTime();
    }

    @Override
    public FileTime creationTime() {
        return node.lastModifiedTime();
    }

    @Override
    public boolean isRegularFile() {
        return node.isRegularFile();
    }

    @Override
    public boolean isDirectory() {
        return node.isDirectory();
    }

    @Override
    public boolean isSymbolicLink() {
        return false;
    }

    @Override
    public boolean isOther() {
        return false;
    }

    @Override
    public long size() {
        return node.size();
    }

    @Override
    public Object fileKey() {
        return node.absolutePath();
    }
}
