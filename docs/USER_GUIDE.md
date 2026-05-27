# Harness TI Maven Plugin - User Guide

This guide explains how to use the harnessti-maven-plugin to embed source traceability metadata in your Maven projects.

## What This Plugin Does

The plugin automatically embeds two things in your JAR files:
1. **Git metadata** - repository URL, commit SHA, and source paths
2. **Source file checksums** - Git blob SHA-1 hashes for Java source files
3. **Source class names** - class-to-source path mapping for Java files

This enables Test Intelligence to trace test execution back to specific source files, even in closed-source deployments.

## Installation

### Option 1: Install from Local Build (Current)

If someone gives you the plugin source code:

```bash
cd /path/to/harnessti-maven-plugin
mvn clean install
```

This installs the plugin to your local Maven repository (`~/.m2/repository/`).

### Option 2: Use from Maven Central (Future)

Once published to Maven Central, you won't need to install anything manually.

## Usage

### Single Module Project

Add the plugin to your `pom.xml`:

```xml
<project>
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
</project>
```

Then build normally:

```bash
mvn clean package
```

### Multi-Module Project

Add the plugin to the **parent POM** and it will apply to all modules:

```xml
<!-- parent pom.xml -->
<project>
    <modules>
        <module>module-a</module>
        <module>module-b</module>
    </modules>

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
</project>
```

Build the entire project:

```bash
mvn clean package
```

Each module's JAR will contain its own metadata.

## Verifying It Works

After building, check the JAR contents:

```bash
# List META-INF files
unzip -l target/myapp-1.0.jar | grep META-INF

# View git metadata
unzip -p target/myapp-1.0.jar META-INF/source-metadata.properties

# View checksums
unzip -p target/myapp-1.0.jar META-INF/source-checksums.properties
```

You should see:

**META-INF/source-metadata.properties:**
```properties
repository.url=git@github.com:yourorg/yourrepo.git
commit.sha=abc123...
source.paths=src/main/java,src/test/java
```

**META-INF/source-checksums.properties:**
```properties
src/main/java/com/example/MyClass.java=2e65efe2a145dda7ee51d1741299f848e5bf752e
src/main/java/com/example/util/Helper.java=4a8f6b9b8f7b9582a4d676d89a18f00a39fce8c4
...
```

**META-INF/source-classes.properties:**
```properties
com.example.MyClass=src/main/java/com/example/MyClass.java
com.example.util.Helper=src/main/java/com/example/util/Helper.java
...
```

## Requirements

- **Maven**: 3.6.3 or higher
- **Java**: 8 or higher
- **Git**: Must be installed and your project must be a Git repository

If Git is not installed or the project is not a Git repo, the plugin will fail with a clear error message.

## Works With All Packaging Types

The plugin automatically works with:

- ✅ **maven-jar-plugin** - standard JAR packaging
- ✅ **maven-shade-plugin** - uber/fat JARs
- ✅ **maven-assembly-plugin** - custom assemblies
- ✅ **maven-war-plugin** - WAR files
- ✅ **spring-boot-maven-plugin** - Spring Boot executable JARs

**No additional configuration needed** for any of these!

## When Does It Run?

The plugin runs during the `process-classes` phase, which happens:

```
compile → process-classes → test → package
           ↑
    Plugin runs here
```

This means:
1. Your code is already compiled
2. Files are written to `target/classes/META-INF/`
3. When packaging happens, those files are automatically included in the JAR

## Common Use Cases

### CI/CD Pipeline

Add to your build command:

```bash
mvn clean package
# Plugin runs automatically, no special flags needed
```

### Docker Builds

In your Dockerfile:

```dockerfile
FROM maven:3.8-openjdk-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package
# Plugin embeds metadata during this build
```

### GitHub Actions / Jenkins / GitLab CI

Just use your normal Maven build command:

```yaml
# .github/workflows/build.yml
- name: Build with Maven
  run: mvn clean package
  # Plugin runs automatically
```

## Troubleshooting

### "Git command failed"

**Problem**: Git is not installed or not in PATH

**Solution**: 
```bash
# Check Git is installed
git --version

# If not, install Git:
# Ubuntu/Debian: sudo apt install git
# macOS: brew install git
# Windows: Download from git-scm.com
```

### "Not a git repository"

**Problem**: Your project folder is not a Git repository

**Solution**:
```bash
cd /path/to/your/project
git init
git remote add origin <your-repo-url>
git add .
git commit -m "Initial commit"
```

### Plugin not running

**Problem**: Plugin configuration is incorrect

**Solution**: Check that:
1. Plugin is in `<build><plugins>` section, not `<pluginManagement>`
2. Execution configuration includes `<goals><goal>embed-metadata</goal></goals>`
3. Version is correct: `1.0.0-SNAPSHOT`

### Metadata files missing from JAR

**Problem**: Metadata files not in the final JAR

**Solution**: This shouldn't happen, but if it does:
1. Check `target/classes/META-INF/` - files should be there after compile
2. If using maven-shade-plugin, ensure you're not excluding META-INF
3. Run with debug: `mvn clean package -X` and check for plugin execution

## Skipping the Plugin

To temporarily disable the plugin:

```bash
# Skip specific execution
mvn package -Dskip.harness.plugin=true
```

Or add to your Mojo parameters:

```xml
<plugin>
    <groupId>io.harnessti</groupId>
    <artifactId>harnessti-maven-plugin</artifactId>
    <configuration>
        <skip>true</skip>
    </configuration>
</plugin>
```

Note: The current version doesn't implement `<skip>` yet, but it's easy to add if needed.

## What Gets Embedded?

### Git Metadata (source-metadata.properties)

| Property | Description | Example |
|----------|-------------|---------|
| `repository.url` | Git remote origin URL | `git@github.com:org/repo.git` |
| `commit.sha` | Current commit hash | `3b4a8cd44bd7481eec1869a50eabc4199b23010f` |
| `source.paths` | Comma-separated source directories | `src/main/java,src/test/java` |

### Source Checksums (source-checksums.properties)

Format: `relative/path/to/File.java=gitBlobSha1`

Example:
```properties
src/main/java/com/example/App.java=2e65efe2a145dda7ee51d1741299f848e5bf752e
src/main/java/com/example/util/Helper.java=4a8f6b9b8f7b9582a4d676d89a18f00a39fce8c4
```

Only `.java` files from configured source roots are included, using `git ls-tree -r -t HEAD` blob hashes.

### Source Classes (source-classes.properties)

Format: `fully.qualified.ClassName=relative/path/to/Class.java`

Example:
```properties
com.example.App=src/main/java/com/example/App.java
com.example.util.Helper=src/main/java/com/example/util/Helper.java
```

## Reading Metadata at Runtime

To read the embedded metadata in your application:

```java
import java.io.InputStream;
import java.util.Properties;

public class MetadataReader {
    
    public static Properties getSourceMetadata() throws Exception {
        Properties props = new Properties();
        try (InputStream is = MetadataReader.class
                .getResourceAsStream("/META-INF/source-metadata.properties")) {
            props.load(is);
        }
        return props;
    }
    
    public static Properties getSourceChecksums() throws Exception {
        Properties props = new Properties();
        try (InputStream is = MetadataReader.class
                .getResourceAsStream("/META-INF/source-checksums.properties")) {
            props.load(is);
        }
        return props;
    }
    
    public static void main(String[] args) throws Exception {
        Properties metadata = getSourceMetadata();
        System.out.println("Repository: " + metadata.getProperty("repository.url"));
        System.out.println("Commit: " + metadata.getProperty("commit.sha"));
        System.out.println("Source paths: " + metadata.getProperty("source.paths"));
        
        Properties checksums = getSourceChecksums();
        System.out.println("\nSource files: " + checksums.size());
        checksums.forEach((file, checksum) -> 
            System.out.println("  " + file + " = " + checksum)
        );
    }
}
```

## Best Practices

### 1. Add to Parent POM

For multi-module projects, configure once in the parent:

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

All child modules automatically inherit this configuration.

### 2. Use in CI/CD Only

If you only need metadata in production builds:

```xml
<profiles>
    <profile>
        <id>ci</id>
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
    </profile>
</profiles>
```

Then in CI: `mvn clean package -Pci`

### 3. Check Metadata in Tests

Verify metadata is present:

```java
@Test
public void shouldHaveSourceMetadata() throws Exception {
    InputStream is = getClass()
        .getResourceAsStream("/META-INF/source-metadata.properties");
    assertNotNull("Source metadata not found", is);
    
    Properties props = new Properties();
    props.load(is);
    
    assertNotNull("Repository URL missing", props.getProperty("repository.url"));
    assertNotNull("Commit SHA missing", props.getProperty("commit.sha"));
}
```

## Examples

### Example 1: Simple Spring Boot App

```xml
<project>
    <groupId>com.example</groupId>
    <artifactId>myapp</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
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
</project>
```

### Example 2: Multi-Module Microservices

```xml
<!-- parent pom.xml -->
<project>
    <modules>
        <module>user-service</module>
        <module>order-service</module>
        <module>payment-service</module>
    </modules>

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
</project>
```

Each service JAR gets its own metadata automatically.

### Example 3: With Shade Plugin

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
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-shade-plugin</artifactId>
            <version>3.5.0</version>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals>
                        <goal>shade</goal>
                    </goals>
                    <configuration>
                        <transformers>
                            <transformer implementation="org.apache.maven.plugins.shade.resource.ManifestResourceTransformer">
                                <mainClass>com.example.Main</mainClass>
                            </transformer>
                        </transformers>
                    </configuration>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

No special shade configuration needed - metadata files survive automatically!

## Support

For issues or questions:
- Check the troubleshooting section above
- Review `MAVEN_PLUGIN_CONCEPTS.md` for how plugins work
- Contact your Harness Test Intelligence team

## Related Documentation

- [README.md](README.md) - Plugin overview and features
- [MAVEN_PLUGIN_CONCEPTS.md](MAVEN_PLUGIN_CONCEPTS.md) - How Maven plugins work
- [CI-22473](https://harness.atlassian.net/browse/CI-22473) - Original Jira ticket
