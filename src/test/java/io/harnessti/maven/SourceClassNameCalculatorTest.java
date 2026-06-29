package io.harnessti.maven;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class SourceClassNameCalculatorTest {

    @Test
    void testCalculateSourceClassesWithModules() {
        SourceClassNameCalculator calculator = new SourceClassNameCalculator();

        List<GitLsTreeEntry> entries = Arrays.asList(
                new GitLsTreeEntry("abc123", "module1/src/main/java/com/example/Foo.java"),
                new GitLsTreeEntry("def456", "module1/src/main/java/com/example/service/Bar.java"),
                new GitLsTreeEntry("ghi789", "module2/src/main/java/com/other/Baz.java"),
                new GitLsTreeEntry("jkl012", "module1/src/main/java/package-info.java") // Should be excluded
        );

        List<ModuleInfo> modules = Arrays.asList(
                new ModuleInfo("module1", "1.0.0", "module1/src/main/java"),
                new ModuleInfo("module2", "2.0.0", "module2/src/main/java")
        );

        Map<String, String> result = calculator.calculateSourceClassesWithModules(entries, modules);

        assertEquals(3, result.size());
        assertTrue(result.containsKey("module1-1.0.0.jar:com.example.Foo"));
        assertEquals("module1/src/main/java/com/example/Foo.java",
                result.get("module1-1.0.0.jar:com.example.Foo"));

        assertTrue(result.containsKey("module1-1.0.0.jar:com.example.service.Bar"));
        assertTrue(result.containsKey("module2-2.0.0.jar:com.other.Baz"));

        // package-info.java should be excluded
        assertFalse(result.containsKey("module1-1.0.0.jar:package-info"));
    }

    @Test
    void testCalculateSourceClasses_legacy() {
        SourceClassNameCalculator calculator = new SourceClassNameCalculator();

        List<GitLsTreeEntry> entries = Arrays.asList(
                new GitLsTreeEntry("abc123", "src/main/java/com/example/Foo.java"),
                new GitLsTreeEntry("def456", "src/main/java/com/example/Bar.java"),
                new GitLsTreeEntry("ghi789", "src/test/java/com/example/TestFoo.java"),
                new GitLsTreeEntry("jkl012", "README.md") // Not a Java file
        );

        Set<String> sourceRoots = new HashSet<>(Arrays.asList("src/main/java", "src/test/java"));

        Map<String, String> result = calculator.calculateSourceClasses(entries, sourceRoots);

        assertEquals(3, result.size());
        assertTrue(result.containsKey("com.example.Foo"));
        assertTrue(result.containsKey("com.example.Bar"));
        assertTrue(result.containsKey("com.example.TestFoo"));

        assertFalse(result.containsKey("README")); // Non-Java file excluded
    }

    @Test
    void testCalculateSourceClasses_excludesModuleInfo() {
        SourceClassNameCalculator calculator = new SourceClassNameCalculator();

        List<GitLsTreeEntry> entries = Arrays.asList(
                new GitLsTreeEntry("abc123", "src/main/java/com/example/Foo.java"),
                new GitLsTreeEntry("def456", "src/main/java/module-info.java")
        );

        Set<String> sourceRoots = Collections.singleton("src/main/java");

        Map<String, String> result = calculator.calculateSourceClasses(entries, sourceRoots);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("com.example.Foo"));
        assertFalse(result.containsKey("module-info"));
    }

    @Test
    void testCalculateSourceClasses_nestedPackages() {
        SourceClassNameCalculator calculator = new SourceClassNameCalculator();

        List<GitLsTreeEntry> entries = Collections.singletonList(
                new GitLsTreeEntry("abc123", "src/main/java/com/example/deep/nested/package/SomeClass.java")
        );

        Set<String> sourceRoots = Collections.singleton("src/main/java");

        Map<String, String> result = calculator.calculateSourceClasses(entries, sourceRoots);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("com.example.deep.nested.package.SomeClass"));
    }

    @Test
    void testCalculateSourceClassesWithModules_unmatchedFiles() {
        SourceClassNameCalculator calculator = new SourceClassNameCalculator();

        List<GitLsTreeEntry> entries = Arrays.asList(
                new GitLsTreeEntry("abc123", "module1/src/main/java/com/example/Foo.java"),
                new GitLsTreeEntry("def456", "unmatched/dir/Bar.java") // Doesn't match any module
        );

        List<ModuleInfo> modules = Collections.singletonList(
                new ModuleInfo("module1", "1.0.0", "module1/src/main/java")
        );

        Map<String, String> result = calculator.calculateSourceClassesWithModules(entries, modules);

        assertEquals(1, result.size());
        assertTrue(result.containsKey("module1-1.0.0.jar:com.example.Foo"));
        // unmatched/dir/Bar.java should not be in the result
    }
}
