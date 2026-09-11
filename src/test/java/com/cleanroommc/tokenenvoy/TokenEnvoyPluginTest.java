/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import static org.assertj.core.api.Assertions.assertThat;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class TokenEnvoyPluginTest {

    @TempDir
    Path projectDir;

    @BeforeEach
    void setup() throws IOException {
        Files.writeString(this.projectDir.resolve("settings.gradle"), "rootProject.name = 'token-envoy-test'");
    }

    @Test
    void warnsWhenTokenProviderCapturesProject() {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', providers.provider { project.version }
                    set 'SAFE', project.version
                    set 'PROP', providers.gradleProperty('mod_version')
                }
                """
        );
        String output = runner("help", "--warn").build().getOutput();
        assertThat(output.contains("[Token Envoy] Token 'VERSION'")).as(output).isTrue();
        assertThat(output.contains("configuration cache")).as(output).isTrue();
        assertThat(output.contains("Token 'SAFE'")).as(output).isFalse();
        assertThat(output.contains("Token 'PROP'")).as(output).isFalse();
    }

    @Test
    void pluginApplies() {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                """
        );
        assertThat(runner("help").build().task(":help").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
    }

    @Test
    void classesConsumersUseJavaCompileDirectly() throws IOException {
        writeBuild(
                """
                        plugins {
                            id 'java'
                            id 'com.cleanroommc.tokenenvoy'
                        }
                        tasks.register('consumeClassesDirectory') {
                            inputs.dir(sourceSets.main.java.classesDirectory)
                            doLast { }
                        }
                        tasks.register('consumeClassesDirs') {
                            inputs.files(sourceSets.main.output.classesDirs)
                            doLast { }
                        }
                        """
        );
        writeFile(
                "src/main/java/com/example/Example.java",
                """
                        package com.example;

                        public class Example {
                        }
                        """
        );
        assertThat(runner("consumeClassesDirectory").build().task(":compileJava")).as("classesDirectory consumers ran without Java compilation").isNotNull();
        assertThat(runner("consumeClassesDirs").build().task(":compileJava")).as("output.classesDirs consumers ran without Java compilation").isNotNull();
    }

    @Test
    void replacesTokensInClassesAndResourcesWithoutTouchingSources() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                version = '1.2.3'
                tokenEnvoy {
                    set 'VERSION', project.version
                    main {
                        set 'MOD_ID', 'examplemod'
                    }
                }
                """
        );
        Path source = writeJava(
                """
                package example;
                
                public final class Reference {
                    public static final String VERSION = "@{VERSION}";
                    public static String id() {
                        return "id=@{MOD_ID}";
                    }
                }
                """
        );
        Path resource = writeResource(
                "mcmod.info",
                """
                {
                  "version": "@{VERSION}",
                  "modid": "@{MOD_ID}"
                }
                """
        );

        BuildResult result = runner("classes").build();
        assertThat(result.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":processResources").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);

        byte[] classBytes = Files.readAllBytes(classFile("example/Reference.class"));
        String classText = new String(classBytes, StandardCharsets.ISO_8859_1);
        assertThat(classText.contains("1.2.3")).as(classText).isTrue();
        assertThat(classText.contains("id=examplemod")).as(classText).isTrue();
        assertThat(classText.contains("@{VERSION}")).as(classText).isFalse();
        assertThat(classText.contains("@{MOD_ID}")).as(classText).isFalse();

        assertThat(Files.readString(resourceFile("mcmod.info"))).isEqualTo(
                """
                {
                  "version": "1.2.3",
                  "modid": "examplemod"
                }
                """
        );

        assertThat(Files.readString(source).contains("@{VERSION}")).isTrue();
        assertThat(Files.readString(source).contains("@{MOD_ID}")).isTrue();
        assertThat(Files.readString(resource).contains("@{VERSION}")).isTrue();
    }

    @Test
    void sourceSetTokensOverrideGlobalsAndPropertyFilesInterpolate() throws IOException {
        Files.writeString(
                this.projectDir.resolve("gradle.properties"),
                """
                mod_version=9.9.9
                mod_id=fromprops
                """
        );
        Files.writeString(
                this.projectDir.resolve("tokens.properties"),
                """
                VERSION=${mod_version}
                MOD_ID=${mod_id}
                """
        );
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', 'global'
                    set 'EXTRA', 'everywhere'
                    main {
                        set file('tokens.properties')
                    }
                    test {
                        set 'VERSION', 'test-only'
                    }
                }
                """
        );
        writeJava(
                """
                package example;
                public final class MainTokens {
                    public static final String VERSION = "@{VERSION}";
                    public static final String EXTRA = "@{EXTRA}";
                    public static final String ID = "@{MOD_ID}";
                }
                """
        );
        writeTestJava(
                """
                package example;
                public final class TestTokens {
                    public static final String VERSION = "@{VERSION}";
                    public static final String EXTRA = "@{EXTRA}";
                }
                """
        );

        runner("classes", "testClasses").build();

        String main = classText("example/MainTokens.class");
        assertThat(main.contains("9.9.9")).as(main).isTrue();
        assertThat(main.contains("everywhere")).as(main).isTrue();
        assertThat(main.contains("fromprops")).as(main).isTrue();
        assertThat(main.contains("global")).as(main).isFalse();

        String test = testClassText("example/TestTokens.class");
        assertThat(test.contains("test-only")).as(test).isTrue();
        assertThat(test.contains("everywhere")).as(test).isTrue();
        assertThat(test.contains("9.9.9")).as(test).isFalse();
    }

    @Test
    void resourcesOnlySkipsClassReplacement() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', '1.0.0'
                    main {
                        resourcesOnly = true
                    }
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Held {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );
        writeResource("version.txt", "v=@{VERSION}\n");

        BuildResult result = runner("classes").build();

        String classText = classText("example/Held.class");
        assertThat(classText.contains("@{VERSION}")).as(classText).isTrue();
        assertThat(classText.contains("1.0.0")).as(classText).isFalse();
        assertThat(Files.readString(resourceFile("version.txt"))).isEqualTo("v=1.0.0\n");
    }

    @Test
    void filtersLimitClassAndResourceReplacement() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', 'yes'
                    classes {
                        include '**/Replaced.class', 'example.Also'
                        exclude 'example/Also.class'
                    }
                    resources {
                        include 'keep.txt', '**/*.json'
                        exclude '**/skip.json'
                    }
                    main {
                        includeClasses 'example/Extra.class'
                    }
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Replaced {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Also {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Held {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Extra {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );
        writeResource("keep.txt", "v=@{VERSION}\n");
        writeResource("held.txt", "v=@{VERSION}\n");
        writeResource("data.json", "{\"v\":\"@{VERSION}\"}\n");
        writeResource("skip.json", "{\"v\":\"@{VERSION}\"}\n");
        writeResource("nested/deep.json", "{\"v\":\"@{VERSION}\"}\n");

        runner("classes").build();

        assertThat(classText("example/Replaced.class").contains("yes")).isTrue();
        assertThat(classText("example/Replaced.class").contains("@{VERSION}")).isFalse();
        assertThat(classText("example/Extra.class").contains("yes")).isTrue();
        assertThat(classText("example/Also.class").contains("@{VERSION}")).isTrue();
        assertThat(classText("example/Held.class").contains("@{VERSION}")).isTrue();

        assertThat(Files.readString(resourceFile("keep.txt"))).isEqualTo("v=yes\n");
        assertThat(Files.readString(resourceFile("held.txt"))).isEqualTo("v=@{VERSION}\n");
        assertThat(Files.readString(resourceFile("data.json"))).isEqualTo("{\"v\":\"yes\"}\n");
        assertThat(Files.readString(resourceFile("skip.json"))).isEqualTo("{\"v\":\"@{VERSION}\"}\n");
        assertThat(Files.readString(resourceFile("nested/deep.json"))).isEqualTo("{\"v\":\"yes\"}\n");
    }

    @Test
    void filterChangeRecompilesClasses() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', 'yes'
                    includeClasses '**/Replaced.class'
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Replaced {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Held {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );

        runner("classes").build();
        assertThat(classText("example/Replaced.class").contains("yes")).isTrue();
        assertThat(classText("example/Held.class").contains("@{VERSION}")).isTrue();

        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', 'yes'
                    includeClasses '**/Held.class'
                }
                """
        );

        BuildResult second = runner("classes").build();
        assertThat(second.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(classText("example/Replaced.class").contains("@{VERSION}")).isTrue();
        assertThat(classText("example/Held.class").contains("yes")).isTrue();
    }

    @Test
    void configurationCacheWithFilters() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', 'cc'
                    includeClasses '**/Kept.class'
                    includeResources 'keep.txt', '**/*.json'
                    excludeResources '**/skip.json'
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Kept {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Held {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );
        writeResource("keep.txt", "v=@{VERSION}\n");
        writeResource("skip.json", "{\"v\":\"@{VERSION}\"}\n");

        BuildResult first = ccRunner("classes").build();
        assertThat(first.getOutput().contains("Configuration cache entry stored")).as(first.getOutput()).isTrue();
        assertThat(first.getOutput().contains("Configuration cache problems")).as(first.getOutput()).isFalse();
        assertThat(classText("example/Kept.class").contains("cc")).isTrue();
        assertThat(classText("example/Held.class").contains("@{VERSION}")).isTrue();
        assertThat(Files.readString(resourceFile("keep.txt"))).isEqualTo("v=cc\n");
        assertThat(Files.readString(resourceFile("skip.json"))).isEqualTo("{\"v\":\"@{VERSION}\"}\n");

        BuildResult second = ccRunner("classes").build();
        assertThat(second.getOutput().contains("Reusing configuration cache")).as(second.getOutput()).isTrue();
        assertThat(second.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(second.task(":processResources").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    @Test
    void leavesBinaryResourcesUntouched() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', 'replaced'
                }
                """
        );
        byte[] png = new byte[] { (byte) 0x89, 'P', 'N', 'G', '@', '{', 'V', 'E', 'R', 'S', 'I', 'O', 'N', '}' };
        Path image = this.projectDir.resolve("src/main/resources/icon.png");
        Files.createDirectories(image.getParent());
        Files.write(image, png);

        runner("processResources").build();
        assertThat(Arrays.toString(Files.readAllBytes(resourceFile("icon.png")))).isEqualTo(Arrays.toString(png));
    }

    @Test
    void tokenChangeRecompilesClasses() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', '1.0.0'
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Version {
                    public static final String VALUE = "@{VERSION}";
                }
                """
        );

        runner("classes").build();
        assertThat(classText("example/Version.class").contains("1.0.0")).isTrue();

        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', '2.0.0'
                }
                """
        );

        BuildResult second = runner("classes").build();
        assertThat(second.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        String rewritten = classText("example/Version.class");
        assertThat(rewritten.contains("2.0.0")).as(rewritten).isTrue();
        assertThat(rewritten.contains("1.0.0")).as(rewritten).isFalse();
        assertThat(rewritten.contains("@{VERSION}")).as(rewritten).isFalse();
    }

    @Test
    void configurationCacheReusesEntry() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                version = '1.4.2'
                tokenEnvoy {
                    set 'VERSION', project.version
                    main {
                        set file('tokens.properties')
                    }
                }
                """
        );
        Files.writeString(this.projectDir.resolve("tokens.properties"), "MOD_ID=cached\n");
        writeJava(
                """
                package example;
                public final class Cc {
                    public static final String VERSION = "@{VERSION}";
                    public static final String ID = "@{MOD_ID}";
                }
                """
        );
        writeResource("desc.txt", "@{VERSION}/@{MOD_ID}\n");

        BuildResult first = ccRunner("classes").build();
        assertThat(first.getOutput().contains("Configuration cache entry stored")).as(first.getOutput()).isTrue();
        assertThat(first.getOutput().contains("Configuration cache problems")).as(first.getOutput()).isFalse();
        assertThat(classText("example/Cc.class").contains("1.4.2")).isTrue();
        assertThat(Files.readString(resourceFile("desc.txt"))).isEqualTo("1.4.2/cached\n");

        BuildResult second = ccRunner("classes").build();
        assertThat(second.getOutput().contains("Reusing configuration cache")).as(second.getOutput()).isTrue();
        assertThat(second.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
        assertThat(second.task(":processResources").getOutcome()).isEqualTo(TaskOutcome.UP_TO_DATE);
    }

    @Test
    void configurationCacheWithResourcesOnly() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set 'VERSION', 'res'
                    main {
                        resourcesOnly = true
                    }
                }
                """
        );
        writeJava(
                """
                package example;
                public final class Held {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );
        writeResource("version.txt", "v=@{VERSION}\n");

        BuildResult first = ccRunner("classes").build();
        assertThat(first.getOutput().contains("Configuration cache entry stored")).as(first.getOutput()).isTrue();
        assertThat(classText("example/Held.class").contains("@{VERSION}")).isTrue();
        assertThat(Files.readString(resourceFile("version.txt"))).isEqualTo("v=res\n");

        BuildResult second = ccRunner("classes").build();
        assertThat(second.getOutput().contains("Reusing configuration cache")).as(second.getOutput()).isTrue();
        assertThat(Files.readString(resourceFile("version.txt"))).isEqualTo("v=res\n");
    }

    @Test
    void configurationCacheInvalidatesWhenPropertyFileChanges() throws IOException {
        writeBuild(
                """
                plugins {
                    id 'java'
                    id 'com.cleanroommc.tokenenvoy'
                }
                tokenEnvoy {
                    set file('tokens.properties')
                }
                """
        );
        Files.writeString(this.projectDir.resolve("tokens.properties"), "VERSION=one\n");
        writeJava(
                """
                package example;
                public final class FileCc {
                    public static final String VERSION = "@{VERSION}";
                }
                """
        );

        assertThat(ccRunner("classes").build().getOutput().contains("Configuration cache entry stored")).isTrue();
        assertThat(classText("example/FileCc.class").contains("one")).isTrue();

        Files.writeString(this.projectDir.resolve("tokens.properties"), "VERSION=two\n");
        BuildResult afterChange = ccRunner("classes").build();
        assertThat(
                afterChange.getOutput().contains("Reusing configuration cache") ||
                        afterChange.getOutput().contains("cannot be reused because file 'tokens.properties'")
        )
                .as(afterChange.getOutput())
                .isTrue();
        assertThat(afterChange.task(":compileJava").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        String rewritten = classText("example/FileCc.class");
        assertThat(rewritten.contains("two")).as(rewritten).isTrue();
        assertThat(rewritten.contains("one")).as(rewritten).isFalse();
    }

    private void writeBuild(String contents) {
        try {
            Files.writeString(this.projectDir.resolve("build.gradle"), contents);
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    private Path writeFile(String relative, String contents) throws IOException {
        Path file = this.projectDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    private Path writeJava(String contents) throws IOException {
        Path file = this.projectDir.resolve("src/main/java/example/" + className(contents) + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    private Path writeTestJava(String contents) throws IOException {
        Path file = this.projectDir.resolve("src/test/java/example/" + className(contents) + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    private Path writeResource(String relative, String contents) throws IOException {
        Path file = this.projectDir.resolve("src/main/resources").resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, contents);
        return file;
    }

    private static String className(String source) {
        int start = source.indexOf("class ") + "class ".length();
        int end = start;
        while (end < source.length() && Character.isJavaIdentifierPart(source.charAt(end))) {
            end++;
        }
        return source.substring(start, end);
    }

    private Path classFile(String relative) {
        return this.projectDir.resolve("build/classes/java/main").resolve(relative);
    }

    private Path resourceFile(String relative) {
        return this.projectDir.resolve("build/resources/main").resolve(relative);
    }

    private String classText(String relative) throws IOException {
        return new String(Files.readAllBytes(classFile(relative)), StandardCharsets.ISO_8859_1);
    }

    private String testClassText(String relative) throws IOException {
        return new String(Files.readAllBytes(this.projectDir.resolve("build/classes/java/test").resolve(relative)), StandardCharsets.ISO_8859_1);
    }

    private GradleRunner runner(String... args) {
        List<String> allArgs = new ArrayList<>(Arrays.asList(args));
        allArgs.add("--stacktrace");
        allArgs.add("--console=plain");
        return GradleRunner.create().withProjectDir(this.projectDir.toFile()).withPluginClasspath().withArguments(allArgs).forwardOutput();
    }

    private GradleRunner ccRunner(String... args) {
        List<String> allArgs = new ArrayList<>(Arrays.asList(args));
        allArgs.add("--configuration-cache");
        allArgs.add("--configuration-cache-problems=fail");
        return runner(allArgs.toArray(String[]::new));
    }

}
