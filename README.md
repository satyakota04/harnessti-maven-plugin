# Harness TI Maven Plugin

[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven Central](https://img.shields.io/badge/maven--central-coming--soon-orange)]()

Maven plugin to embed a lightweight Harness Test Intelligence manifest (`harness-manifest.properties`) in JAR artifacts. Computes a content-addressed manifest of all source files in the repository and records per-JAR class prefixes. Works for single JARs and multiple JARs produced from the same codebase.

## Features

- ✅ **Zero configuration** - works automatically after adding to pom.xml
- ✅ **Git metadata embedding** - repository URL, commit SHA
- ✅ **Source manifest** - content-addressed manifest ID + per-JAR class prefixes for Test Intelligence
- ✅ **Multi-JAR aware** - multiple JARs from one codebase share the same manifest ID
- ✅ **Universal compatibility** - works with jar, shade, assembly, war, spring-boot plugins
- ✅ **Simple properties file** - `harness-manifest.properties` is easy to parse at runtime
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
mvn clean verify
```

That's it! The plugin automatically embeds a `harness-manifest.properties` file in your JAR(s).

Note: The plugin binds to the `verify` phase by default. If you only run `mvn package`, explicitly add the goal or run `mvn verify`. Many repackagers (spring-boot, shade) also work when the plugin runs after the final artifact is produced.

## Verify It Works

```bash
# Check the embedded manifest (lightweight pointer)
unzip -p target/myapp-1.0.jar META-INF/harness-manifest.properties
```

Example output:
```properties
account.id=your-account-id
manifest.id=7f8e9d2c4a1b3f5e6d7c8b9a0f1e2d3c4b5a69787766554433221100ffeeddcc
repo.url=git@github.com:org/repo.git
artifact.id=myapp
commit.sha=3b4a8cd44bd7481eec1869a50eabc4199b23010f
class.prefixes=myapp-1.0.0-SNAPSHOT.jar:com.example
```

## Output

The plugin embeds a lightweight manifest file in each JAR:

**harness-manifest.properties**
```properties
account.id=your-account-id
manifest.id=7f8e9d2c4a1b3f5e6d7c8b9a0f1e2d3c4b5a69787766554433221100ffeeddcc
repo.url=git@github.com:org/repo.git
artifact.id=myapp
commit.sha=3b4a8cd44bd7481eec1869a50eabc4199b23010f
class.prefixes=myapp-1.0.0-SNAPSHOT.jar:com.example,myapp-1.0.0-SNAPSHOT.jar:org.utils
```

- `manifest.id` is a content hash (SHA-256) of all source file checksums in the repository.
- `class.prefixes` lists package roots for classes that belong to **this specific JAR**, prefixed with the versioned jar id (e.g. `myapp-1.0.0-SNAPSHOT.jar:com.example`).

The full source checksums and class-to-source mappings are uploaded to the Harness TI service under the `manifest.id`. They are **not** embedded in the JAR during normal operation.

If the upload fails, the plugin falls back to writing `harness-manifest-data.json` (containing the full checksums and class mappings) alongside `harness-manifest.properties` for later manual upload.

## Reading Metadata at Runtime

```java
Properties manifest = new Properties();
try (InputStream is = getClass().getResourceAsStream("/META-INF/harness-manifest.properties")) {
    manifest.load(is);
}
String manifestId = manifest.getProperty("manifest.id");
String repoUrl = manifest.getProperty("repo.url");
String commitSha = manifest.getProperty("commit.sha");
String artifactId = manifest.getProperty("artifact.id");
String classPrefixes = manifest.getProperty("class.prefixes");
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

### Multiple JARs from a Single Codebase

When a single repository produces multiple JARs (multi-module project, shaded artifacts, multiple maven-jar-plugin executions, etc.):

- All JARs from the **same commit** share the **same `manifest.id`** (the checksums cover the entire repository).
- Each JAR gets its **own `harness-manifest.properties`** with:
  - A unique `artifact.id`
  - `class.prefixes` using the versioned jar id (e.g. `core-lib-1.0.0-SNAPSHOT.jar:com.example`) scoped only to classes that belong to **that JAR**
- The full manifest (checksums + all class mappings) is uploaded **once** under the shared `manifest.id`.

Example: `service-a.jar` and `service-b.jar` built from the same repo at the same commit will have:

`service-a-1.0.0.jar`:
```properties
manifest.id=7f8e9d2c...
artifact.id=service-a
class.prefixes=service-a-1.0.0.jar:com.acme.servicea
```

`service-b-1.0.0.jar`:
```properties
manifest.id=7f8e9d2c...   # same
artifact.id=service-b
class.prefixes=service-b-1.0.0.jar:com.acme.serviceb
```

This allows the Test Intelligence agent to know which classes live in which JAR while still using a single content-addressed manifest for the whole codebase.

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

1. Runs during the `verify` phase (after packaging is complete)
2. Executes `git` commands to get repository URL and commit SHA
3. Runs `git ls-tree -r -t HEAD` across the entire repo to collect all Java source files and blob hashes
4. Computes a `manifest.id` (SHA-256 of all source checksums) for content-addressable storage
5. Uploads the full manifest (checksums + class mappings) to the Harness TI service
6. Writes a lightweight `harness-manifest.properties` into the final JAR containing `manifest.id`, `artifact.id`, and `class.prefixes` for that JAR
7. If upload fails, also writes `harness-manifest-data.json` as a fallback

**Why a lightweight manifest + server upload?** All JARs from the same commit share the same source content. Uploading the full data once under a `manifest.id` and embedding only a small pointer per JAR keeps artifacts small and avoids duplication. Properties files are used because they survive all packaging plugins (shade, spring-boot, assembly, etc.).

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
