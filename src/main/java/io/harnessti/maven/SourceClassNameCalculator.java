package io.harnessti.maven;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SourceClassNameCalculator {

    public Map<String, String> calculateSourceClasses(List<GitLsTreeEntry> entries, Set<String> relativeSourceRoots) {
        Map<String, String> sourceClasses = new HashMap<>();

        for (GitLsTreeEntry entry : entries) {
            String filePath = entry.getFilePath();
            if (!filePath.endsWith(".java")
                    || filePath.endsWith("/package-info.java")
                    || filePath.endsWith("/module-info.java")) {
                continue;
            }
            if (!GitInfoProvider.isInSourceRoots(filePath, relativeSourceRoots)) {
                continue;
            }

            String className = deriveClassName(filePath, relativeSourceRoots);
            if (className != null && !className.isEmpty()) {
                sourceClasses.put(className, filePath);
            }
        }

        return sourceClasses;
    }

    private String deriveClassName(String filePath, Set<String> sourceRoots) {
        for (String sourceRoot : sourceRoots) {
            String prefix = sourceRoot + "/";
            if (!filePath.startsWith(prefix)) {
                continue;
            }

            String relativePath = filePath.substring(prefix.length());
            if (!relativePath.endsWith(".java") || relativePath.length() <= 5) {
                return null;
            }

            String classPath = relativePath.substring(0, relativePath.length() - 5);
            return classPath.replace('/', '.');
        }

        return null;
    }
}
