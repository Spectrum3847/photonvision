/*
 * Copyright (C) Photon Vision.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package org.photonvision.common.hardware.metrics;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SpectrumJetson (photonvision-16, 19, 20): finding the Jetson's sysfs files by searching rather
 * than by fixed paths, since JetPack 7 (L4T r39, kernel 6.8) moved some of what JetPack 6 (r36,
 * kernel 5.15) had. Every lookup fails soft: null, or an empty list, when nothing matches.
 */
public final class JetsonSysfs {
    private JetsonSysfs() {}

    /**
     * GPU load, per mille (what tegrastats shows as GR3D_FREQ). Candidates in order:
     *
     * <ul>
     *   <li>{@code /sys/devices/platform/bus@0/17000000.gpu/load}: r36-verified. On r39 the
     *       directory is verified (railgate_enable, tpc_pg_mask, devfreq/ live there) but the {@code
     *       load} file itself is guessed from r36: check it on the Jetson.
     *   <li>any {@code *.gpu/load} under {@code /sys/devices/platform} or {@code bus@0}: guessed, in
     *       case the GPU's address or bus node changes.
     *   <li>{@code /sys/devices/gpu.0/load}: older L4T.
     * </ul>
     */
    public static Path gpuLoad() {
        Path p = Path.of("/sys/devices/platform/bus@0/17000000.gpu/load");
        if (Files.isReadable(p)) return p;
        p = firstMatch(Path.of("/sys/devices/platform/bus@0"), "*.gpu", "load");
        if (p != null) return p;
        p = firstMatch(Path.of("/sys/devices/platform"), "*.gpu", "load");
        if (p != null) return p;
        p = Path.of("/sys/devices/gpu.0/load");
        return Files.isReadable(p) ? p : null;
    }

    /**
     * The fan's PWM duty (0-255). r39-verified: {@code
     * /sys/devices/platform/pwm-fan/hwmon/hwmon0/pwm1}; the hwmon number is searched for.
     */
    public static Path fanPwm() {
        return firstMatch(Path.of("/sys/devices/platform/pwm-fan/hwmon"), "hwmon*", "pwm1");
    }

    /**
     * The first readable {@code dir/<childGlob>/<file>}, or null. Children are tried in name order so
     * the answer is stable.
     */
    static Path firstMatch(Path dir, String childGlob, String file) {
        for (Path child : children(dir, childGlob)) {
            Path p = child.resolve(file);
            if (Files.isReadable(p)) return p;
        }
        return null;
    }

    /** The entries of {@code dir} matching {@code glob}, sorted by name; empty if none or no dir. */
    static List<Path> children(Path dir, String glob) {
        var out = new ArrayList<Path>();
        try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, glob)) {
            for (Path p : s) out.add(p);
        } catch (IOException | RuntimeException e) {
            // not there: nothing found
        }
        out.sort(null);
        return out;
    }

    /** A sysfs file's contents, stripped, or null if it can't be read. */
    static String read(Path p) {
        try {
            return Files.readString(p).strip();
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** A sysfs file as a number, or null. */
    static Double number(Path p) {
        String s = p == null ? null : read(p);
        if (s == null) return null;
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * A thermal zone's name without the "-thermal" suffix. r36 types were "cpu-thermal",
     * "soc0-thermal"; on r39 cpu-thermal, gpu-thermal and tj-thermal are verified, and the SoC and
     * CV zones were reported as soc0..2 and cv0..2, so both spellings are matched.
     */
    static String zoneName(String type) {
        return type.endsWith("-thermal") ? type.substring(0, type.length() - "-thermal".length()) : type;
    }
}
