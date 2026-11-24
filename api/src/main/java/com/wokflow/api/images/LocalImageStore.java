package com.wokflow.api.images;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Files in a directory (a named volume in compose, shared by both API replicas). Names are content
 * hashes, so a write is never an overwrite of different bytes; it goes to a temp file and is moved
 * into place so a reader never sees half an image.
 */
public class LocalImageStore implements ImageStore {
    static final Pattern NAME = Pattern.compile("[a-z]/[0-9a-f]{20}-\\d{3,4}\\.jpg");

    private final Path root;

    public LocalImageStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.root);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot create image directory " + root, e);
        }
    }

    private Path resolve(String name) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("bad image name");
        }
        return root.resolve(name);
    }

    @Override
    public void put(String name, byte[] bytes, String contentType) {
        Path target = resolve(name);
        try {
            Files.createDirectories(target.getParent());
            Path tmp = Files.createTempFile(target.getParent(), ".img", ".tmp");
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Optional<byte[]> get(String name) {
        if (!NAME.matcher(name).matches()) {
            return Optional.empty();
        }
        try {
            Path p = root.resolve(name);
            return Files.exists(p) ? Optional.of(Files.readAllBytes(p)) : Optional.empty();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public boolean exists(String name) {
        return NAME.matcher(name).matches() && Files.exists(root.resolve(name));
    }
}
