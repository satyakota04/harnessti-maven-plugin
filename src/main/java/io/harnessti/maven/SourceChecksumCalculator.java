package io.harnessti.maven;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SourceChecksumCalculator {

    /**
     * Calculates checksums for ALL .java files in the repository.
     * This ensures all modules compute the same manifestId for content-addressable storage.
     *
     * NOTE: relativeSourceRoots parameter is IGNORED - kept for backward compatibility.
     * We want ALL modules to see ALL .java files and compute the same manifestId.
     */
    public Map<String, String> calculateChecksums(List<GitLsTreeEntry> entries, Set<String> relativeSourceRoots) {
        Map<String, String> checksums = new HashMap<>();

        for (GitLsTreeEntry entry : entries) {
            String filePath = entry.getFilePath();

            // Only include .java files (excluding package-info and module-info)
            if (!filePath.endsWith(".java")
                    || filePath.endsWith("/package-info.java")
                    || filePath.endsWith("/module-info.java")) {
                continue;
            }

            // Include ALL .java files from the repo, not just current module
            // This ensures all modules compute the same manifestId
            checksums.put(filePath, entry.getBlobSha());
        }

        return checksums;
    }
}
