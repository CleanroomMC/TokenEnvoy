/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import static org.assertj.core.api.Assertions.assertThat;
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

class TokenJavacPluginTest {

    @TempDir
    Path projectDir;

    @Test
    void compilesIncrementallyAndCachesOnJava21And25() throws IOException {
        Path root = this.projectDir;
        for (int version : List.of(21, 25)) {
            this.projectDir = root.resolve("java" + version);
            // TestKit's own build cache outlives the run, so a repeat run would load compileJava instead of compiling incrementally
            write("settings.gradle", "rootProject.name = 'javac-test'\nbuildCache { local { directory = file('build-cache') } }");
            write(
                    "build.gradle",
                    """
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
                    """.formatted(
                            version
                    )
            );
            write(
                    "src/main/java/example/Probe.java",
                    """
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
                    """
            );
            write(
                    "src/main/java/example/Consumer.java",
                    """
                    package example;
                    public class Consumer {
                        public static final String INLINED = Probe.VERSION;
                        public static final String HELD = "@{VERSION}";
                    }
                    """
            );
            write("src/main/java/example/Independent.java", "package example; class Independent { int value() { return 1; } }");
            String detail = "quote\" slash\\ newline\n雪";
            Properties tokens = new Properties();
            tokens.setProperty("VERSION", "one");
            tokens.setProperty("DETAIL", detail);
            try (var output = Files.newOutputStream(this.projectDir.resolve("tokens.properties"))) {
                tokens.store(output, null);
            }

            BuildResult first = runner("runProbe").build();
            assertThat(first.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(first.task(":tokenEnvoyJavaClasses")).isNull();
            assertThat(first.getOutput().contains("VERSION=one")).as(first.getOutput()).isTrue();
            assertThat(first.getOutput().contains("DETAIL=" + Base64.getEncoder().encodeToString(detail.getBytes(StandardCharsets.UTF_8))))
                    .as(first.getOutput())
                    .isTrue();
            assertThat(Files.exists(this.projectDir.resolve("build/tokenEnvoy"))).isFalse();
            assertThat(Files.readString(this.projectDir.resolve("src/main/java/example/Probe.java")).contains("@{VERSION}")).isTrue();
            assertThat(
                    new String(
                            Files.readAllBytes(this.projectDir.resolve("build/classes/java/main/example/Consumer.class")),
                            StandardCharsets.ISO_8859_1
                    ).contains("@{VERSION}")
            ).isTrue();
            byte[] original = Files.readAllBytes(this.projectDir.resolve("build/classes/java/main/example/Probe.class"));
            assertThat((original[6] & 255) << 8 | original[7] & 255).isEqualTo(version + 44);

            BuildResult unchanged = runner("runProbe").build();
            assertThat(unchanged.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
            assertThat(unchanged.getOutput().contains("Reusing configuration cache")).as(unchanged.getOutput()).isTrue();

            write("src/main/java/example/Independent.java", "package example; class Independent { int value() { return 2; } }");
            BuildResult incremental = runner("runProbe", "--info").build();
            assertThat(incremental.getOutput().contains("Incremental compilation of 1 classes")).as(incremental.getOutput()).isTrue();

            tokens.setProperty("VERSION", "two");
            try (var output = Files.newOutputStream(this.projectDir.resolve("tokens.properties"))) {
                tokens.store(output, null);
            }
            BuildResult changed = runner("runProbe").build();
            assertThat(changed.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
            assertThat(changed.getOutput().contains("VERSION=two")).as(changed.getOutput()).isTrue();

            BuildResult restored = runner("clean", "runProbe").build();
            assertThat(restored.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.FROM_CACHE);
            assertThat(restored.getOutput().contains("VERSION=two")).as(restored.getOutput()).isTrue();

            BuildResult resourcesOnly = runner("runProbe", "-PresourcesOnly=true").build();
            assertThat(resourcesOnly.getOutput().contains("VERSION=@{VERSION}")).as(resourcesOnly.getOutput()).isTrue();
            BuildResult enabled = runner("runProbe").build();
            assertThat(enabled.getOutput().contains("VERSION=two")).as(enabled.getOutput()).isTrue();

            tokens.remove("VERSION");
            try (var output = Files.newOutputStream(this.projectDir.resolve("tokens.properties"))) {
                tokens.store(output, null);
            }
            BuildResult removed = runner("runProbe").build();
            assertThat(removed.getOutput().contains("VERSION=@{VERSION}")).as(removed.getOutput()).isTrue();
        }
    }

    @Test
    void replacesTokensOnJava8() throws IOException {
        write("settings.gradle", "rootProject.name = 'java8-test'");
        write(
                "build.gradle",
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                java.toolchain.languageVersion = JavaLanguageVersion.of(8)
                tokenEnvoy { set 'VERSION', 'eight' }
                tasks.register('runProbe', JavaExec) {
                    javaLauncher = javaToolchains.launcherFor(java.toolchain)
                    classpath = sourceSets.main.runtimeClasspath
                    mainClass = 'example.Probe'
                }
                """
        );
        write(
                "src/main/java/example/Probe.java",
                """
                package example;
                public class Probe {
                    public static final String VERSION = "@{VERSION}";
                    public static void main(String[] args) {
                        if (!Consumer.INLINED.equals(VERSION)) {
                            throw new AssertionError("Replacement changed Java semantics");
                        }
                        System.out.println("VERSION=" + VERSION);
                    }
                }
                """
        );
        write("src/main/java/example/Consumer.java", "package example; public class Consumer { public static final String INLINED = Probe.VERSION; }");

        BuildResult result = runner("runProbe").build();
        assertThat(result.getOutput()).contains("VERSION=eight");
        byte[] probe = Files.readAllBytes(this.projectDir.resolve("build/classes/java/main/example/Probe.class"));
        assertThat((probe[6] & 255) << 8 | probe[7] & 255).isEqualTo(52);
    }

    @Test
    void preservesAnnotationProcessorsAndReleaseTargetsOnJava25() throws IOException {
        write("settings.gradle", "rootProject.name = 'processor-test'; include 'processor'");
        write("processor/build.gradle", "plugins { id 'java' }; java.toolchain.languageVersion = JavaLanguageVersion.of(25)");
        write("processor/src/main/resources/META-INF/services/javax.annotation.processing.Processor", "GenerateTokens");
        write(
                "processor/src/main/java/GenerateTokens.java",
                """
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
                """
        );
        write(
                "build.gradle",
                """
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
                """
        );
        write(
                "src/main/java/example/Probe.java",
                """
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
                """
        );
        BuildResult result = runner("runProbe").build();
        assertThat(result.task(":runProbe").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        byte[] generated = Files.readAllBytes(this.projectDir.resolve("build/classes/java/main/example/Generated.class"));
        assertThat((generated[6] & 255) << 8 | generated[7] & 255).isEqualTo(52);
        assertThat(
                Files.readString(this.projectDir.resolve("build/generated/sources/annotationProcessor/java/main/example/Generated.java")).contains("@{VERSION}")
        ).isTrue();
    }

    @Test
    void compilerPatternsMatchGradleClassFilters() {
        List<String> paths = List.of(
                "Tags.class",
                "Other.class",
                "example/Tags.class",
                "example/Tags$Nested.class",
                "example/deep/Tags.class",
                "example/internal/Tags.class",
                "example/deep/internal/Tags.class"
        );
        for (String glob : List.of(
                "Tags.class",
                "example.Tags",
                "example.Tags.class",
                "**/Tags.class",
                "*.class",
                "**.class",
                "example/*",
                "example/**",
                "example/",
                "**/internal/**",
                "example/**/Tags.class",
                "**/**/Tags.class",
                "example/Tag?.class",
                "example/Tags$Nested.class",
                "example\\Tags.class"
        )) {
            TokenPathFilter gradle = TokenPathFilter.of(List.of(glob), List.of(), TokenPathFilter.Kind.CLASSES);
            List<Pattern> patterns = TokenJavacArguments.classPatterns(List.of(glob)).stream().map(Pattern::compile).toList();
            for (String path : paths) {
                assertThat(patterns.stream().anyMatch(pattern -> pattern.matcher(path).matches()))
                        .as(glob + " against " + path)
                        .isEqualTo(gradle.accepts(path));
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
