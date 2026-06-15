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
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

@Mojo(name = "embed-metadata", defaultPhase = LifecyclePhase.PROCESS_CLASSES)
public class MetadataMojo extends AbstractMojo {

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
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
            getLog().debug("Computed manifest ID: " + manifestId);

            // Always call uploadManifest - server decides whether to return signed URL or process directly
            boolean uploadSuccess = uploadManifest(checksums, sourceClasses, manifestId, gitInfo.getRepositoryUrl());

            File metaInfDir = resolveMetaInfDir();

            if (uploadSuccess) {
                // Write manifest metadata file
                writeManifestMetadata(metaInfDir, manifestId, gitInfo.getRepositoryUrl(), gitInfo.getCommitSha());
            } else {
                // Fallback: write metadata + data files to JAR for later upload
                getLog().warn("Manifest upload failed, writing manifest files to JAR for later upload");
                writeManifestMetadata(metaInfDir, manifestId, gitInfo.getRepositoryUrl(), gitInfo.getCommitSha());
                writeManifestData(metaInfDir, checksums, sourceClasses);
                getLog().info("Fallback: manifest files written to META-INF");
            }

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to embed source metadata", e);
        }
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
                String responseBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
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
                    java.io.InputStream errorStream = conn.getErrorStream();
                    if (errorStream != null) {
                        String errorResponse = new String(errorStream.readAllBytes(), StandardCharsets.UTF_8);
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
     * Simple JSON value extractor (without adding dependencies).
     */
    private String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int keyStart = json.indexOf(searchKey);
        if (keyStart < 0) return null;

        int colonIdx = json.indexOf(":", keyStart);
        if (colonIdx < 0) return null;

        int valueStart = json.indexOf("\"", colonIdx);
        if (valueStart < 0) return null;
        valueStart++; // Skip the quote

        int valueEnd = json.indexOf("\"", valueStart);
        if (valueEnd < 0) return null;

        return json.substring(valueStart, valueEnd);
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

    private File resolveMetaInfDir() {
        File outputDir = new File(project.getBuild().getOutputDirectory());
        File metaInfDir = new File(outputDir, "META-INF");
        if (!metaInfDir.exists()) {
            metaInfDir.mkdirs();
        }
        return metaInfDir;
    }

    private void writeManifestMetadata(File metaInfDir, String manifestId, String repoUrl, String commitSha) throws IOException {
        String accountId = System.getenv("HARNESS_ACCOUNT_ID");
        String artifactId = project.getArtifactId();

        File metadataFile = new File(metaInfDir, "harness-manifest.properties");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(metadataFile), StandardCharsets.UTF_8)) {
            writer.write("account.id=" + (accountId != null ? accountId : "") + "\n");
            writer.write("manifest.id=" + manifestId + "\n");
            writer.write("repo.url=" + repoUrl + "\n");
            writer.write("artifact.id=" + artifactId + "\n");
            writer.write("commit.sha=" + commitSha + "\n");
        }

        getLog().debug("Wrote manifest metadata to " + metadataFile.getAbsolutePath());
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
