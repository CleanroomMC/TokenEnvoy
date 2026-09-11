package com.cleanroommc.tokenenvoy;

import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.process.CommandLineArgumentProvider;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

public abstract class TokenJavacArguments implements CommandLineArgumentProvider {

    @Input
    public abstract MapProperty<String, String> getTokens();

    @Input
    public abstract ListProperty<String> getIncludes();

    @Input
    public abstract ListProperty<String> getExcludes();

    @Input
    public abstract Property<Boolean> getResourcesOnly();

    @Override
    public Iterable<String> asArguments() {
        if (getResourcesOnly().get() || getTokens().get().isEmpty()) {
            return List.of();
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            var tokens = Tokens.ordered(getTokens().get());
            output.writeInt(tokens.size());
            for (var token : tokens) {
                output.writeUTF(token.getKey());
                output.writeUTF(token.getValue());
            }
            for (var patterns : List.of(getIncludes().get(), getExcludes().get())) {
                List<String> expressions = classPatterns(patterns);
                output.writeInt(expressions.size());
                for (String expression : expressions) {
                    output.writeUTF(expression);
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        return List.of("-Xplugin:TokenEnvoy " + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()));
    }

    // Javac runs without Gradle's PatternSet, pass equivalent regular expressions
    static List<String> classPatterns(List<String> patterns) {
        List<String> expressions = new ArrayList<>();
        for (String pattern : TokenPathFilter.canonicalize(patterns, TokenPathFilter.Kind.CLASSES)) {
            if (pattern.endsWith("/")) {
                pattern += "**";
            }
            List<String> segments = new ArrayList<>();
            for (String segment : pattern.split("/")) {
                if (!segment.isEmpty() && !(segment.equals("**") && !segments.isEmpty() && segments.getLast().equals("**"))) {
                    segments.add(segment);
                }
            }
            StringBuilder expression = new StringBuilder();
            for (int i = 0; i < segments.size(); i++) {
                String segment = segments.get(i);
                if (segment.equals("**")) {
                    expression.append(i == segments.size() - 1 ? ".*" : "(?:[^/]+/)*");
                    continue;
                }
                for (char character : segment.toCharArray()) {
                    expression.append(switch (character) {
                        case '*' -> "[^/]*";
                        case '?' -> "[^/]";
                        default -> Pattern.quote(String.valueOf(character));
                    });
                }
                if (i + 1 == segments.size() - 1 && segments.get(i + 1).equals("**")) {
                    expression.append("(?:/.*)?");
                    break;
                }
                if (i < segments.size() - 1) {
                    expression.append('/');
                }
            }
            expressions.add(expression.toString());
        }
        return expressions;
    }

}
