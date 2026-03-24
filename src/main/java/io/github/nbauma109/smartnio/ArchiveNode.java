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
    private byte[] data;
    private FileTime lastModifiedTime;

    private ArchiveNode(String name, ArchiveNode parent, boolean directory) {
        this.name = name;
        this.parent = parent;
        this.directory = directory;
        this.children = directory ? new LinkedHashMap<>() : Collections.emptyMap();
        this.data = new byte[0];
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

    ArchiveNode putFile(String childName, byte[] content, FileTime modifiedTime) {
        Objects.requireNonNull(content, "content");
        ArchiveNode fileNode = new ArchiveNode(childName, this, false);
        fileNode.data = content.clone();
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

    byte[] data() {
        return data.clone();
    }

    long size() {
        return directory ? 0L : data.length;
    }

    FileTime lastModifiedTime() {
        return lastModifiedTime;
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
}
