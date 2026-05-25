package io.harnessti.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

public class SourceChecksumCalculator {

    private final File projectBaseDir;

    public SourceChecksumCalculator(File projectBaseDir) {
        this.projectBaseDir = projectBaseDir;
    }

    public Map<String, String> calculateChecksums(List<String> sourceRoots) throws IOException {
        Map<String, String> checksums = new HashMap<>();
        MessageDigest md5;

        try {
            md5 = MessageDigest.getInstance("MD5");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("MD5 algorithm not available", e);
        }

        for (String sourceRoot : sourceRoots) {
            File sourceDir = new File(sourceRoot);
            if (!sourceDir.exists() || !sourceDir.isDirectory()) {
                continue;
            }

            try (Stream<Path> paths = Files.walk(sourceDir.toPath())) {
                paths.filter(Files::isRegularFile)
                     .filter(path -> path.toString().endsWith(".java"))
                     .forEach(path -> {
                         try {
                             String relativePath = projectBaseDir.toPath().relativize(path).toString();
                             String checksum = computeMD5(path.toFile(), md5);
                             checksums.put(relativePath, checksum);
                         } catch (IOException e) {
                             throw new RuntimeException("Failed to compute checksum for " + path, e);
                         }
                     });
            }
        }

        return checksums;
    }

    private String computeMD5(File file, MessageDigest md5) throws IOException {
        md5.reset();

        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                md5.update(buffer, 0, bytesRead);
            }
        }

        byte[] digest = md5.digest();
        StringBuilder hexString = new StringBuilder();
        for (byte b : digest) {
            String hex = Integer.toHexString(0xff & b);
            if (hex.length() == 1) {
                hexString.append('0');
            }
            hexString.append(hex);
        }

        return hexString.toString();
    }
}
