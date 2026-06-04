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
                File moduleDir = pomFile.getParentFile();
                String sourceRoot = resolveSourceRoot(repoRoot, moduleDir);
                modules.add(new ModuleInfo(artifactId, sourceRoot));
            }
        }

        return modules;
    }

    /**
     * Finds all pom.xml files recursively from the given directory.
     */
    private static List<File> findPomFiles(File root) throws IOException {
        List<File> pomFiles = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(root.toPath())) {
            paths.filter(path -> path.getFileName().toString().equals("pom.xml"))
                 .forEach(path -> pomFiles.add(path.toFile()));
        }

        return pomFiles;
    }

    /**
     * Extracts artifactId from a pom.xml file.
     * Uses a simple parser that reads the first ~50 lines looking for <artifactId>.
     */
    private static String extractArtifactId(File pomFile) {
        try (BufferedReader reader = new BufferedReader(new FileReader(pomFile))) {
            String line;
            int lineCount = 0;

            while ((line = reader.readLine()) != null && lineCount < 50) {
                lineCount++;
                line = line.trim();

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
