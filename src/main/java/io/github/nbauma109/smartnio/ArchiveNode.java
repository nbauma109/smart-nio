package io.github.nbauma109.smartnio;

import java.nio.file.attribute.FileTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Indexed metadata node representing a directory or regular file inside a mounted archive.
 * <p>
 * Nodes form an in-memory tree used for path resolution. They intentionally store metadata only: names, timestamps,
 * sizes, child relationships, and format-specific lookup hints such as the {@code 7z} entry index.
 * </p>
 */
final class ArchiveNode {

    private final String name;
    private final ArchiveNode parent;
    private final Map<String, ArchiveNode> children;
    private final boolean directory;
    private final String entryName;
    private int archiveEntryIndex;
    private long size;
    private FileTime lastModifiedTime;

    /**
     * Creates a new archive node.
     *
     * @param name node name relative to its parent
     * @param parent parent node, or {@code null} for the root
     * @param directory whether the node represents a directory
     */
    private ArchiveNode(String name, ArchiveNode parent, boolean directory) {
        this.name = name;
        this.parent = parent;
        this.directory = directory;
        this.children = directory ? new LinkedHashMap<>() : Collections.emptyMap();
        this.entryName = buildEntryName(name, parent);
        this.archiveEntryIndex = -1;
        this.size = 0L;
        this.lastModifiedTime = FileTime.fromMillis(0L);
    }

    /**
     * Creates the root node of an indexed archive tree.
     *
     * @return root archive node
     */
    static ArchiveNode root() {
        return new ArchiveNode("", null, true);
    }

    /**
     * Resolves or creates a child directory node.
     *
     * @param childName child directory name
     * @return existing or newly-created directory node
     */
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

    /**
     * Creates or replaces a regular-file child node.
     *
     * @param childName child file name
     * @param size declared file size in bytes
     * @param modifiedTime last-modified time
     * @param archiveEntryIndex format-specific entry index, or {@code -1} when not applicable
     * @return created file node
     */
    ArchiveNode putFile(String childName, long size, FileTime modifiedTime, int archiveEntryIndex) {
        ArchiveNode fileNode = new ArchiveNode(childName, this, false);
        fileNode.archiveEntryIndex = archiveEntryIndex;
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

    /**
     * Returns the format-specific entry index used for indexed {@code 7z} reopen logic.
     *
     * @return archive entry index, or {@code -1} when the format has no such index
     */
    int archiveEntryIndex() {
        return archiveEntryIndex;
    }

    /**
     * Updates a directory node with its last-modified timestamp.
     *
     * @param modifiedTime directory modification time
     */
    void markDirectory(FileTime modifiedTime) {
        this.lastModifiedTime = modifiedTime;
    }

    /**
     * Returns the immediate child nodes in insertion order.
     *
     * @return child nodes
     */
    Collection<ArchiveNode> children() {
        return children.values();
    }

    /**
     * Returns a named child node, if present.
     *
     * @param childName child name
     * @return matching child or {@code null}
     */
    ArchiveNode child(String childName) {
        return children.get(childName);
    }

    /**
     * Returns the absolute archive path of this node.
     *
     * @return absolute path beginning with {@code /}
     */
    String absolutePath() {
        if (parent == null) {
            return "/";
        }
        if (parent.parent == null) {
            return "/" + name;
        }
        return parent.absolutePath() + "/" + name;
    }

    /**
     * Builds the normalized entry name used to reopen this entry from the source archive.
     *
     * @param name node name
     * @param parent parent node
     * @return archive entry name relative to the archive root
     */
    private static String buildEntryName(String name, ArchiveNode parent) {
        Objects.requireNonNull(name, "name");
        if (parent == null || parent.entryName.isEmpty()) {
            return name;
        }
        return parent.entryName + "/" + name;
    }
}
