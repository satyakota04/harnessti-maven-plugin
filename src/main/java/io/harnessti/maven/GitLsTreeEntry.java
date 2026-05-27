package io.harnessti.maven;

/**
 * Represents a single blob entry from "git ls-tree -r -t HEAD" output.
 */
public final class GitLsTreeEntry {
    private final String blobSha;
    private final String filePath;

    public GitLsTreeEntry(String blobSha, String filePath) {
        this.blobSha = blobSha;
        this.filePath = filePath;
    }

    public String getBlobSha() {
        return blobSha;
    }

    public String getFilePath() {
        return filePath;
    }
}
