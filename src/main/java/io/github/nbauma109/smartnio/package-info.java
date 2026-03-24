/**
 * Read-only NIO filesystem support for archive formats that are not handled by the JDK's built-in ZIP filesystem
 * provider.
 * <p>
 * The package exposes {@link io.github.nbauma109.smartnio.SmartArchiveFileSystemProvider} as the public entry point.
 * Archives are mounted as {@link java.nio.file.FileSystem} instances whose paths can be read through the regular
 * {@link java.nio.file.Files} API.
 * </p>
 * <p>
 * Mounting builds an in-memory directory and metadata index for the archive. File contents remain lazy:
 * tar-based archives reopen and scan to the requested entry when needed, while {@code 7z} archives reuse structured
 * archive metadata to reopen the requested indexed entry on demand.
 * </p>
 */
package io.github.nbauma109.smartnio;
