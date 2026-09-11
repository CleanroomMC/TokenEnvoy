/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

import java.util.List;

class TokenPathFilterTest {

    @Test
    void emptyIncludesAcceptEverything() {
        TokenPathFilter filter = resources(List.of(), List.of());
        assertThat(filter.accepts("resources.json")).isTrue();
        assertThat(filter.accepts("assets/lang/en_us.lang")).isTrue();
    }

    @Test
    void emptyPathIsRejected() {
        assertThat(resources(List.of(), List.of()).accepts("")).isFalse();
        assertThat(resources(List.of(), List.of()).accepts((String) null)).isFalse();
        assertThat(resources(List.of(), List.of()).accepts("   ")).isFalse();
    }

    @Test
    void doesNotApplyGradleDefaultExcludes() {
        TokenPathFilter filter = resources(List.of(), List.of());
        assertThat(filter.accepts("notes~")).isTrue();
        assertThat(filter.accepts(".gitkeep")).isTrue();
    }

    @Test
    void plainFileNameMatchesOnlyRoot() {
        TokenPathFilter filter = resources(List.of("resources.json"), List.of());
        assertThat(filter.accepts("resources.json")).isTrue();
        assertThat(filter.accepts("nested/resources.json")).isFalse();
        assertThat(filter.accepts("mcmod.json")).isFalse();
    }

    @Test
    void doubleStarMatchesFileNameInAnyDirectory() {
        TokenPathFilter filter = resources(List.of("**/resources.json"), List.of());
        assertThat(filter.accepts("resources.json")).isTrue();
        assertThat(filter.accepts("nested/resources.json")).isTrue();
        assertThat(filter.accepts("resources.txt")).isFalse();
    }

    @Test
    void plainClassFileNameMatchesOnlyRoot() {
        TokenPathFilter filter = classes(List.of("Tokens.class"), List.of());
        assertThat(filter.accepts("Tokens.class")).isTrue();
        assertThat(filter.accepts("com/example/Tokens.class")).isFalse();
        assertThat(filter.accepts("com/example/Tokens$Inner.class")).isFalse();
    }

    @Test
    void doubleStarClassFileNameMatchesNestedOutput() {
        TokenPathFilter filter = classes(List.of("**/Tokens.class"), List.of());
        assertThat(filter.accepts("Tokens.class")).isTrue();
        assertThat(filter.accepts("com/example/Tokens.class")).isTrue();
        assertThat(filter.accepts("com/example/Tokens$Inner.class")).isFalse();
        assertThat(filter.accepts("com/example/Other.class")).isFalse();
    }

    @Test
    void exactRelativePathMatchesOnlyThatPath() {
        TokenPathFilter filter = classes(List.of("com/example/Tokens.class"), List.of());
        assertThat(filter.accepts("com/example/Tokens.class")).isTrue();
        assertThat(filter.accepts("Tokens.class")).isFalse();
        assertThat(filter.accepts("other/example/Tokens.class")).isFalse();
    }

    @Test
    void fqcnIsCanonicalizedForClasses() {
        TokenPathFilter dotted = classes(List.of("com.example.Tokens"), List.of());
        assertThat(dotted.accepts("com/example/Tokens.class")).isTrue();
        assertThat(dotted.accepts("com/example/Other.class")).isFalse();
        assertThat(dotted.accepts("Tokens.class")).isFalse();

        TokenPathFilter withSuffix = classes(List.of("com.example.Tokens.class"), List.of());
        assertThat(withSuffix.accepts("com/example/Tokens.class")).isTrue();

        TokenPathFilter simpleName = classes(List.of("Tokens"), List.of());
        assertThat(simpleName.accepts("Tokens")).isTrue();
        assertThat(simpleName.accepts("Tokens.class")).isFalse();
        assertThat(simpleName.accepts("com/example/Tokens.class")).isFalse();
    }

    @Test
    void resourceFqcnIsNotRewritten() {
        TokenPathFilter filter = resources(List.of("com.example.Tokens"), List.of());
        assertThat(filter.accepts("com/example/Tokens.class")).isFalse();
        assertThat(filter.accepts("com.example.Tokens")).isTrue();
    }

    @Test
    void starGlobStaysInOneSegment() {
        TokenPathFilter filter = resources(List.of("*.json"), List.of());
        assertThat(filter.accepts("resources.json")).isTrue();
        assertThat(filter.accepts("assets/resources.json")).isFalse();
    }

    @Test
    void doubleStarGlobCrossesDirectories() {
        TokenPathFilter filter = resources(List.of("**/*.json"), List.of());
        assertThat(filter.accepts("resources.json")).isTrue();
        assertThat(filter.accepts("assets/resources.json")).isTrue();
        assertThat(filter.accepts("resources.txt")).isFalse();
    }

    @Test
    void doubleStarWithoutSlashIsOneSegmentSuffix() {
        TokenPathFilter filter = resources(List.of("**.lang"), List.of());
        assertThat(filter.accepts("en_us.lang")).isTrue();
        assertThat(filter.accepts("assets/lang/en_us.lang")).isFalse();
    }

    @Test
    void questionMarkMatchesOneCharacter() {
        TokenPathFilter filter = resources(List.of("?.txt"), List.of());
        assertThat(filter.accepts("a.txt")).isTrue();
        assertThat(filter.accepts("ab.txt")).isFalse();
        assertThat(filter.accepts("a/b.txt")).isFalse();
    }

    @Test
    void nestedDirectoryGlob() {
        TokenPathFilter filter = resources(List.of("assets/**/*.lang"), List.of());
        assertThat(filter.accepts("assets/lang/en_us.lang")).isTrue();
        assertThat(filter.accepts("assets/en_us.lang")).isTrue();
        assertThat(filter.accepts("data/en_us.lang")).isFalse();
        assertThat(filter.accepts("assets")).isFalse();
    }

    @Test
    void excludesWinOverIncludes() {
        TokenPathFilter filter = resources(List.of("**/*.json", "resources.json"), List.of("skip.json", "secret/**"));
        assertThat(filter.accepts("resources.json")).isTrue();
        assertThat(filter.accepts("data.json")).isTrue();
        assertThat(filter.accepts("nested/data.json")).isTrue();
        assertThat(filter.accepts("skip.json")).isFalse();
        assertThat(filter.accepts("nested/skip.json")).isTrue();
        assertThat(filter.accepts("secret/keep.json")).isFalse();
    }

    @Test
    void doubleStarExcludeMatchesAnyDirectory() {
        TokenPathFilter filter = resources(List.of("**/*.json"), List.of("**/skip.json"));
        assertThat(filter.accepts("resources.json")).isTrue();
        assertThat(filter.accepts("skip.json")).isFalse();
        assertThat(filter.accepts("nested/skip.json")).isFalse();
    }

    @Test
    void excludeWithoutIncludeSkipsOnlyMatches() {
        TokenPathFilter filter = classes(List.of(), List.of("**/internal/**"));
        assertThat(filter.accepts("com/example/Tokens.class")).isTrue();
        assertThat(filter.accepts("com/example/internal/Hidden.class")).isFalse();
    }

    @Test
    void normalizesBackslashesAndDotSlash() {
        TokenPathFilter filter = resources(List.of("assets/**/*.json"), List.of());
        assertThat(filter.accepts("assets\\resources.json")).isTrue();
        assertThat(filter.accepts("./assets/resources.json")).isTrue();
        assertThat(filter.accepts("/assets/resources.json")).isTrue();
    }

    @Test
    void doubleStarAtEndMatchesDescendants() {
        TokenPathFilter filter = resources(List.of("assets/**"), List.of());
        assertThat(filter.accepts("assets")).isTrue();
        assertThat(filter.accepts("assets/x")).isTrue();
        assertThat(filter.accepts("assets/lang/en_us.lang")).isTrue();
        assertThat(filter.accepts("data/x")).isFalse();
    }

    private static TokenPathFilter classes(List<String> includes, List<String> excludes) {
        return TokenPathFilter.of(includes, excludes, TokenPathFilter.Kind.CLASSES);
    }

    private static TokenPathFilter resources(List<String> includes, List<String> excludes) {
        return TokenPathFilter.of(includes, excludes, TokenPathFilter.Kind.RESOURCES);
    }

}
