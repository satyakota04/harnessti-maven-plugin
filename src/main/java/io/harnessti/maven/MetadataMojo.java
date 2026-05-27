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

            File baseDir = project.getBasedir();
            GitInfoProvider gitInfoProvider = new GitInfoProvider(baseDir);

            // Compute relative source roots ONCE
            java.util.Set<String> relativeSourceRoots = gitInfoProvider.collectRelativeSourceRoots(sourceRoots);

            // Get Git metadata
            GitInfoProvider.GitInfo gitInfo = gitInfoProvider.getGitInfo(new ArrayList<>(relativeSourceRoots));

            getLog().info("Repository URL: " + gitInfo.getRepositoryUrl());
            getLog().info("Commit SHA: " + gitInfo.getCommitSha());
            getLog().info("Source paths: " + String.join(", ", gitInfo.getSourcePaths()));

            // Run git ls-tree ONCE
            List<GitLsTreeEntry> lsTreeEntries = gitInfoProvider.runLsTree();

            // Derive both maps from the same data
            SourceChecksumCalculator checksumCalculator = new SourceChecksumCalculator();
            Map<String, String> checksums = checksumCalculator.calculateChecksums(lsTreeEntries, relativeSourceRoots);

            SourceClassNameCalculator classNameCalculator = new SourceClassNameCalculator();
            Map<String, String> sourceClasses = classNameCalculator.calculateSourceClasses(lsTreeEntries, relativeSourceRoots);

            getLog().info("Calculated checksums for " + checksums.size() + " source files");
            getLog().info("Calculated class names for " + sourceClasses.size() + " source files");

            // Write all outputs (resolve META-INF dir once)
            File metaInfDir = resolveMetaInfDir();
            writeMetadata(metaInfDir, gitInfo);
            writeChecksums(metaInfDir, checksums);
            writeSourceClasses(metaInfDir, sourceClasses);

            getLog().info("Source metadata embedded successfully");

        } catch (IOException e) {
            throw new MojoExecutionException("Failed to embed source metadata", e);
        }
    }

    private File resolveMetaInfDir() {
        File outputDir = new File(project.getBuild().getOutputDirectory());
        File metaInfDir = new File(outputDir, "META-INF");
        if (!metaInfDir.exists()) {
            metaInfDir.mkdirs();
        }
        return metaInfDir;
    }

    private void writeMetadata(File metaInfDir, GitInfoProvider.GitInfo gitInfo) throws IOException {
        File metadataFile = new File(metaInfDir, "source-metadata.properties");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(metadataFile), StandardCharsets.UTF_8)) {
            writer.write("repository.url=" + gitInfo.getRepositoryUrl() + "\n");
            writer.write("commit.sha=" + gitInfo.getCommitSha() + "\n");
            writer.write("source.paths=" + String.join(",", gitInfo.getSourcePaths()) + "\n");
        }

        getLog().debug("Wrote metadata to " + metadataFile.getAbsolutePath());
    }

    private void writeChecksums(File metaInfDir, Map<String, String> checksums) throws IOException {
        File checksumsFile = new File(metaInfDir, "source-checksums.properties");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(checksumsFile), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : checksums.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
        }

        getLog().debug("Wrote checksums to " + checksumsFile.getAbsolutePath());
    }

    private void writeSourceClasses(File metaInfDir, Map<String, String> sourceClasses) throws IOException {
        File sourceClassesFile = new File(metaInfDir, "source-classes.properties");

        try (Writer writer = new OutputStreamWriter(new FileOutputStream(sourceClassesFile), StandardCharsets.UTF_8)) {
            for (Map.Entry<String, String> entry : sourceClasses.entrySet()) {
                writer.write(entry.getKey() + "=" + entry.getValue() + "\n");
            }
        }

        getLog().debug("Wrote source classes to " + sourceClassesFile.getAbsolutePath());
    }
}
