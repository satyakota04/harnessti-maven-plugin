package io.harnessti.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

@Mojo(name = "embed-metadata", defaultPhase = LifecyclePhase.VERIFY)
public class MetadataMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "harness.metadata.failOnError", defaultValue = "false")
    private boolean failOnError;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            executeInternal();
        } catch (Exception e) {
            String errorMsg = "Failed to embed source metadata: " + e.getMessage();

            if (failOnError) {
                // Strict mode - fail the build
                throw new MojoExecutionException(errorMsg, e);
            } else {
                // Default mode - log warning and continue
                getLog().warn("─────────────────────────────────────────────────────");
                getLog().warn("⚠️  " + errorMsg);
                getLog().warn("⚠️  Build will continue without metadata");
                getLog().warn("⚠️  Set <failOnError>true</failOnError> to enforce metadata embedding");
                getLog().warn("─────────────────────────────────────────────────────");

                if (getLog().isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
        }
    }

    private void executeInternal() throws IOException, MojoExecutionException {
        getLog().info("Embedding source metadata in artifact...");

        // Get source roots
        List<String> sourceRoots = new ArrayList<>();
        sourceRoots.addAll(project.getCompileSourceRoots());
        sourceRoots.addAll(project.getTestCompileSourceRoots());

        File baseDir = project.getBasedir();
        GitInfoProvider gitInfoProviderModule = new GitInfoProvider(baseDir);

        // Discover git repo root
        String repoRootPath = gitInfoProviderModule.getGitRepoRoot();
        File repoRoot = new File(repoRootPath);
        getLog().debug("Git repo root: " + repoRootPath);

        // Create GitInfoProvider from REPO ROOT (not module dir)
        // This ensures git ls-tree sees ALL files in the repo
        GitInfoProvider gitInfoProvider = new GitInfoProvider(repoRoot);

        // Discover all modules in the repo
        List<ModuleInfo> modules = ModuleDiscovery.discoverModules(repoRoot);
        getLog().info("Discovered " + modules.size() + " modules in repository");
        for (ModuleInfo module : modules) {
            getLog().debug("  Module: " + module.getArtifactId() + " -> " + module.getSourceRoot());
        }

        // Compute relative source roots from repo root (for checksums)
        java.util.Set<String> relativeSourceRoots = gitInfoProviderModule.collectRelativeSourceRoots(sourceRoots);

        // Get Git metadata
        GitInfoProvider.GitInfo gitInfo = gitInfoProvider.getGitInfo(new ArrayList<>(relativeSourceRoots));

        getLog().debug("Repository URL: " + gitInfo.getRepositoryUrl());
        getLog().debug("Commit SHA: " + gitInfo.getCommitSha());

        // Run git ls-tree from repo root (via gitInfoProvider created with repoRoot)
        List<GitLsTreeEntry> lsTreeEntries = gitInfoProvider.runLsTree();

        // Compute checksums (all files, not module-specific)
        SourceChecksumCalculator checksumCalculator = new SourceChecksumCalculator();
        Map<String, String> checksums = checksumCalculator.calculateChecksums(lsTreeEntries, relativeSourceRoots);

        // Compute class mappings with artifact-scoped keys
        SourceClassNameCalculator classNameCalculator = new SourceClassNameCalculator();
        Map<String, String> sourceClasses;
        if (!modules.isEmpty()) {
            sourceClasses = classNameCalculator.calculateSourceClassesWithModules(lsTreeEntries, modules);
        } else {
            // Fallback to legacy mode if no modules discovered
            sourceClasses = classNameCalculator.calculateSourceClasses(lsTreeEntries, relativeSourceRoots);
        }

        getLog().info("Calculated checksums for " + checksums.size() + " files, class mappings for " + sourceClasses.size() + " classes");

        // Compute manifest ID (SHA-256 of sorted checksums)
        String manifestId = computeManifestId(checksums);
        if (manifestId == null) {
            throw new MojoExecutionException("Failed to compute manifest ID");
        }
        getLog().debug("Computed manifest ID: " + manifestId);

        // Always call uploadManifest - server decides whether to return signed URL or process directly
        boolean uploadSuccess = uploadManifest(checksums, sourceClasses, manifestId, gitInfo.getRepositoryUrl());

        File metaInfDir = resolveMetaInfDir();

        if (uploadSuccess) {
            // Write manifest metadata file
            writeManifestMetadata(metaInfDir, manifestId, gitInfo.getRepositoryUrl(), gitInfo.getCommitSha(), sourceClasses);
        } else {
            // Fallback: write metadata + data files to JAR for later upload
            getLog().warn("Manifest upload failed, writing manifest files to JAR for later upload");
            writeManifestMetadata(metaInfDir, manifestId, gitInfo.getRepositoryUrl(), gitInfo.getCommitSha(), sourceClasses);
            writeManifestData(metaInfDir, checksums, sourceClasses);
            getLog().info("Fallback: manifest files written to META-INF");
        }

        // If we wrote to temp dir for JAR injection, update the JAR now
        String jarPath = project.getProperties().getProperty("harness.metadata.jar");
        String tempPath = project.getProperties().getProperty("harness.metadata.temp");
        if (jarPath != null && tempPath != null) {
            updateJarWithMetadata(new File(jarPath), new File(tempPath));
        }
    }

    private void updateJarWithMetadata(File jarFile, File tempDir) throws IOException {
        getLog().info("Injecting metadata into final JAR: " + jarFile.getName());

        // Use jar command to update the JAR
        ProcessBuilder pb = new ProcessBuilder("jar", "uf", jarFile.getAbsolutePath(),
            "-C", tempDir.getAbsolutePath(), "META-INF");
        pb.redirectErrorStream(true);
        Process process = pb.start();

        try {
            int exitCode = process.waitFor();
            if (exitCode == 0) {
                getLog().info("Successfully injected metadata into JAR");
            } else {
                getLog().warn("Failed to inject metadata into JAR (exit code: " + exitCode + ")");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while updating JAR", e);
        }

        // Clean up temp directory
        deleteDirectory(tempDir);
    }

    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }

    private String computeManifestId(Map<String, String> checksums) {
        try {
            List<String> sortedPaths = new ArrayList<>(checksums.keySet());
            Collections.sort(sortedPaths);

            StringBuilder sb = new StringBuilder();
            for (String path : sortedPaths) {
                sb.append(path).append('=').append(checksums.get(path)).append('\n');
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));

            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            getLog().error("Failed to compute manifest ID", e);
            return null;
        }
    }

    private boolean uploadManifest(Map<String, String> checksums, Map<String, String> classes,
                                   String manifestId, String repoUrl) {
        String endpoint = System.getenv("HARNESS_TI_SERVICE_ENDPOINT");
        String accountId = System.getenv("HARNESS_ACCOUNT_ID");
        String token = System.getenv("HARNESS_TI_SERVICE_TOKEN");

        if (endpoint == null || accountId == null || token == null) {
            getLog().warn("Manifest upload failed: TI service env vars not set");
            getLog().warn("  Missing: " + (endpoint == null ? "HARNESS_TI_SERVICE_ENDPOINT " : "")
                         + (accountId == null ? "HARNESS_ACCOUNT_ID " : "")
                         + (token == null ? "HARNESS_TI_SERVICE_TOKEN" : ""));
            return false;
        }

        if (manifestId == null) {
            getLog().warn("Manifest upload failed: could not compute manifest ID");
            return false;
        }

        try {
            String json = buildJson(checksums, classes);
            byte[] compressed = gzipBytes(json.getBytes(StandardCharsets.UTF_8));

            String urlStr = endpoint + "/manifests/write"
                    + "?accountId=" + URLEncoder.encode(accountId, "UTF-8")
                    + "&manifestId=" + URLEncoder.encode(manifestId, "UTF-8")
                    + "&repoUrl=" + URLEncoder.encode(repoUrl, "UTF-8");

            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Content-Encoding", "gzip");
            conn.setRequestProperty("X-Harness-Token", token);
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(30_000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(compressed.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(compressed);
            }

            int status = conn.getResponseCode();

            if (status >= 200 && status < 300) {
                // Read response body
                String responseBody = new String(readStream(conn.getInputStream()), StandardCharsets.UTF_8);
                getLog().debug("Response body: " + responseBody);

                // Check if response contains signed_url (server wants us to upload to GCS directly)
                if (responseBody.contains("\"signed_url\"")) {
                    // Extract signed URL
                    String signedUrl = extractJsonValue(responseBody, "signed_url");
                    if (signedUrl == null || signedUrl.isEmpty()) {
                        getLog().warn("Failed to extract signed URL from response");
                        return false;
                    }

                    // Upload to GCS using signed URL
                    return uploadToGCS(signedUrl, compressed, checksums, classes, manifestId);
                }

                // No signed URL - direct POST completed or dedup
                String uploadMode = extractJsonValue(responseBody, "upload_mode");
                getLog().info("Manifest uploaded (mode=" + (uploadMode != null ? uploadMode : "direct_post")
                            + ", checksums=" + checksums.size() + ", classes=" + classes.size() + ")");
                return true;
            } else {
                getLog().warn("Manifest upload failed: HTTP " + status);

                // Try to read response body for more details
                try {
                    InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        String errorResponse = new String(readStream(errorStream), StandardCharsets.UTF_8);
                        getLog().warn("Server response: " + errorResponse);
                    }
                } catch (Exception e) {
                    // Ignore error reading error stream
                }

                return false;
            }
        } catch (IOException e) {
            getLog().warn("Manifest upload failed: " + e.getMessage());
            if (getLog().isDebugEnabled()) {
                e.printStackTrace();
            }
            return false;
        }
    }

    /**
     * Uploads compressed manifest directly to GCS using signed URL.
     */
    private boolean uploadToGCS(String signedUrl, byte[] compressed,
                                Map<String, String> checksums, Map<String, String> classes,
                                String manifestId) {
        try {
            URL url = new URL(signedUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("PUT");
            conn.setRequestProperty("Content-Type", "application/octet-stream");
            conn.setConnectTimeout(30_000);
            conn.setReadTimeout(60_000);
            conn.setDoOutput(true);
            conn.setFixedLengthStreamingMode(compressed.length);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(compressed);
            }

            int status = conn.getResponseCode();

            if (status >= 200 && status < 300) {
                getLog().info("Manifest uploaded (mode=signed_url, checksums=" + checksums.size() + ", classes=" + classes.size() + ")");
                return true;
            } else {
                getLog().warn("GCS upload failed: HTTP " + status);
                return false;
            }
        } catch (IOException e) {
            getLog().warn("GCS upload failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Java 8 compatible helper to read all bytes from an InputStream.
     */
    private byte[] readStream(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
            baos.write(buffer, 0, bytesRead);
        }
        return baos.toByteArray();
    }

    /**
     * Simple JSON value extractor (without adding dependencies).
     * Handles escaped quotes within JSON values.
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyStart = json.indexOf(searchKey);
        if (keyStart < 0) return null;

        int colonIdx = json.indexOf(":", keyStart);
        if (colonIdx < 0) return null;

        int valueStart = json.indexOf("\"", colonIdx);
        if (valueStart < 0) return null;
        valueStart++; // Skip the opening quote

        // Scan forward, skipping escaped quotes
        int i = valueStart;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                // Skip escaped character
                i += 2;
            } else if (c == '"') {
                // Found unescaped closing quote
                return json.substring(valueStart, i);
            } else {
                i++;
            }
        }

        return null; // No closing quote found
    }

    private String buildJson(Map<String, String> checksums, Map<String, String> classes) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"checksums\":{");

        boolean first = true;
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(entry.getKey())).append("\":\"")
              .append(escapeJson(entry.getValue())).append('"');
            first = false;
        }

        sb.append("},\"classes\":{");

        first = true;
        for (Map.Entry<String, String> entry : classes.entrySet()) {
            if (!first) sb.append(',');
            sb.append('"').append(escapeJson(entry.getKey())).append("\":\"")
              .append(escapeJson(entry.getValue())).append('"');
            first = false;
        }

        sb.append("}}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }

    private byte[] gzipBytes(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
            gzos.write(data);
        }
        return baos.toByteArray();
    }

    private File resolveMetaInfDir() throws IOException {
        // Check if final artifact exists - this means Spring Boot repackage or war packaging has run
        String finalName = project.getBuild().getFinalName();
        File buildDir = new File(project.getBuild().getDirectory());
        String packaging = project.getPackaging();
        String ext = "war".equals(packaging) ? "war" : "jar";
        File finalArtifact = new File(buildDir, finalName + "." + ext);

        if (finalArtifact.exists()) {
            // Final artifact exists - inject metadata directly into it
            getLog().debug("Final " + ext.toUpperCase() + " found, will inject metadata: " + finalArtifact.getAbsolutePath());
            return injectMetadataIntoJar(finalArtifact);
        } else {
            // No final artifact yet - write to target/classes (normal flow for maven-shade-plugin, etc.)
            File outputDir = new File(project.getBuild().getOutputDirectory());
            File metaInfDir = new File(outputDir, "META-INF");
            if (!metaInfDir.exists()) {
                metaInfDir.mkdirs();
            }
            return metaInfDir;
        }
    }

    private File injectMetadataIntoJar(File jarFile) throws IOException {
        // Create temporary directory for metadata files
        File tempDir = new File(project.getBuild().getDirectory(), "harness-metadata-temp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        File metaInfDir = new File(tempDir, "META-INF");
        if (!metaInfDir.exists()) {
            metaInfDir.mkdirs();
        }

        // Metadata will be written to this temp directory first
        // Then we'll update the JAR with these files
        project.getProperties().setProperty("harness.metadata.jar", jarFile.getAbsolutePath());
        project.getProperties().setProperty("harness.metadata.temp", tempDir.getAbsolutePath());

        return metaInfDir;
    }

    private void writeManifestMetadata(File metaInfDir, String manifestId, String repoUrl, String commitSha,
                                       Map<String, String> sourceClasses) throws IOException {
        String accountId = System.getenv("HARNESS_ACCOUNT_ID");
        String artifactId = project.getArtifactId();
        String version = project.getVersion();

        // Build jar id like "core-lib-1.0.0-SNAPSHOT.jar" for this module (includes version)
        String currentJarId = toJarId(artifactId, version);

        // Extract package prefixes from source classes (with jarid:prefix)
        String packagePrefixes = extractPackagePrefixes(sourceClasses, currentJarId, artifactId);

        File metadataFile = new File(metaInfDir, "harness-manifest.properties");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(metadataFile), StandardCharsets.UTF_8)) {
            writer.write("account.id=" + (accountId != null ? accountId : "") + "\n");
            writer.write("manifest.id=" + manifestId + "\n");
            writer.write("repo.url=" + repoUrl + "\n");
            writer.write("artifact.id=" + artifactId + "\n");
            writer.write("commit.sha=" + commitSha + "\n");
            if (packagePrefixes != null && !packagePrefixes.isEmpty()) {
                writer.write("class.prefixes=" + packagePrefixes + "\n");
            }
        }

        getLog().debug("Wrote manifest metadata to " + metadataFile.getAbsolutePath());
        if (packagePrefixes != null && !packagePrefixes.isEmpty()) {
            getLog().info("Detected class prefixes: " + packagePrefixes);
        }
    }

    /**
     * Computes actual common package roots for the CURRENT jar only.
     *
     * Each JAR should only contain prefixes for its own classes, not for other modules.
     *
     * "Package prefix" here means the package portion only (everything before the class name).
     * We build a trie of all packages in this jar and collapse single-child chains to find
     * the highest meaningful root(s) for groups of classes.
     *
     * Under generic TLDs (com, org, io, ...):
     *   - We always go at least 2 levels deep (e.g. com.foo.bar or io.harness.cvng)
     *     before choosing a root for a group, unless the package has fewer segments.
     *   - Multiple different user-maintained packages in the same jar are reported separately.
     *   - Bare TLDs are never emitted.
     *
     * Jar ids include version when known: "core-lib-1.0.0-SNAPSHOT.jar:com.example"
     */
    private String extractPackagePrefixes(Map<String, String> sourceClasses, String currentJarId, String artifactId) {
        if (sourceClasses == null || sourceClasses.isEmpty()) {
            return "";
        }
        if (currentJarId == null || currentJarId.isEmpty()) {
            return "";
        }

        // Collect packages for CURRENT jar only
        java.util.Set<String> packages = new java.util.HashSet<>();

        for (String key : sourceClasses.keySet()) {
            String jarId;
            String className;
            if (key.contains(":")) {
                int colon = key.indexOf(":");
                jarId = key.substring(0, colon);
                className = key.substring(colon + 1);
            } else {
                // Legacy mode: keys without jar prefix use currentJarId
                jarId = currentJarId;
                className = key;
            }

            // Only include classes from the CURRENT jar.
            // Match exact, or by artifactId (to tolerate ModuleDiscovery missing the version,
            // or using a different version string for inherited <version> from parent).
            boolean matches = currentJarId.equals(jarId);
            if (!matches && artifactId != null && !artifactId.isEmpty()) {
                if (jarId.equals(artifactId + ".jar")
                        || (jarId.startsWith(artifactId + "-") && jarId.endsWith(".jar"))) {
                    matches = true;
                }
            }
            if (!matches) {
                continue;
            }

            int lastDot = className.lastIndexOf('.');
            if (lastDot > 0) {
                String pkg = className.substring(0, lastDot);
                packages.add(pkg);
            }
        }

        if (packages.isEmpty()) {
            return "";
        }

        // Find common roots for this jar's packages
        java.util.Set<String> roots = findCommonPackageRoots(packages);
        java.util.Set<String> prefixedRoots = new java.util.TreeSet<>();
        for (String root : roots) {
            prefixedRoots.add(currentJarId + ":" + root);
        }

        return String.join(",", prefixedRoots);
    }

    /**
     * Formats a jar identifier including version when available:
     * "artifactId-version.jar" (e.g. "core-lib-1.0.0-SNAPSHOT.jar").
     * Falls back to "artifactId.jar" if no version.
     */
    private static String toJarId(String artifactId, String version) {
        if (artifactId == null || artifactId.isEmpty()) {
            return "unknown.jar";
        }
        if (version != null && !version.isEmpty()) {
            return artifactId + "-" + version + ".jar";
        }
        return artifactId + ".jar";
    }

    /**
     * Generic first segments that are just namespace holders (reverse domain).
     * We never want to report bare "com", "org", "io" etc. as a package prefix.
     */
    private static final java.util.Set<String> GENERIC_TOP = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("com", "org", "io", "net", "edu", "gov", "mil", "int"))
    );

    /**
     * Given a set of package strings, compute the common root package(s)
     * by collapsing chains of single-child package segments.
     *
     * "Package prefix" = the package part only (no class name).
     *
     * Under generic TLDs (com, org, io, ...):
     *   - We always descend at least 2 levels deep before reporting a root,
     *     unless the package literally doesn't have that much depth.
     *   - This prevents emitting bare "com", "org", "io", etc.
     *   - It also allows multiple distinct user roots in one jar to be reported separately
     *     (e.g. io.harness + com.my.project, or com.harness.cvng + com.harness.delegate).
     *   - Branching or leaves at depth 1 under the TLD are pushed down to depth 2+ when possible.
     *
     * Non-generic top level package segments are handled with normal single-child collapsing.
     *
     * The algorithm only walks the (very small) package trie built from this jar's own sources.
     * Recursion depth == max segments in any one package name (typically < 10). No risk of OOM
     * from this logic; the dominant memory users are the checksum map and class map built earlier.
     */
    private static java.util.Set<String> findCommonPackageRoots(java.util.Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return Collections.emptySet();
        }

        TrieNode root = new TrieNode();
        for (String pkg : packages) {
            if (pkg == null || pkg.isEmpty()) continue;
            String[] parts = pkg.split("\\.");
            TrieNode node = root;
            for (String part : parts) {
                node = node.children.computeIfAbsent(part, k -> new TrieNode());
            }
            node.hasClasses = true;
        }

        java.util.Set<String> roots = new java.util.TreeSet<>();

        for (Map.Entry<String, TrieNode> top : root.children.entrySet()) {
            String topSeg = top.getKey();
            TrieNode topNode = top.getValue();

            if (GENERIC_TOP.contains(topSeg)) {
                // Under a generic TLD we force at least 2 levels of depth when possible.
                for (Map.Entry<String, TrieNode> first : topNode.children.entrySet()) {
                    String firstName = first.getKey();
                    TrieNode firstNode = first.getValue();
                    String startPrefix = topSeg + "." + firstName;
                    collectRootsWithMinDepth(startPrefix, firstNode, roots, /*depthUnderGeneric=*/1);
                }
                // Never emit the bare TLD even if someone put classes directly in "com".
            } else {
                // Non-generic first segment: normal collapsing, no forced min depth.
                collectRoots(topSeg, topNode, roots);
            }
        }

        return roots;
    }

    private static void collectRoots(String prefix, TrieNode node, java.util.Set<String> roots) {
        boolean hasDirect = node.hasClasses;
        int childCount = node.children.size();

        if (childCount == 0) {
            // leaf package that contains classes
            roots.add(prefix);
            return;
        }

        if (childCount > 1) {
            // branch point → this level is a common root for multiple sub-trees
            roots.add(prefix);
            return;
        }

        // single child
        if (hasDirect) {
            // classes live in this package AND it has subpackages → report here
            roots.add(prefix);
            return;
        }

        // collapse further
        Map.Entry<String, TrieNode> only = node.children.entrySet().iterator().next();
        collectRoots(prefix + "." + only.getKey(), only.getValue(), roots);
    }

    /**
     * Same single-child collapsing as collectRoots, but with a minimum depth requirement
     * when we are under a generic TLD (com/org/io/...).
     *
     * We only consider emitting a prefix once depthUnderGeneric >= 2 (i.e. at least
     * TLD + two more segments), unless the package tree literally ends earlier.
     *
     * When depth is still < 2 we always descend (even across what would have been
     * branch points at depth 1). This lets distinct sub-packages surface as separate roots.
     *
     * Bounded recursion (depth = # of dot segments in a package name) and tiny working set
     * per jar → no meaningful OOM risk.
     */
    private static void collectRootsWithMinDepth(String prefix, TrieNode node,
                                                 java.util.Set<String> roots,
                                                 int depthUnderGeneric) {
        boolean hasDirect = node.hasClasses;
        int childCount = node.children.size();

        if (childCount == 0) {
            // leaf (or the only depth we could reach)
            roots.add(prefix);
            return;
        }

        if (depthUnderGeneric >= 2) {
            if (childCount > 1 || hasDirect) {
                // branch point or classes here at sufficient depth → this is a root for a group
                roots.add(prefix);
                return;
            }
        }

        // Depth still too shallow, or single-child chain that hasn't met min-depth + direct yet:
        // keep descending into children.
        for (Map.Entry<String, TrieNode> e : node.children.entrySet()) {
            collectRootsWithMinDepth(prefix + "." + e.getKey(), e.getValue(), roots, depthUnderGeneric + 1);
        }
    }

    private static class TrieNode {
        final Map<String, TrieNode> children = new java.util.HashMap<>();
        boolean hasClasses = false;
    }

    /**
     * Writes manifest data (checksums + classes) as JSON to JAR.
     * This is used as a fallback when upload fails, so HCLI can upload later.
     */
    private void writeManifestData(File metaInfDir, Map<String, String> checksums,
                                   Map<String, String> sourceClasses) throws IOException {
        File manifestDataFile = new File(metaInfDir, "harness-manifest-data.json");

        // Build manifest data JSON
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"checksums\": {");

        boolean first = true;
        for (Map.Entry<String, String> entry : checksums.entrySet()) {
            if (!first) sb.append(',');
            sb.append("\n    \"").append(escapeJson(entry.getKey())).append("\": \"")
              .append(escapeJson(entry.getValue())).append('"');
            first = false;
        }
        sb.append("\n  },\n");

        sb.append("  \"classes\": {");
        first = true;
        for (Map.Entry<String, String> entry : sourceClasses.entrySet()) {
            if (!first) sb.append(',');
            sb.append("\n    \"").append(escapeJson(entry.getKey())).append("\": \"")
              .append(escapeJson(entry.getValue())).append('"');
            first = false;
        }
        sb.append("\n  }\n");
        sb.append("}\n");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(manifestDataFile), StandardCharsets.UTF_8)) {
            writer.write(sb.toString());
        }

        getLog().debug("Wrote manifest data to " + manifestDataFile.getAbsolutePath());
    }
}
