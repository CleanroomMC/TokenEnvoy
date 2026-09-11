/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy.resource;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class TokenResourcesTest {

    @Test
    void treatsKnownBinariesAsBinary() {
        assertThat(TokenResources.isBinary("assets/textures/x.png")).isTrue();
        assertThat(TokenResources.isBinary("data.bin")).isTrue();
        assertThat(TokenResources.isBinary("sound.OGG")).isTrue();
    }

    @Test
    void treatsTextResourcesAsText() {
        assertThat(TokenResources.isBinary("mcmod.info")).isFalse();
        assertThat(TokenResources.isBinary("pack.mcmeta")).isFalse();
        assertThat(TokenResources.isBinary("assets/lang/en_us.lang")).isFalse();
        assertThat(TokenResources.isBinary("mixins.mod.json")).isFalse();
    }

}
