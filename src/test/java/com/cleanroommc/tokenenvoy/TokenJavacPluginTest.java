package com.cleanroommc.tokenenvoy;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenJavacPluginTest {

    @TempDir
    Path projectDir;

    @Test
    void compilesIncrementallyAndCachesOnJava21And25() throws IOException {
        Path root = this.projectDir;
        for (int version : List.of(21, 25)) {
            this.projectDir = root.resolve("java" + version);
            write("settings.gradle", "rootProject.name = 'javac-test'");
            write("build.gradle", """
                    plugins {
                        id 'java'
                        id 'com.cleanroommc.tokenenvoy'
                    }
                    java.toolchain.languageVersion = JavaLanguageVersion.of(%d)
                    tokenEnvoy {
                        set file('tokens.properties')
                        includeClasses '**/Probe.class'
                        main.resourcesOnly = providers.gradleProperty('resourcesOnly').map { it.toBoolean() }.orElse(false)
                    }
                    tasks.register('runProbe', JavaExec) {
                        javaLauncher = javaToolchains.launcherFor(java.toolchain)
                        classpath = sourceSets.main.runtimeClasspath
                        mainClass = 'example.Probe'
                    }
                    """.formatted(version));
            write("src/main/java/example/Probe.java", """
                    package example;
                    @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                    @interface Marker { String value(); }
                    @Marker("@{VERSION}")
                    public class Probe {
                        public static final String VERSION = "@{VERSION}";
                        public static final String DETAIL = "@{DETAIL}";
                        public static class Nested { public static final String VERSION = "@{VERSION}"; }
                        public static boolean matches(String value) {
                            return switch (value) {
                                case "@{VERSION}" -> true;
                                default -> false;
                            };
                        }
                        public static String message(String suffix) { return "@{VERSION}-" + suffix; }
                        public static String block() {
                            return \"""
                                    @{VERSION}
                                    \""";
                        }
                        public static void main(String[] args) {
                            if (!matches(VERSION) || !Consumer.INLINED.equals(VERSION)
                                    || !Nested.VERSION.equals(VERSION)
                                    || !Probe.class.getAnnotation(Marker.class).value().equals(VERSION)
                                    || !message("suffix").equals(VERSION + "-suffix")
                                    || !block().equals(VERSION + "\\n")) {
                                throw new AssertionError("Replacement changed Java semantics");
                            }
                            System.out.println("VERSION=" + VERSION);
                            System.out.println("DETAIL=" + java.util.Base64.getEncoder().encodeToString(
                                    DETAIL.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                        }
                    }
                    """);
            write("src/main/java/example/Consumer.java", """
                    package example;
                    public class Consumer {
                        public static final String INLINED = Probe.VERSION;
                        public static final String HELD = "@{VERSION}";
                    }
                    """);
            write("src/main/java/example/Independent.java", "package example; class Independent { int value() { return 1; } }");
            String detail = "quote\" slash\\ newline\n雪";
            Properties tokens = new Properties();
            tokens.setProperty("VERSION", "one");
            tokens.setProperty("DETAIL", detail);
            try (var output = Files.newOutputStream(this.projectDir.resolve("tokens.properties"))) {
                tokens.store(output, null);
            }

            BuildResult first = runner("runProbe").build();
            assertTrue(List.of(TaskOutcome.SUCCESS, TaskOutcome.FROM_CACHE).contains(first.task(":compileJava").getOutcome()));
            assertNull(first.task(":tokenEnvoyJavaClasses"));
            assertTrue(first.getOutput().contains("VERSION=one"), first.getOutput());
            assertTrue(first.getOutput().contains("DETAIL=" + Base64.getEncoder().encodeToString(detail.getBytes(StandardCharsets.UTF_8))), first.getOutput());
            assertFalse(Files.exists(this.projectDir.resolve("build/tokenEnvoy")));
            assertTrue(Files.readString(this.projectDir.resolve("src/main/java/example/Probe.java")).contains("@{VERSION}"));
            assertTrue(new String(Files.readAllBytes(this.projectDir.resolve("build/classes/java/main/example/Consumer.class")), StandardCharsets.ISO_8859_1).contains("@{VERSION}"));
            byte[] original = Files.readAllBytes(this.projectDir.resolve("build/classes/java/main/example/Probe.class"));
            assertEquals(version + 44, (original[6] & 255) << 8 | original[7] & 255);

            BuildResult unchanged = runner("runProbe").build();
            assertEquals(TaskOutcome.UP_TO_DATE, unchanged.task(":compileJava").getOutcome());
            assertTrue(unchanged.getOutput().contains("Reusing configuration cache"), unchanged.getOutput());

            write("src/main/java/example/Independent.java", "package example; class Independent { int value() { return 2; } }");
            BuildResult incremental = runner("runProbe", "--info").build();
            assertTrue(incremental.getOutput().contains("Incremental compilation of 1 classes"), incremental.getOutput());

            tokens.setProperty("VERSION", "two");
            try (var output = Files.newOutputStream(this.projectDir.resolve("tokens.properties"))) {
                tokens.store(output, null);
            }
            BuildResult changed = runner("runProbe").build();
            assertEquals(TaskOutcome.SUCCESS, changed.task(":compileJava").getOutcome());
            assertTrue(changed.getOutput().contains("VERSION=two"), changed.getOutput());

            BuildResult restored = runner("clean", "runProbe").build();
            assertEquals(TaskOutcome.FROM_CACHE, restored.task(":compileJava").getOutcome());
            assertTrue(restored.getOutput().contains("VERSION=two"), restored.getOutput());

            BuildResult resourcesOnly = runner("runProbe", "-PresourcesOnly=true").build();
            assertTrue(resourcesOnly.getOutput().contains("VERSION=@{VERSION}"), resourcesOnly.getOutput());
            BuildResult enabled = runner("runProbe").build();
            assertTrue(enabled.getOutput().contains("VERSION=two"), enabled.getOutput());

            tokens.remove("VERSION");
            try (var output = Files.newOutputStream(this.projectDir.resolve("tokens.properties"))) {
                tokens.store(output, null);
            }
            BuildResult removed = runner("runProbe").build();
            assertTrue(removed.getOutput().contains("VERSION=@{VERSION}"), removed.getOutput());
        }
    }

    @Test
    void preservesAnnotationProcessorsAndReleaseTargetsOnJava25() throws IOException {
        write("settings.gradle", "rootProject.name = 'processor-test'; include 'processor'");
        write("processor/build.gradle", "plugins { id 'java' }; java.toolchain.languageVersion = JavaLanguageVersion.of(25)");
        write("processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor", "GenerateTokens");
        write("processor/src/main/java/GenerateTokens.java", """
                import javax.annotation.processing.*;
                import javax.lang.model.SourceVersion;
                import javax.lang.model.element.TypeElement;
                import java.util.Set;
                @SupportedAnnotationTypes("*")
                public class GenerateTokens extends AbstractProcessor {
                    private boolean generated;
                    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
                    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment round) {
                        if (!generated && !round.processingOver()) {
                            generated = true;
                            try (var writer = processingEnv.getFiler().createSourceFile("example.Generated").openWriter()) {
                                writer.write("package example; public class Generated { public static final String VALUE = \\\"@{VERSION}\\\"; }");
                            } catch (java.io.IOException e) {
                                throw new java.io.UncheckedIOException(e);
                            }
                        }
                        return false;
                    }
                }
                """);
        write("build.gradle", """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                java.toolchain.languageVersion = JavaLanguageVersion.of(25)
                tasks.withType(JavaCompile).configureEach { options.release = 8 }
                dependencies { annotationProcessor project(':processor') }
                tokenEnvoy { set 'VERSION', 'generated' }
                tasks.register('runProbe', JavaExec) {
                    javaLauncher = javaToolchains.launcherFor(java.toolchain)
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass = 'example.Probe'
                }
                """);
        write("src/main/java/example/Probe.java", """
                package example;
                @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.RUNTIME)
                @interface Marker { String value() default "@{VERSION}"; }
                @Marker
                public class Probe {
                    public static void main(String[] args) {
                        if (!Generated.VALUE.equals("generated")
                                || !Probe.class.getAnnotation(Marker.class).value().equals("generated")) {
                            throw new AssertionError("Generated source or annotation default was not replaced");
                        }
                    }
                }
                """);
        BuildResult result = runner("runProbe").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":runProbe").getOutcome());
        byte[] generated = Files.readAllBytes(this.projectDir.resolve("build/classes/java/main/example/Generated.class"));
        assertEquals(52, (generated[6] & 255) << 8 | generated[7] & 255);
        assertTrue(Files.readString(this.projectDir.resolve("build/generated/sources/annotationProcessor/java/main/example/Generated.java")).contains("@{VERSION}"));
    }

    @Test
    void compilerPatternsMatchGradleClassFilters() {
        List<String> paths = List.of("Tags.class", "Other.class", "example/Tags.class", "example/Tags$Nested.class",
                "example/deep/Tags.class", "example/internal/Tags.class", "example/deep/internal/Tags.class");
        for (String glob : List.of("Tags.class", "example.Tags", "example.Tags.class", "**/Tags.class", "*.class",
                "**.class", "example/*", "example/**", "example/", "**/internal/**", "example/**/Tags.class",
                "**/**/Tags.class", "example/Tag?.class", "example/Tags$Nested.class", "example\\Tags.class")) {
            TokenPathFilter gradle = TokenPathFilter.of(List.of(glob), List.of(), TokenPathFilter.Kind.CLASSES);
            List<Pattern> patterns = TokenJavacArguments.classPatterns(List.of(glob)).stream().map(Pattern::compile).toList();
            for (String path : paths) {
                assertEquals(gradle.accepts(path), patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches()), glob + " against " + path);
            }
        }
    }

    private void write(String path, String contents) throws IOException {
        Path target = this.projectDir.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, contents);
    }

    private GradleRunner runner(String... arguments) {
        List<String> args = new ArrayList<>(Arrays.asList(arguments));
        args.addAll(List.of("--configuration-cache", "--configuration-cache-problems=fail", "--build-cache", "--stacktrace", "--console=plain"));
        return GradleRunner.create().withProjectDir(this.projectDir.toFile()).withPluginClasspath().withArguments(args).forwardOutput();
    }

}
