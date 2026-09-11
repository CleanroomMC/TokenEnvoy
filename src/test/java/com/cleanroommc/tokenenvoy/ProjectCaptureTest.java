/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy;

import static org.assertj.core.api.Assertions.assertThat;
import org.gradle.api.Project;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

class ProjectCaptureTest {

    @Test
    void realizedValuesAreSafe() {
        assertThat(ProjectCapture.diagnose("1.0.0")).isNull();
        assertThat(ProjectCapture.diagnose(12)).isNull();
        assertThat(ProjectCapture.diagnose(null)).isNull();
        assertThat(ProjectCapture.captures(0, "project")).isFalse();
        assertThat(ProjectCapture.isUserCallableProvider("1.0.0")).isFalse();
    }

    @Test
    void reportsExplicitProject() {
        Project project = (Project) Proxy.newProxyInstance(Project.class.getClassLoader(), new Class<?>[] { Project.class }, (proxy, method, args) -> null);
        assertThat(ProjectCapture.captures(0, project)).isTrue();
        assertThat(ProjectCapture.diagnose(project)).isEqualTo("captures Project");
    }

}
