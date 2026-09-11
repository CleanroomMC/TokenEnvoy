/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import com.cleanroommc.tokenenvoy.resource.TokenResourceAction;
import com.cleanroommc.tokenenvoy.task.ReplaceClassTokens;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.Directory;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.SourceDirectorySet;
import org.gradle.api.plugins.ExtensionAware;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.Copy;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.compile.AbstractCompile;
import org.gradle.api.tasks.compile.JavaCompile;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URI;

public abstract class TokenEnvoyPlugin implements Plugin<Project> {

    public static final String EXTENSION_NAME = "tokenEnvoy";
    public static final String TASK_GROUP = "token envoy";

    @Override
    public void apply(Project project) {
        TokenEnvoyExtension extension = project.getExtensions().create(EXTENSION_NAME, TokenEnvoyExtension.class);

        project.getPluginManager().withPlugin("java-base", plugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            sourceSets.all(sourceSet -> {
                TokenEnvoySourceSetSpec spec = extension.getSourceSets().maybeCreate(sourceSet.getName());
                addNestedExtension(extension, spec);
                hookResources(project, sourceSet, extension, spec);
            });
        });

        project.getPluginManager().withPlugin("java", plugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            sourceSets.configureEach(sourceSet -> hookJava(project, sourceSet, extension));
        });
        addForLanguage(project, extension, "groovy");
        addForLanguage(project, extension, "scala");
        addForLanguage(project, extension, "org.jetbrains.kotlin.jvm", "kotlin");
    }

    private static void addForLanguage(Project project, TokenEnvoyExtension ext, String language) {
        addForLanguage(project, ext, language, language);
    }

    private static void addForLanguage(Project project, TokenEnvoyExtension ext, String pluginName, String language) {
        project.getPluginManager().withPlugin(pluginName, plugin -> {
            SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
            sourceSets.all(sourceSet -> hookClasses(project, sourceSet, ext, language));
        });
    }

    private static void addNestedExtension(TokenEnvoyExtension extension, TokenEnvoySourceSetSpec spec) {
        var extensions = ((ExtensionAware) extension).getExtensions();
        if (extensions.findByName(spec.getName()) == null) {
            extensions.add(TokenEnvoySourceSetSpec.class, spec.getName(), spec);
        }
    }

    private static void hookResources(Project project, SourceSet sourceSet, TokenEnvoyExtension extension, TokenEnvoySourceSetSpec spec) {
        MapProperty<String, String> tokens = mergedTokens(project, extension, spec);
        ListProperty<String> includes = mergePatterns(project, extension.getResources().getIncludes(), spec.getResources().getIncludes());
        ListProperty<String> excludes = mergePatterns(project, extension.getResources().getExcludes(), spec.getResources().getExcludes());
        project.getTasks().named(sourceSet.getProcessResourcesTaskName(), Copy.class, copy -> {
            copy.getInputs().property("tokenEnvoyTokens", tokens);
            copy.getInputs().property("tokenEnvoyResourceIncludes", includes);
            copy.getInputs().property("tokenEnvoyResourceExcludes", excludes);
            copy.filesMatching("**/*", new TokenResourceAction(tokens, includes, excludes));
        });
    }

    private static void hookClasses(Project project, SourceSet sourceSet, TokenEnvoyExtension extension, String language) {
        SourceDirectorySet sources = publishedSources(sourceSet, language);
        if (sources == null) {
            return;
        }
        DirectoryProperty published = sources.getDestinationDirectory();
        String compileName = sourceSet.getCompileTaskName(language);
        if (!project.getTasks().getNames().contains(compileName)) {
            return;
        }
        TokenEnvoySourceSetSpec spec = extension.getSourceSets().maybeCreate(sourceSet.getName());
        MapProperty<String, String> tokens = mergedTokens(project, extension, spec);
        String replaceName = sourceSet.getTaskName("tokenEnvoy", capitalize(language) + "Classes");
        if (project.getTasks().getNames().contains(replaceName)) {
            return;
        }

        Provider<Directory> raw = project.getLayout().getBuildDirectory().dir("tokenEnvoy/raw-classes/" + language + "/" + sourceSet.getName());

        TaskProvider<Task> compile = project.getTasks().named(compileName);
        Provider<Directory> compileDestination = compile.flatMap(TokenEnvoyPlugin::destinationDirectory);

        var resourcesOnly = spec.getResourcesOnly();
        TaskProvider<ReplaceClassTokens> replace = project.getTasks().register(replaceName, ReplaceClassTokens.class, task -> {
            task.setGroup(TASK_GROUP);
            task.setDescription("Replaces tokens in compiled " + language + " classes of the '" + sourceSet.getName() + "' source set.");
            task.getTokens().set(tokens);
            task.getIncludes().addAll(extension.getClasses().getIncludes());
            task.getIncludes().addAll(spec.getClasses().getIncludes());
            task.getExcludes().addAll(extension.getClasses().getExcludes());
            task.getExcludes().addAll(spec.getClasses().getExcludes());
            task.getOutputDirectory().set(published);
            task.getClassFiles().from(compileDestination);
            task.onlyIf("tokenEnvoy resourcesOnly is false", t -> !resourcesOnly.get());
            task.dependsOn(compile);
        });

        compile.configure(task -> destinationDirectory(task).set(spec.getResourcesOnly().flatMap(only -> only ? published : raw)));

        sources.compiledBy(replace, ReplaceClassTokens::getOutputDirectory);
        ((ConfigurableFileCollection) sourceSet.getOutput().getClassesDirs()).builtBy(replace);
        sourceSet.compiledBy(replace);
        project.getTasks().named(sourceSet.getClassesTaskName(), classes -> classes.dependsOn(replace));
    }

    private static void hookJava(Project project, SourceSet sourceSet, TokenEnvoyExtension extension) {
        TokenEnvoySourceSetSpec spec = extension.getSourceSets().maybeCreate(sourceSet.getName());
        TokenJavacArguments arguments = project.getObjects().newInstance(TokenJavacArguments.class);
        arguments.getTokens().set(mergedTokens(project, extension, spec));
        arguments.getIncludes().set(mergePatterns(project, extension.getClasses().getIncludes(), spec.getClasses().getIncludes()));
        arguments.getExcludes().set(mergePatterns(project, extension.getClasses().getExcludes(), spec.getClasses().getExcludes()));
        arguments.getResourcesOnly().set(spec.getResourcesOnly());
        File pluginJar = new File(URI.create(TokenEnvoyPlugin.class.getProtectionDomain().getCodeSource().getLocation().toExternalForm()));
        project.getTasks().named(sourceSet.getCompileJavaTaskName(), JavaCompile.class, compile -> {
            compile.getOptions().getCompilerArgumentProviders().add(arguments);
            compile.getOptions().setAnnotationProcessorPath(project.files(compile.getOptions().getAnnotationProcessorPath(), pluginJar));
            compile.getOptions().setFork(true);
            compile.getOptions().getForkOptions().getJvmArgs().add("--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED");
        });
    }

    private static MapProperty<String, String> mergedTokens(Project project, TokenEnvoyExtension extension, TokenEnvoySourceSetSpec spec) {
        MapProperty<String, String> merged = project.getObjects().mapProperty(String.class, String.class);
        merged.putAll(extension.getTokens());
        merged.putAll(spec.getTokens());
        return merged;
    }

    private static ListProperty<String> mergePatterns(Project project, ListProperty<String> globals, ListProperty<String> locals) {
        ListProperty<String> merged = project.getObjects().listProperty(String.class);
        merged.addAll(globals);
        merged.addAll(locals);
        return merged;
    }

    private static SourceDirectorySet publishedSources(SourceSet sourceSet, String language) {
        return sourceSet.getExtensions().findByName(language) instanceof SourceDirectorySet sources ? sources : null;
    }

    private static DirectoryProperty destinationDirectory(Task task) {
        if (task instanceof AbstractCompile compileTask) {
            return compileTask.getDestinationDirectory();
        }
        // Kotlin...
        try {
            Method method = task.getClass().getMethod("getDestinationDirectory");
            Object value = method.invoke(task);
            if (value instanceof DirectoryProperty directory) {
                return directory;
            }
            throw new GradleException(
                    "Unable to get destination directory. " + task.getClass() + "::getDestinationDirectory returned type " + value.getClass()
            );
        } catch (Throwable t) {
            throw new GradleException("Unable to get destination directory", t);
        }
    }

    private static String capitalize(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

}
