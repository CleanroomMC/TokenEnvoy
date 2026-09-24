# Token Envoy

Gradle plugin that replaces tokens in **string literals** during compilation and **resources** (`processResources`).
Source files are never rewritten.

## Apply

From the [Gradle Plugin Portal](https://plugins.gradle.org/plugin/com.cleanroommc.tokenenvoy):

```groovy
plugins {
    id 'java'
    id 'com.cleanroommc.tokenenvoy' version '1.1.0'
}
```

Or from [CleanroomMC's maven](https://maven.cleanroommc.com):

```groovy
// settings.gradle
pluginManagement {
    repositories {
        maven {
            url = 'https://maven.cleanroommc.com'
        }
        gradlePluginPortal()
    }
}
```

## DSL

```groovy
tokenEnvoy {
    // Global: applies to every source set
    set 'VERSION', project.version

    main { // Per-source set: only for the `main` source set in this case
        set 'MOD_ID', project.findProperty('mod_id')
        set file('tokens.properties') // NAME=value properties file
        // resourcesOnly = true // default false. This would skip class replacement

        classes {
            include '**/Tokens.class', 'com.example.Reference'
            exclude '**/internal/**'
        }
        resources {
            include 'mcmod.info', '**/*.json'
            exclude '**/skip.json'
        }
    }
}
```

In classes and resources the marker is always `@{NAME}`:

```java
public static final String VERSION = "@{VERSION}";
```

```json
{ "version": "@{VERSION}" }
```

The DSL and property files use the bare name only (`VERSION`, not `@{VERSION}`).

Do not pass `provider { project.version }`.
That captures `Project` and breaks the configuration cache.
Token Envoy warns if you do.
Prefer a realized value or a Gradle-managed provider:

```groovy
set 'VERSION', project.version
set 'VERSION', providers.gradleProperty('mod_version')
```

### Properties

```properties
# tags.properties
VERSION=${mod_version}
MOD_ID=${mod_id}
```

Keys are token names. Values may use `${gradleProperty}` from `gradle.properties` / `-P`.
Source set `set` calls override globals of the same name.

### Targets

| Target              | How                                                                                                                                                                                  |
|---------------------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Java                | A javac plugin replaces string literals, including text blocks and annotation values, before constant folding and inlining. `compileJava` writes directly to its normal destination. |
| Groovy/Scala/Kotlin | Compile writes raw classes, then `tokenEnvoy<Language>Classes` replaces string constants and annotation values with ASM.                                                             |
| Resources           | `processResources` filters `@{NAME}` in text files. Known binaries (png, ogg, jar, …) are copied as-is.                                                                              |

`resourcesOnly = true` on a source set replaces tokens only in that source set's resources.

Java compilation uses a forked javac process and works with a JDK 8 or newer compiler.
- JDK 8, 21 and 25 are tested.
- On JDK 9 and newer, the plugin adds the module export needed to update javac's literal trees. It does not change the selected toolchain or bytecode target.

Token and filter changes trigger recompilation. Ordinary source edits remain incremental.

### File Filters

`classes` and `resources` choose which files receive replacements.
Files that do not match are still compiled or copied and their `@{NAME}` markers stay in place.

For Java, class patterns select top-level classes by package and class name, such as `com/example/Reference.class`

Selecting a top-level class also selects its nested, local, and anonymous classes.
Nested classes cannot be filtered separately.
Replaced constants can be inlined into other classes, including classes outside the filter.
These semantics differ from filtering individual compiled class files.

Patterns are Gradle Ant-style (`*`, `**`, `?`), relative to the class or resource output root.

- A name without `/` or `**` matches only at that root (`mcmod.info`, `Tags.class`)
- Use `**/Tags.class` to match that file name in any directory
- Paths (`com/example/Reference.class`) match that relative path
- Class patterns also accept a fully-qualified name (`com.example.Tokens`)
- Empty includes mean every file; any matching exclude wins
- Global filters are union'd with the source set's filters

```groovy
tokenEnvoy {
    includeClasses '**/Tags.class'
    includeResources 'mcmod.info', '**/*.json'
    excludeResources '**/skip.json'
}
```

Use `sourceSets.*.output`/`classes` task as the classes input.

## Building

The build applies [Cleanroom Conventions](https://github.com/CleanroomMC/Conventions), whose plugin jar needs a Java 25 Gradle daemon.

The published plugin still targets Java 21. Its javac plugin is a Multi-Release class, built with a JDK 8 toolchain at the jar root and with Java 21 under `META-INF/versions/21`.
