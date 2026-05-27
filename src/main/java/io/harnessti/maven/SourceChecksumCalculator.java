package io.harnessti.maven;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SourceChecksumCalculator {

    public Map<String, String> calculateChecksums(List<GitLsTreeEntry> entries, Set<String> relativeSourceRoots) {
        Map<String, String> checksums = new HashMap<>();

        for (GitLsTreeEntry entry : entries) {
            String filePath = entry.getFilePath();
            if (!filePath.endsWith(".java")) {
                continue;
            }
            if (!GitInfoProvider.isInSourceRoots(filePath, relativeSourceRoots)) {
                continue;
            }
            checksums.put(filePath, entry.getBlobSha());
        }

        return checksums;
    }
}
