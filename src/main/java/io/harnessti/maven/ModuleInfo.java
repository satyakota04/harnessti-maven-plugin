package io.harnessti.maven;

/**
 * Represents a Maven module with its artifact ID and source root.
 */
public class ModuleInfo {
    private final String artifactId;
    private final String sourceRoot;  // relative to repo root

    public ModuleInfo(String artifactId, String sourceRoot) {
        this.artifactId = artifactId;
        this.sourceRoot = sourceRoot;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getSourceRoot() {
        return sourceRoot;
    }

    @Override
    public String toString() {
        return "ModuleInfo{artifactId='" + artifactId + "', sourceRoot='" + sourceRoot + "'}";
    }
}
