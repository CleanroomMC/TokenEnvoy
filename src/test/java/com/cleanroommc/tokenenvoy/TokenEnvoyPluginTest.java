package com.cleanroommc.tokenenvoy;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertTrue(output.contains("[Token Envoy] Token 'VERSION'"), output);
        assertTrue(output.contains("configuration cache"), output);
        assertFalse(output.contains("Token 'SAFE'"), output);
        assertFalse(output.contains("Token 'PROP'"), output);
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
        assertEquals(TaskOutcome.SUCCESS, runner("help").build().task(":help").getOutcome());
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
        writeFile("src/main/java/com/example/Example.java",
                """
                        package com.example;

                        public class Example {
                        }
                        """);
        assertNotNull(runner("consumeClassesDirectory").build().task(":compileJava"),
                "classesDirectory consumers ran without Java compilation");
        assertNotNull(runner("consumeClassesDirs").build().task(":compileJava"),
                "output.classesDirs consumers ran without Java compilation");
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
        Path resource = writeResource("mcmod.info",
                """
                {
                  "version": "@{VERSION}",
                  "modid": "@{MOD_ID}"
                }
                """
        );

        BuildResult result = runner("classes").build();
        assertEquals(TaskOutcome.SUCCESS, result.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.SUCCESS, result.task(":processResources").getOutcome());

        byte[] classBytes = Files.readAllBytes(classFile("example/Reference.class"));
        String classText = new String(classBytes, StandardCharsets.ISO_8859_1);
        assertTrue(classText.contains("1.2.3"), classText);
        assertTrue(classText.contains("id=examplemod"), classText);
        assertFalse(classText.contains("@{VERSION}"), classText);
        assertFalse(classText.contains("@{MOD_ID}"), classText);

        assertEquals(
                """
                {
                  "version": "1.2.3",
                  "modid": "examplemod"
                }
                """,
                Files.readString(resourceFile("mcmod.info"))
        );

        assertTrue(Files.readString(source).contains("@{VERSION}"));
        assertTrue(Files.readString(source).contains("@{MOD_ID}"));
        assertTrue(Files.readString(resource).contains("@{VERSION}"));
    }

    @Test
    void sourceSetTokensOverrideGlobalsAndPropertyFilesInterpolate() throws IOException {
        Files.writeString(this.projectDir.resolve("gradle.properties"),
                """
                mod_version=9.9.9
                mod_id=fromprops
                """
        );
        Files.writeString(this.projectDir.resolve("tokens.properties"),
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
        assertTrue(main.contains("9.9.9"), main);
        assertTrue(main.contains("everywhere"), main);
        assertTrue(main.contains("fromprops"), main);
        assertFalse(main.contains("global"), main);

        String test = testClassText("example/TestTokens.class");
        assertTrue(test.contains("test-only"), test);
        assertTrue(test.contains("everywhere"), test);
        assertFalse(test.contains("9.9.9"), test);
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
        assertTrue(classText.contains("@{VERSION}"), classText);
        assertFalse(classText.contains("1.0.0"), classText);
        assertEquals("v=1.0.0\n", Files.readString(resourceFile("version.txt")));
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

        assertTrue(classText("example/Replaced.class").contains("yes"));
        assertFalse(classText("example/Replaced.class").contains("@{VERSION}"));
        assertTrue(classText("example/Extra.class").contains("yes"));
        assertTrue(classText("example/Also.class").contains("@{VERSION}"));
        assertTrue(classText("example/Held.class").contains("@{VERSION}"));

        assertEquals("v=yes\n", Files.readString(resourceFile("keep.txt")));
        assertEquals("v=@{VERSION}\n", Files.readString(resourceFile("held.txt")));
        assertEquals("{\"v\":\"yes\"}\n", Files.readString(resourceFile("data.json")));
        assertEquals("{\"v\":\"@{VERSION}\"}\n", Files.readString(resourceFile("skip.json")));
        assertEquals("{\"v\":\"yes\"}\n", Files.readString(resourceFile("nested/deep.json")));
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
        assertTrue(classText("example/Replaced.class").contains("yes"));
        assertTrue(classText("example/Held.class").contains("@{VERSION}"));

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
        assertEquals(TaskOutcome.SUCCESS, second.task(":compileJava").getOutcome());
        assertTrue(classText("example/Replaced.class").contains("@{VERSION}"));
        assertTrue(classText("example/Held.class").contains("yes"));
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
        assertTrue(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());
        assertFalse(first.getOutput().contains("Configuration cache problems"), first.getOutput());
        assertTrue(classText("example/Kept.class").contains("cc"));
        assertTrue(classText("example/Held.class").contains("@{VERSION}"));
        assertEquals("v=cc\n", Files.readString(resourceFile("keep.txt")));
        assertEquals("{\"v\":\"@{VERSION}\"}\n", Files.readString(resourceFile("skip.json")));

        BuildResult second = ccRunner("classes").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":processResources").getOutcome());
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
        assertEquals(Arrays.toString(png), Arrays.toString(Files.readAllBytes(resourceFile("icon.png"))));
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
        assertTrue(classText("example/Version.class").contains("1.0.0"));

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
        assertEquals(TaskOutcome.SUCCESS, second.task(":compileJava").getOutcome());
        String rewritten = classText("example/Version.class");
        assertTrue(rewritten.contains("2.0.0"), rewritten);
        assertFalse(rewritten.contains("1.0.0"), rewritten);
        assertFalse(rewritten.contains("@{VERSION}"), rewritten);
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
        assertTrue(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());
        assertFalse(first.getOutput().contains("Configuration cache problems"), first.getOutput());
        assertTrue(classText("example/Cc.class").contains("1.4.2"));
        assertEquals("1.4.2/cached\n", Files.readString(resourceFile("desc.txt")));

        BuildResult second = ccRunner("classes").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":compileJava").getOutcome());
        assertEquals(TaskOutcome.UP_TO_DATE, second.task(":processResources").getOutcome());
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
        assertTrue(first.getOutput().contains("Configuration cache entry stored"), first.getOutput());
        assertTrue(classText("example/Held.class").contains("@{VERSION}"));
        assertEquals("v=res\n", Files.readString(resourceFile("version.txt")));

        BuildResult second = ccRunner("classes").build();
        assertTrue(second.getOutput().contains("Reusing configuration cache"), second.getOutput());
        assertEquals("v=res\n", Files.readString(resourceFile("version.txt")));
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

        assertTrue(ccRunner("classes").build().getOutput().contains("Configuration cache entry stored"));
        assertTrue(classText("example/FileCc.class").contains("one"));

        Files.writeString(this.projectDir.resolve("tokens.properties"), "VERSION=two\n");
        BuildResult afterChange = ccRunner("classes").build();
        assertTrue(afterChange.getOutput().contains("Reusing configuration cache") ||
                        afterChange.getOutput().contains("cannot be reused because file 'tokens.properties'"), afterChange.getOutput());
        assertEquals(TaskOutcome.SUCCESS, afterChange.task(":compileJava").getOutcome());
        String rewritten = classText("example/FileCc.class");
        assertTrue(rewritten.contains("two"), rewritten);
        assertFalse(rewritten.contains("one"), rewritten);
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
        return GradleRunner.create()
                .withProjectDir(this.projectDir.toFile())
                .withPluginClasspath()
                .withArguments(allArgs)
                .forwardOutput();
    }

    private GradleRunner ccRunner(String... args) {
        List<String> allArgs = new ArrayList<>(Arrays.asList(args));
        allArgs.add("--configuration-cache");
        allArgs.add("--configuration-cache-problems=fail");
        return runner(allArgs.toArray(String[]::new));
    }

}
