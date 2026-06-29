package io.harnessti.maven;

/**
 * Represents a Maven module with its artifact ID, version and source root.
 */
public class ModuleInfo {
    private final String artifactId;
    private final String version;     // may be null
    private final String sourceRoot;  // relative to repo root

    public ModuleInfo(String artifactId, String version, String sourceRoot) {
        this.artifactId = artifactId;
        this.version = version;
        this.sourceRoot = sourceRoot;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public String getVersion() {
        return version;
    }

    public String getSourceRoot() {
        return sourceRoot;
    }

    /**
     * Returns the jar identifier as "artifactId.jar" (version intentionally omitted so the id
     * is consistent whether or not the module declares its own version).
     */
    public String getJarId() {
        if (artifactId == null || artifactId.isEmpty()) {
            return "unknown.jar";
        }
        return artifactId + ".jar";
    }

    @Override
    public String toString() {
        return "ModuleInfo{artifactId='" + artifactId + "', version='" + version + "', sourceRoot='" + sourceRoot + "'}";
    }
}
