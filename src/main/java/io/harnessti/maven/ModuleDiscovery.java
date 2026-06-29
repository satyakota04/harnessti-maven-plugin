package io.harnessti.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Discovers all Maven modules in a repository by scanning for pom.xml files
 * and extracting artifactId from each.
 */
public class ModuleDiscovery {

    /**
     * Discovers all modules starting from the given repo root.
     * Returns a list of ModuleInfo with artifactId and sourceRoot (relative to repoRoot).
     */
    public static List<ModuleInfo> discoverModules(File repoRoot) throws IOException {
        List<ModuleInfo> modules = new ArrayList<>();

        // Find all pom.xml files
        List<File> pomFiles = findPomFiles(repoRoot);

        for (File pomFile : pomFiles) {
            String artifactId = extractArtifactId(pomFile);
            if (artifactId != null && !artifactId.isEmpty()) {
                String version = extractVersion(pomFile);
                File moduleDir = pomFile.getParentFile();
                String sourceRoot = resolveSourceRoot(repoRoot, moduleDir);
                modules.add(new ModuleInfo(artifactId, version, sourceRoot));
            }
        }

        return modules;
    }

    /**
     * Finds all pom.xml files recursively from the given directory.
     * Excludes build output directories and version control directories.
     */
    private static List<File> findPomFiles(File root) throws IOException {
        List<File> pomFiles = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root.toPath())) {
            paths.filter(path -> {
                // Check if path contains excluded directories
                for (Path part : path) {
                    String name = part.getFileName().toString();
                    if (name.equals("target") || name.equals(".git") ||
                        name.equals("node_modules") || name.equals("build") ||
                        name.equals(".idea") || name.equals(".gradle")) {
                        return false;
                    }
                }
                return path.getFileName().toString().equals("pom.xml");
            }).forEach(path -> pomFiles.add(path.toFile()));
        }

        return pomFiles;
    }

    /**
     * Extracts artifactId from a pom.xml file.
     * Uses a simple parser that reads the first ~50 lines looking for <artifactId>.
     * Skips <artifactId> tags within <parent> blocks to avoid picking up parent artifactId.
     */
    private static String extractArtifactId(File pomFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(pomFile))) {
            String line;
            int lineCount = 0;
            boolean insideParent = false;

            while ((line = reader.readLine()) != null && lineCount < 50) {
                lineCount++;
                line = line.trim();

                // Track <parent> block
                if (line.startsWith("<parent>") || line.equals("<parent")) {
                    insideParent = true;
                    continue;
                }
                if (line.startsWith("</parent>") || line.equals("</parent")) {
                    insideParent = false;
                    continue;
                }

                // Skip artifactId if inside parent block
                if (insideParent) {
                    continue;
                }

                // Look for <artifactId>value</artifactId>
                if (line.startsWith("<artifactId>") && line.endsWith("</artifactId>")) {
                    int startIdx = line.indexOf(">") + 1;
                    int endIdx = line.lastIndexOf("<");
                    if (startIdx > 0 && endIdx > startIdx) {
                        return line.substring(startIdx, endIdx).trim();
                    }
                }
            }
        } catch (IOException e) {
            // Skip files that can't be read
        }

        return null;
    }

    /**
     * Extracts version from a pom.xml file.
     * Uses a simple parser that reads the first ~80 lines looking for <version>.
     * Skips <version> tags within <parent> blocks to avoid picking up parent version.
     */
    private static String extractVersion(File pomFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(pomFile))) {
            String line;
            int lineCount = 0;
            boolean insideParent = false;

            while ((line = reader.readLine()) != null && lineCount < 80) {
                lineCount++;
                line = line.trim();

                // Track <parent> block
                if (line.startsWith("<parent>") || line.equals("<parent")) {
                    insideParent = true;
                    continue;
                }
                if (line.startsWith("</parent>") || line.equals("</parent")) {
                    insideParent = false;
                    continue;
                }

                // Skip version if inside parent block
                if (insideParent) {
                    continue;
                }

                // Look for <version>value</version>
                if (line.startsWith("<version>") && line.endsWith("</version>")) {
                    int startIdx = line.indexOf(">") + 1;
                    int endIdx = line.lastIndexOf("<");
                    if (startIdx > 0 && endIdx > startIdx) {
                        String v = line.substring(startIdx, endIdx).trim();
                        // Skip obvious property placeholders or parent refs if they don't look like a version
                        if (v != null && !v.isEmpty() && !v.startsWith("${")) {
                            return v;
                        }
                    }
                }
            }
        } catch (IOException e) {
            // Skip files that can't be read
        }

        return null;
    }

    /**
     * Resolves the source root for a module.
     * Returns the path relative to repoRoot in the format: moduleDir/src/main/java
     */
    private static String resolveSourceRoot(File repoRoot, File moduleDir) {
        // Default Maven source directory
        File srcMainJava = new File(moduleDir, "src/main/java");

        // Compute relative path from repoRoot
        Path repoPath = repoRoot.toPath();
        Path srcPath = srcMainJava.toPath();

        String relativePath = repoPath.relativize(srcPath).toString();
        return relativePath.replace(File.separatorChar, '/');
    }

    /**
     * Finds which module a given file path belongs to based on its source root prefix.
     * Returns the ModuleInfo or null if no match found.
     */
    public static ModuleInfo findModuleForFile(String filePath, List<ModuleInfo> modules) {
        // Find the longest matching source root (most specific match)
        ModuleInfo bestMatch = null;
        int longestMatchLength = 0;

        for (ModuleInfo module : modules) {
            String sourceRoot = module.getSourceRoot();
            if (filePath.startsWith(sourceRoot + "/") || filePath.equals(sourceRoot)) {
                if (sourceRoot.length() > longestMatchLength) {
                    bestMatch = module;
                    longestMatchLength = sourceRoot.length();
                }
            }
        }

        return bestMatch;
    }
}
