package io.lumnik.hub.connector.transport;

import java.io.*;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

public class LocalFileSource implements FileSource, SeekableSource {

    private final Path baseDir;
    private final PathMatcher matcher;

    public LocalFileSource(String pathGlob) {
        int globIdx = pathGlob.indexOf('*');
        if (globIdx < 0) {
            Path full = Paths.get(pathGlob);
            this.baseDir = full.getParent() != null ? full.getParent() : Paths.get(".");
            String fileName = full.getFileName().toString();
            this.matcher = p -> p.getFileName().toString().equals(fileName);
        } else {
            this.baseDir = Paths.get(pathGlob.substring(0, globIdx)).normalize();
            String pattern = pathGlob.substring(globIdx);
            PathMatcher m = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            this.matcher = p -> m.matches(p.getFileName());
        }
    }

    @Override
    public Stream<FileDescriptor> listSince(Instant cursor) {
        if (!Files.isDirectory(baseDir)) return Stream.empty();
        try (Stream<Path> paths = Files.list(baseDir)) {
            return paths
                .filter(Files::isRegularFile)
                .filter(matcher::matches)
                .map(this::toDescriptor)
                .filter(d -> !d.lastModified().isBefore(cursor))   // >= cursor: inclusive boundary (callers dedup via FileProcessingCursor)
                .filter(d -> d.size() > 0)
                .sorted(Comparator.comparing(FileDescriptor::lastModified))
                .toList()
                .stream();
        } catch (IOException e) {
            throw new RuntimeException("LocalFileSource.list failed for " + baseDir, e);
        }
    }

    private FileDescriptor toDescriptor(Path p) {
        try {
            return new FileDescriptor(
                p.toAbsolutePath().toString(),
                Files.size(p),
                Files.getLastModifiedTime(p).toInstant(),
                null
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public InputStream open(FileDescriptor file) {
        try {
            return Files.newInputStream(Paths.get(file.path()));
        } catch (IOException e) {
            throw new RuntimeException("LocalFileSource.open failed for " + file.path(), e);
        }
    }

    /**
     * Real positioned seek via {@link SeekableByteChannel#position}, not a read-and-discard
     * loop over the skipped prefix — the channel jumps straight to {@code byteOffset}.
     */
    @Override
    public InputStream openAt(FileDescriptor file, long byteOffset) {
        if (byteOffset < 0) {
            throw new IllegalArgumentException(
                "LocalFileSource.openAt: negative offset " + byteOffset + " for " + file.path());
        }
        try {
            SeekableByteChannel channel = Files.newByteChannel(Paths.get(file.path()), StandardOpenOption.READ);
            channel.position(byteOffset);
            return Channels.newInputStream(channel);
        } catch (IOException e) {
            throw new RuntimeException(
                "LocalFileSource.openAt failed for " + file.path() + " at offset " + byteOffset, e);
        }
    }

    @Override
    public void markProcessed(FileDescriptor file, AfterProcessAction action, Map<String, Object> cfg) {
        Path p = Paths.get(file.path());
        try {
            switch (action) {
                case LEAVE -> {}
                case DELETE -> Files.deleteIfExists(p);
                case MOVE -> {
                    String archive = (String) cfg.getOrDefault("archive_path", baseDir.resolve("archive").toString());
                    Path dest = Paths.get(archive).resolve(file.name());
                    Files.createDirectories(dest.getParent());
                    Files.move(p, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                case CUSTOM_KOTLIN -> throw new IllegalStateException(
                    "CUSTOM_KOTLIN must be handled by the connector runtime, not the FileSource");
            }
        } catch (IOException e) {
            throw new RuntimeException("LocalFileSource.markProcessed failed for " + file.path(), e);
        }
    }
}
