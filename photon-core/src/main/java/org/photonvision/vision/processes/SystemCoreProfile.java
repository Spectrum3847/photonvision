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

package org.photonvision.vision.processes;

import org.photonvision.common.hardware.Platform;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;

/**
 * Spectrum (photonvision-98): the SystemCore profile. On the robot controller PhotonVision shares a
 * small CPU with robot code and has no GPU detector or JPEG hardware, so the CPU AprilTag pipeline
 * is capped:
 *
 * <ul>
 *   <li>detector threads at most {@link #MAX_THREADS} (the rest stay free for robot code);
 *   <li>decimation at least 2. MJPEG is decoded straight to full-resolution gray on the CPU
 *       (CpuMjpegDecode). The low-CPU half-resolution decode stays off unless the pipeline asks
 *       for it: on 1280x800 field frames it saved only 0.9 ms of 4.9 ms decode, the detector cost
 *       was the same as decimate 2, and it found 86% of the GPU detector's tags against 96%, with
 *       twice the corner error (tests/systemcore-cpu);
 *   <li>at most {@link #MAX_FPS} frames a second processed, and decoded (the decoder thread waits
 *       for the loop, as in IdleMode).
 * </ul>
 *
 * <p>On when this is a SystemCore (a QCS6490 that isn't a Rubik Pi) or a Raspberry Pi, or with
 * SPECTRUM_SYSTEMCORE_PROFILE=1; SPECTRUM_SYSTEMCORE_PROFILE=0 turns it off. Off on the Jetson,
 * where nothing changes. The pipeline's saved settings aren't changed, only what runs.
 */
public final class SystemCoreProfile {
    public static final int MAX_THREADS = 2;
    public static final int MAX_FPS = 50;

    private static final boolean ACTIVE = decide(System.getenv("SPECTRUM_SYSTEMCORE_PROFILE"));
    private static boolean logged = false;

    private SystemCoreProfile() {}

    static boolean decide(String env) {
        if ("1".equals(env)) return true;
        if ("0".equals(env)) return false;
        try {
            return isSystemCore() || Platform.isRaspberryPi();
        } catch (Throwable t) { // no platform detection (tests)
            return false;
        }
    }

    /** The SystemCore: a QCS6490 that isn't a Rubik Pi (the same test as RobotClockSync's). */
    static boolean isSystemCore() {
        if (!Platform.isQCS6490()) return false;
        try {
            String model =
                    java.nio.file.Files.readString(java.nio.file.Path.of("/proc/device-tree/model"));
            return !model.toUpperCase().contains("RUBIK");
        } catch (Exception e) {
            return true;
        }
    }

    public static boolean active() {
        if (ACTIVE && !logged) {
            logged = true;
            new Logger(SystemCoreProfile.class, LogGroup.General)
                    .info(
                            "SystemCore profile on: AprilTag detector threads <= "
                                    + MAX_THREADS
                                    + ", decimate >= 2, <= "
                                    + MAX_FPS
                                    + " fps (SPECTRUM_SYSTEMCORE_PROFILE=0 turns it off)");
        }
        return ACTIVE;
    }

    /** The detector threads to run. */
    public static int threads(int wanted) {
        return active() ? Math.max(1, Math.min(wanted, MAX_THREADS)) : wanted;
    }

    /** Whether to decode at half resolution: the pipeline's setting (the profile doesn't force it). */
    public static boolean lowCpuDecode(boolean setting) {
        return setting;
    }

    /** The decimation to run. */
    public static int decimate(int wanted) {
        return active() ? Math.max(2, wanted) : wanted;
    }

    /** The FPS limit to apply, given the camera's (0 or less: none). */
    public static int fpsLimit(int limit) {
        if (!active()) return limit;
        return limit > 0 ? Math.min(limit, MAX_FPS) : MAX_FPS;
    }

    /** The minimum frame period, ns (0: none). */
    public static long minPeriodNanos() {
        return active() ? 1_000_000_000L / MAX_FPS : 0;
    }
}
