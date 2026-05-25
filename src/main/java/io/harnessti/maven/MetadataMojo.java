package io.harnessti.maven;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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

            // Calculate relative paths for source roots
            File baseDir = project.getBasedir();
            List<String> relativeSourcePaths = new ArrayList<>();
            for (String sourceRoot : sourceRoots) {
                File sourceDir = new File(sourceRoot);
                if (sourceDir.exists()) {
                    String relativePath = baseDir.toPath().relativize(sourceDir.toPath()).toString();
                    relativeSourcePaths.add(relativePath);
                }
            }

            // Get Git info
            GitInfoProvider gitInfoProvider = new GitInfoProvider(baseDir);
            GitInfoProvider.GitInfo gitInfo = gitInfoProvider.getGitInfo(relativeSourcePaths);

            getLog().info("Repository URL: " + gitInfo.getRepositoryUrl());
            getLog().info("Commit SHA: " + gitInfo.getCommitSha());
            getLog().info("Source paths: " + String.join(", ", gitInfo.getSourcePaths()));

            // Calculate checksums
            SourceChecksumCalculator checksumCalculator = new SourceChecksumCalculator(baseDir);
            Map<String, String> checksums = checksumCalculator.calculateChecksums(sourceRoots);

            getLog().info("Calculated checksums for " + checksums.size() + " source files");

            // Write metadata file (simpler than fighting with manifest)
            writeMetadata(gitInfo);

            // Write checksums file
            writeChecksums(checksums);

            getLog().info("Source metadata embedded successfully");

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to embed source metadata", e);
        }
    }

    private void writeMetadata(GitInfoProvider.GitInfo gitInfo) throws IOException {
        File outputDir = new File(project.getBuild().getOutputDirectory());
        File metaInfDir = new File(outputDir, "META-INF");
        if (!metaInfDir.exists()) {
            metaInfDir.mkdirs();
        }

        File metadataFile = new File(metaInfDir, "source-metadata.properties");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(metadataFile), StandardCharsets.UTF_8)) {
            writer.write("repository.url=" + gitInfo.getRepositoryUrl() + "\n");
            writer.write("commit.sha=" + gitInfo.getCommitSha() + "\n");
            writer.write("source.paths=" + String.join(",", gitInfo.getSourcePaths()) + "\n");
        }

        getLog().debug("Wrote metadata to " + metadataFile.getAbsolutePath());
    }

    private void writeChecksums(Map<String, String> checksums) throws IOException {
        File outputDir = new File(project.getBuild().getOutputDirectory());
        File metaInfDir = new File(outputDir, "META-INF");
        if (!metaInfDir.exists()) {
            metaInfDir.mkdirs();
        }

        File checksumsFile = new File(metaInfDir, "source-checksums.properties");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(checksumsFile), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : checksums.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
        }

        getLog().debug("Wrote checksums to " + checksumsFile.getAbsolutePath());
    }
}
