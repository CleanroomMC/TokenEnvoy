/*
 * Copyright (c) 2026 CleanroomMC contributors
 * SPDX-License-Identifier: LGPL-3.0-only
 */

package com.cleanroommc.tokenenvoy.resource;

import java.util.Locale;
import java.util.Set;

public final class TokenResources {

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "class",
            "jar",
            "zip",
            "gz",
            "tgz",
            "xz",
            "7z",
            "rar",
            "bz2",
            "png",
            "jpg",
            "jpeg",
            "gif",
            "webp",
            "bmp",
            "ico",
            "tif",
            "tiff",
            "ogg",
            "mp3",
            "wav",
            "flac",
            "aac",
            "m4a",
            "ttf",
            "otf",
            "woff",
            "woff2",
            "eot",
            "nbt",
            "dat",
            "bin",
            "dll",
            "so",
            "dylib",
            "exe",
            "o",
            "pdf"
    );

    private TokenResources() {}

    public static boolean isBinary(String path) {
        if (path == null || path.isEmpty()) {
            return false;
        }
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = path.substring(separator + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return false;
        }
        return BINARY_EXTENSIONS.contains(name.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

}
