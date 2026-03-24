package io.github.nbauma109.smartnio;

import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

final class ArchiveNode {

    private final String name;
    private final ArchiveNode parent;
    private final Map<String, ArchiveNode> children;
    private final boolean directory;
    private final String entryName;
    private long size;
    private FileTime lastModifiedTime;

    private ArchiveNode(String name, ArchiveNode parent, boolean directory) {
        this.name = name;
        this.parent = parent;
        this.directory = directory;
        this.children = directory ? new LinkedHashMap<>() : Collections.emptyMap();
        this.entryName = buildEntryName(name, parent);
        this.size = 0L;
        this.lastModifiedTime = FileTime.fromMillis(0L);
    }

    static ArchiveNode root() {
        return new ArchiveNode("", null, true);
    }

    ArchiveNode ensureDirectory(String childName) {
        ArchiveNode existing = children.get(childName);
        if (existing != null) {
            if (!existing.directory) {
                throw new IllegalStateException("Archive entry collides with file: " + existing.absolutePath());
            }
            return existing;
        }
        ArchiveNode directoryNode = new ArchiveNode(childName, this, true);
        children.put(childName, directoryNode);
        return directoryNode;
    }

    ArchiveNode putFile(String childName, long size, FileTime modifiedTime) {
        ArchiveNode fileNode = new ArchiveNode(childName, this, false);
        fileNode.size = Math.max(0L, size);
        fileNode.lastModifiedTime = modifiedTime;
        children.put(childName, fileNode);
        return fileNode;
    }

    boolean isDirectory() {
        return directory;
    }

    boolean isRegularFile() {
        return !directory;
    }

    String name() {
        return name;
    }

    long size() {
        return directory ? 0L : size;
    }

    FileTime lastModifiedTime() {
        return lastModifiedTime;
    }

    String entryName() {
        return entryName;
    }

    void markDirectory(FileTime modifiedTime) {
        this.lastModifiedTime = modifiedTime;
    }

    Collection<ArchiveNode> children() {
        return children.values();
    }

    ArchiveNode child(String childName) {
        return children.get(childName);
    }

    String absolutePath() {
        if (parent == null) {
            return "/";
        }
        if (parent.parent == null) {
            return "/" + name;
        }
        return parent.absolutePath() + "/" + name;
    }

    private static String buildEntryName(String name, ArchiveNode parent) {
        Objects.requireNonNull(name, "name");
        if (parent == null || parent.entryName.isEmpty()) {
            return name;
        }
        return parent.entryName + "/" + name;
    }
}
