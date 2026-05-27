# Harness TI Maven Plugin

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/badge/maven--central-coming--soon-orange)]()

Maven plugin to embed Git metadata and source file checksums in JAR artifacts for Test Intelligence source traceability.

## Features

- ✅ **Zero configuration** - works automatically after adding to pom.xml
- ✅ **Git metadata embedding** - repository URL, commit SHA, source paths
- ✅ **Source checksums** - Git blob SHA-1 hashes for Java source files
- ✅ **Source class names** - class-to-source path mapping for Java files
- ✅ **Universal compatibility** - works with jar, shade, assembly, war plugins
- ✅ **Simple properties files** - easy to parse at runtime
- ✅ **Java 8+** and **Maven 3.6.3+** support

## Quick Start

Add the plugin to your `pom.xml`:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>io.harnessti</groupId>
            <artifactId>harnessti-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <goals>
                        <goal>embed-metadata</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Build your project:

```bash
mvn clean package
```

That's it! The plugin automatically embeds metadata in your JAR.

## Verify It Works

```bash
# Check metadata
unzip -p target/myapp-1.0.jar META-INF/source-metadata.properties

# Check checksums
unzip -p target/myapp-1.0.jar META-INF/source-checksums.properties
```

## Output

The plugin creates three files in `META-INF/`:

**source-metadata.properties**
```properties
repository.url=git@github.com:org/repo.git
commit.sha=3b4a8cd44bd7481eec1869a50eabc4199b23010f
source.paths=src/main/java,src/test/java
```

**source-checksums.properties**
```properties
src/main/java/com/example/MyClass.java=2e65efe2a145dda7ee51d1741299f848e5bf752e
src/main/java/com/example/util/Helper.java=4a8f6b9b8f7b9582a4d676d89a18f00a39fce8c4
```

**source-classes.properties**
```properties
com.example.MyClass=src/main/java/com/example/MyClass.java
com.example.util.Helper=src/main/java/com/example/util/Helper.java
```

## Reading Metadata at Runtime

```java
Properties metadata = new Properties();
try (InputStream is = getClass().getResourceAsStream("/META-INF/source-metadata.properties")) {
    metadata.load(is);
}
String repoUrl = metadata.getProperty("repository.url");
String commitSha = metadata.getProperty("commit.sha");
```

## Multi-Module Projects

Add once in the parent pom - applies to all modules automatically:

```xml
<!-- parent pom.xml -->
<build>
    <plugins>
        <plugin>
            <groupId>io.harnessti</groupId>
            <artifactId>harnessti-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <goals>
                        <goal>embed-metadata</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

## Requirements

- **Maven**: 3.6.3 or higher
- **Java**: 8 or higher  
- **Git**: Must be installed and project must be a Git repository

## Documentation

- 📖 [User Guide](docs/USER_GUIDE.md) - Detailed usage, examples, troubleshooting
- 🛠️ [Maven Plugin Concepts](docs/MAVEN_PLUGIN_CONCEPTS.md) - How Maven plugins work
- 📝 [Contributing](CONTRIBUTING.md) - Development setup and guidelines
- 📋 [Changelog](CHANGELOG.md) - Version history

## How It Works

1. Runs during `process-classes` phase (after compilation, before packaging)
2. Executes `git` commands to get repository URL and commit SHA
3. Runs `git ls-tree -r -t HEAD` and maps Java source files to blob hashes
4. Maps Java source paths to fully-qualified class names
5. Writes properties files to `target/classes/META-INF/`
6. Files automatically included in JAR by packaging plugins

**Why properties files?** They work with ALL packaging plugins without configuration. Manifest attributes often get overwritten by shade/assembly plugins.

## Compatible With

- ✅ maven-jar-plugin
- ✅ maven-shade-plugin  
- ✅ maven-assembly-plugin
- ✅ maven-war-plugin
- ✅ spring-boot-maven-plugin
- ✅ All other packaging plugins

## Building from Source

```bash
git clone https://github.com/harness/harnessti-maven-plugin.git
cd harnessti-maven-plugin
mvn clean install
```

## Support

- 🐛 [Report Issues](https://github.com/harness/harnessti-maven-plugin/issues)
- 💬 [Discussions](https://github.com/harness/harnessti-maven-plugin/discussions)
- 📖 [Harness Documentation](https://docs.harness.io)

## License

Apache License 2.0 - see [LICENSE](LICENSE) file for details.

## Related

- [CI-22473](https://harness.atlassian.net/browse/CI-22473) - Maven/Gradle Plugin for TI Metadata
- [CI-22474](https://harness.atlassian.net/browse/CI-22474) - Agent reads manifest & extracts metadata

---

Made with ❤️ by [Harness](https://harness.io) for Test Intelligence
