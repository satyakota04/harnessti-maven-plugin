package io.harnessti.maven;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class SourceClassNameCalculator {

    /**
     * Calculates source classes with artifact-scoped keys for multi-module support.
     * Returns a map where keys are {artifactId}-{version}.jar:{fullyQualifiedClassName}
     * (e.g. "myapp-1.2.3.jar:com.example.Foo") and values are file paths relative to repo root.
     */
    public Map<String, String> calculateSourceClassesWithModules(List<GitLsTreeEntry> entries, List<ModuleInfo> modules) {
        Map<String, String> sourceClasses = new HashMap<>();

        for (GitLsTreeEntry entry : entries) {
            String filePath = entry.getFilePath();
            if (!filePath.endsWith(".java")
                    || filePath.endsWith("/package-info.java")
                    || filePath.endsWith("/module-info.java")) {
                continue;
            }

            // Find which module this file belongs to
            ModuleInfo module = ModuleDiscovery.findModuleForFile(filePath, modules);
            if (module == null) {
                continue;
            }

            String className = deriveClassName(filePath, module.getSourceRoot());
            if (className != null && !className.isEmpty()) {
                String key = module.getJarId() + ":" + className;
                sourceClasses.put(key, filePath);
            }
        }

        return sourceClasses;
    }

    /**
     * Legacy method for backward compatibility (single module).
     * Used when multi-module discovery is not available.
     */
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

            String className = deriveClassNameLegacy(filePath, relativeSourceRoots);
            if (className != null && !className.isEmpty()) {
                sourceClasses.put(className, filePath);
            }
        }

        return sourceClasses;
    }

    /**
     * Derives the fully qualified class name from a file path and module source root.
     */
    private String deriveClassName(String filePath, String sourceRoot) {
        String prefix = sourceRoot + "/";
        if (!filePath.startsWith(prefix)) {
            return null;
        }

        String relativePath = filePath.substring(prefix.length());
        if (!relativePath.endsWith(".java") || relativePath.length() <= 5) {
            return null;
        }

        String classPath = relativePath.substring(0, relativePath.length() - 5);
        return classPath.replace('/', '.');
    }

    /**
     * Legacy method for deriving class name without module context.
     */
    private String deriveClassNameLegacy(String filePath, Set<String> sourceRoots) {
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
