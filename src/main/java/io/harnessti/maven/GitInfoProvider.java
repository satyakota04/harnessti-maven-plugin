package io.harnessti.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class GitInfoProvider {

    private final File projectBaseDir;

    public GitInfoProvider(File projectBaseDir) {
        this.projectBaseDir = projectBaseDir;
    }

    /**
     * Discovers the git repository root directory.
     */
    public String getGitRepoRoot() throws IOException {
        return executeGitCommand("git", "rev-parse", "--show-toplevel");
    }

    public GitInfo getGitInfo(List<String> sourcePaths) throws IOException {
        String repositoryUrl = executeGitCommand("git", "config", "--get", "remote.origin.url");
        String commitSha = executeGitCommand("git", "rev-parse", "HEAD");

        return new GitInfo(repositoryUrl, commitSha, sourcePaths);
    }

    /**
     * Runs "git ls-tree -r -t HEAD" once and returns parsed blob entries.
     */
    public List<GitLsTreeEntry> runLsTree() throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder("git", "ls-tree", "-r", "-t", "HEAD");
        processBuilder.directory(projectBaseDir);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();
        List<String> rawLines = new ArrayList<>();

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawLines.add(line);
            }
        }

        int exitCode;
        try {
            boolean finished = process.waitFor(30, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new IOException("git ls-tree timed out after 30 seconds");
            }
            exitCode = process.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while running git ls-tree", e);
        }

        if (exitCode != 0) {
            throw new IOException("git ls-tree failed: " + String.join(System.lineSeparator(), rawLines));
        }

        List<GitLsTreeEntry> entries = new ArrayList<>();
        for (String line : rawLines) {
            int tabIndex = line.indexOf('\t');
            if (tabIndex < 0) {
                continue;
            }

            String metadata = line.substring(0, tabIndex);
            String filePath = line.substring(tabIndex + 1);
            String[] parts = metadata.split("\\s+");

            if (parts.length < 3 || !"blob".equals(parts[1])) {
                continue;
            }

            entries.add(new GitLsTreeEntry(parts[2], filePath));
        }

        return entries;
    }

    /**
     * Converts absolute source root paths to relative paths from project base directory.
     */
    public Set<String> collectRelativeSourceRoots(List<String> sourceRoots) {
        Set<String> relativeRoots = new HashSet<>();
        for (String sourceRoot : sourceRoots) {
            File sourceDir = new File(sourceRoot);
            if (!sourceDir.exists() || !sourceDir.isDirectory()) {
                continue;
            }
            String relativePath = projectBaseDir.toPath().relativize(sourceDir.toPath()).toString();
            relativeRoots.add(relativePath.replace(File.separatorChar, '/'));
        }
        return relativeRoots;
    }

    /**
     * Checks if a file path is within any of the provided source roots.
     */
    public static boolean isInSourceRoots(String filePath, Set<String> sourceRoots) {
        for (String sourceRoot : sourceRoots) {
            if (filePath.equals(sourceRoot) || filePath.startsWith(sourceRoot + "/")) {
                return true;
            }
        }
        return false;
    }

    private String executeGitCommand(String... command) throws IOException {
        ProcessBuilder processBuilder = new ProcessBuilder(command);
        processBuilder.directory(projectBaseDir);
        processBuilder.redirectErrorStream(true);

        Process process = processBuilder.start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String result = reader.lines().collect(Collectors.joining("\n")).trim();

            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new IOException("Git command failed with exit code " + exitCode + ": " + String.join(" ", command));
            }

            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git command interrupted", e);
        }
    }

    public static class GitInfo {
        private final String repositoryUrl;
        private final String commitSha;
        private final List<String> sourcePaths;

        public GitInfo(String repositoryUrl, String commitSha, List<String> sourcePaths) {
            this.repositoryUrl = repositoryUrl;
            this.commitSha = commitSha;
            this.sourcePaths = sourcePaths;
        }

        public String getRepositoryUrl() {
            return repositoryUrl;
        }

        public String getCommitSha() {
            return commitSha;
        }

        public List<String> getSourcePaths() {
            return sourcePaths;
        }
    }
}
