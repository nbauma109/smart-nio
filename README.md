## smart-nio

[![Maven Central](https://img.shields.io/maven-central/v/io.github.nbauma109/smart-nio.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/io.github.nbauma109/smart-nio)
[![codecov](https://codecov.io/gh/nbauma109/smart-nio/graph/badge.svg?branch=master)](https://codecov.io/gh/nbauma109/smart-nio)

An extension of the Java NIO API for filesystems backed by archives such as `tar`, `tar.gz`, `tar.bz2`, `tar.xz`, and `7z`.

## Why this project?

With the regular Java NIO API, only ZIP-based archives can be mounted as filesystems out of the box, for example `jar`, `war`, and `ear`.

This project extends that model to other archive families, starting with read-only support for tar-based archives and `7z`.

## Current status

The current implementation provides a read-only `FileSystemProvider`.

Like the JDK ZIP filesystem provider, mounting an archive builds an in-memory entry index up front while leaving file
contents lazy. For tar-based archives this index requires a linear pass over archive headers because tar does not provide
a ZIP-style central directory. For `7z`, `smart-nio` uses the format's structured header metadata to index entries and
reopen a specific entry on demand. File contents are reopened and streamed when you access a file through
`Files.newInputStream`, `Files.readString`, or `Files.newByteChannel`.

Supported archive families:

- `tar`
- compressed tar streams auto-detected by Apache Commons Compress, including `tar.gz`, `tar.bz2`, and `tar.xz`
- `7z`

## Usage

Mount an archive directly from a `Path`:

```java
var provider = new io.github.nbauma109.smartnio.SmartArchiveFileSystemProvider();

try (var fs = provider.newFileSystem(Path.of("sample.tar.gz"), Map.of())) {
    String content = Files.readString(fs.getPath("/docs/hello.txt"));
}
```

Resolve an entry through the provider URI format:

```java
var provider = new io.github.nbauma109.smartnio.SmartArchiveFileSystemProvider();
var uri = URI.create("smartnio:" + Path.of("sample.tar.gz").toUri() + "!/docs/hello.txt");

String content = Files.readString(provider.getPath(uri));
```

## Quality gates

Coverage is produced with JaCoCo during `mvn verify` and uploaded to Codecov from GitHub Actions.

Release automation is split across GitHub Actions:

- `ci.yml` runs tests, generates JaCoCo coverage, and uploads to Codecov
- `release.yml` triggers `maven-release-plugin` on demand
- `publish-central.yml` publishes signed artifacts to Maven Central on pushed version tags
- `publish-github-release.yml` creates GitHub Releases on pushed version tags
