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

package org.photonvision.common.hardware;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.photonvision.vision.pipeline.PipelineType;
import org.photonvision.vision.processes.VisionModule;

/**
 * SpectrumJetson (photonvision-72): what the Jetson's GPU does, for Settings > Device ("GPU
 * Acceleration"). That row is upstream's, for the Raspberry Pi's camera path, so on a Jetson it
 * said "None detected" while the CUDA detector ran every AprilTagCuda pipeline (another team asked
 * whether they'd set something up wrong). The libraries in use come from /proc/self/maps, so asking
 * never loads one.
 */
public final class GpuStatus {
    private static volatile String maps = "";
    private static volatile long mapsReadNanos = Long.MIN_VALUE / 2;

    private GpuStatus() {}

    /** The GPU detector library, under its name or its name before 2026-10-31. */
    private static boolean detectorLoaded(String loaded) {
        return loaded.contains("libspectrum-gpu-apriltag.so")
                || loaded.contains("lib971apriltag.so");
    }

    /** The GPU's work in a sentence, or "" off a Jetson (no CUDA detector installed). */
    public static String describe(Iterable<VisionModule> modules) {
        String loaded = loadedLibraries();
        if (!Files.exists(Path.of("/usr/lib/libspectrum-gpu-apriltag.so"))
                && !Files.exists(Path.of("/usr/lib/lib971apriltag.so"))
                && !Files.exists(Path.of("/usr/local/lib/lib971apriltag.so"))
                && !detectorLoaded(loaded)) {
            return "";
        }
        var cuda = new ArrayList<String>();
        var cpu = new ArrayList<String>();
        for (var m : modules) {
            var s = m.getCurrentPipelineSettings();
            if (s == null) continue;
            String name = m.getCameraConfiguration().nickname;
            if (s.pipelineType == PipelineType.AprilTagCuda
                    || s.pipelineType == PipelineType.AprilTagObject) { // photonvision-100
                cuda.add(name);
            } else if (s.pipelineType == PipelineType.AprilTag) cpu.add(name);
        }
        var parts = new ArrayList<String>();
        if (detectorLoaded(loaded) && !cuda.isEmpty()) {
            parts.add(
                    "CUDA AprilTag detection: "
                            + plural(cuda.size(), "camera")
                            + " set to AprilTagCuda pipelines");
        } else {
            parts.add("CUDA detector not in use: no camera runs an AprilTagCuda pipeline");
        }
        if (loaded.contains("libspectrumnvjpg.so")) parts.add("JPEG decode on the NVJPG engine");
        if (loaded.contains("libspectrumtrt.so")) parts.add("TensorRT object detection");
        if (!cpu.isEmpty()) {
            parts.add(
                    String.join(", ", cpu)
                            + " on the CPU AprilTag pipeline type (AprilTagCuda is the GPU one)");
        }
        return String.join("; ", parts);
    }

    private static String plural(int n, String what) {
        return n + " " + what + (n == 1 ? "" : "s");
    }

    /** /proc/self/maps, re-read at most every 5 s (it's long, and libraries load once). */
    private static String loadedLibraries() {
        long now = System.nanoTime();
        if (now - mapsReadNanos > 5_000_000_000L) {
            mapsReadNanos = now;
            try {
                maps = Files.readString(Path.of("/proc/self/maps"));
            } catch (Exception e) {
                maps = "";
            }
        }
        return maps;
    }
}
