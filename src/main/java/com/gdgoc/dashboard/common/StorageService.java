package com.gdgoc.dashboard.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@Service
public class StorageService {

    private final Path rootDir;

    public StorageService(@Value("${app.storage.local-dir:./data/storage}") String localDir) {
        this.rootDir = Paths.get(localDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(this.rootDir);
        } catch (IOException e) {
            throw new IllegalStateException("Could not initialize storage directory: " + this.rootDir, e);
        }
    }

    public String saveBytes(String relativeKey, byte[] content) {
        try {
            Path target = resolveSafe(relativeKey);
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.write(target, content);
            return relativeKey;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write storage key: " + relativeKey, e);
        }
    }

    public byte[] readBytes(String relativeKey) {
        try {
            Path target = resolveSafe(relativeKey);
            if (!Files.exists(target)) {
                return null;
            }
            return Files.readAllBytes(target);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read storage key: " + relativeKey, e);
        }
    }

    public boolean exists(String relativeKey) {
        if (relativeKey == null || relativeKey.isBlank()) {
            return false;
        }
        return Files.exists(resolveSafe(relativeKey));
    }

    private Path resolveSafe(String relativeKey) {
        String cleaned = relativeKey.startsWith("/") ? relativeKey.substring(1) : relativeKey;
        Path resolved = this.rootDir.resolve(cleaned).normalize();
        if (!resolved.startsWith(this.rootDir)) {
            throw new BadRequestException("Invalid storage path");
        }
        return resolved;
    }
}
