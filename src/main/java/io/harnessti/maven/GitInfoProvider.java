package io.harnessti.maven;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.stream.Collectors;

public class GitInfoProvider {

    private final File projectBaseDir;

    public GitInfoProvider(File projectBaseDir) {
        this.projectBaseDir = projectBaseDir;
    }

    public GitInfo getGitInfo(List<String> sourcePaths) throws IOException {
        String repositoryUrl = executeGitCommand("git", "config", "--get", "remote.origin.url");
        String commitSha = executeGitCommand("git", "rev-parse", "HEAD");

        return new GitInfo(repositoryUrl, commitSha, sourcePaths);
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
