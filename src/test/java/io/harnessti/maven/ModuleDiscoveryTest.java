package io.harnessti.maven;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleDiscoveryTest {

    @Test
    void testDiscoverModules_withParentBlock(@TempDir Path tempDir) throws IOException {
        // Create a pom.xml with a parent block
        String pomContent =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project>\n" +
                "    <parent>\n" +
                "        <artifactId>parent-artifact</artifactId>\n" +
                "        <version>1.0.0</version>\n" +
                "    </parent>\n" +
                "    <artifactId>child-artifact</artifactId>\n" +
                "    <version>2.0.0</version>\n" +
                "</project>";

        Path pomPath = tempDir.resolve("pom.xml");
        Files.write(pomPath, pomContent.getBytes());

        // Create src/main/java directory
        Files.createDirectories(tempDir.resolve("src/main/java"));

        List<ModuleInfo> modules = ModuleDiscovery.discoverModules(tempDir.toFile());

        assertEquals(1, modules.size());
        assertEquals("child-artifact", modules.get(0).getArtifactId());
        assertEquals("2.0.0", modules.get(0).getVersion());
    }

    @Test
    void testDiscoverModules_excludesTargetDirectory(@TempDir Path tempDir) throws IOException {
        // Create main pom.xml
        String mainPom =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project>\n" +
                "    <artifactId>main-artifact</artifactId>\n" +
                "    <version>1.0.0</version>\n" +
                "</project>";

        Files.write(tempDir.resolve("pom.xml"), mainPom.getBytes());
        Files.createDirectories(tempDir.resolve("src/main/java"));

        // Create a pom.xml in target directory (should be excluded)
        Path targetDir = tempDir.resolve("target");
        Files.createDirectories(targetDir);
        String targetPom =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project>\n" +
                "    <artifactId>target-artifact</artifactId>\n" +
                "    <version>1.0.0</version>\n" +
                "</project>";

        Files.write(targetDir.resolve("pom.xml"), targetPom.getBytes());

        List<ModuleInfo> modules = ModuleDiscovery.discoverModules(tempDir.toFile());

        assertEquals(1, modules.size());
        assertEquals("main-artifact", modules.get(0).getArtifactId());
    }

    @Test
    void testDiscoverModules_excludesDotGitDirectory(@TempDir Path tempDir) throws IOException {
        // Create main pom.xml
        String mainPom =
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<project>\n" +
                "    <artifactId>main-artifact</artifactId>\n" +
                "</project>";

        Files.write(tempDir.resolve("pom.xml"), mainPom.getBytes());
        Files.createDirectories(tempDir.resolve("src/main/java"));

        // Create a pom.xml in .git directory (should be excluded)
        Path gitDir = tempDir.resolve(".git");
        Files.createDirectories(gitDir);
        Files.write(gitDir.resolve("pom.xml"), mainPom.getBytes());

        List<ModuleInfo> modules = ModuleDiscovery.discoverModules(tempDir.toFile());

        assertEquals(1, modules.size());
    }

    @Test
    void testFindModuleForFile() throws IOException {
        ModuleInfo module1 = new ModuleInfo("module1", "1.0.0", "module1/src/main/java");
        ModuleInfo module2 = new ModuleInfo("module2", "1.0.0", "module2/src/main/java");
        List<ModuleInfo> modules = List.of(module1, module2);

        ModuleInfo found = ModuleDiscovery.findModuleForFile("module1/src/main/java/com/example/Foo.java", modules);
        assertNotNull(found);
        assertEquals("module1", found.getArtifactId());

        found = ModuleDiscovery.findModuleForFile("module2/src/main/java/com/example/Bar.java", modules);
        assertNotNull(found);
        assertEquals("module2", found.getArtifactId());

        found = ModuleDiscovery.findModuleForFile("unmatched/path/File.java", modules);
        assertNull(found);
    }
}
