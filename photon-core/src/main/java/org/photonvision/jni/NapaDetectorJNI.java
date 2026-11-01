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

package org.photonvision.jni;

import org.wpilib.vision.apriltag.AprilTagDetection;

/**
 * Spectrum: NAPA, a sparse-sampling CPU AprilTag detector by Zachary Wilson (github.com/zwatx/NAPA,
 * v0.5.1, GPL-3.0-only; NOTICE), as libspectrum-napa-apriltag.so (SpectrumJetson detector/napa,
 * scripts/jetson/07-build-napa-detector.sh). One handle per camera; its detections are in OpenCV's
 * pixel convention already. Optional: {@link #isAvailable()} is false when the library isn't
 * installed, and callers fall back to their normal detector.
 */
public final class NapaDetectorJNI {
    private static final boolean available;
    private static String loadError = null;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("spectrum-napa-apriltag");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            loadError = e.getMessage();
        }
        available = ok;
    }

    private NapaDetectorJNI() {}

    public static boolean isAvailable() {
        return available;
    }

    public static String loadError() {
        return loadError;
    }

    /** A detector for a NAPA family name ("tag36h11", "tag16h5"); 0 if unknown. */
    public static native long create(String family);

    public static native void destroy(long handle);

    /**
     * minTagPx: the full-frame pass (far search off). far: the full frame at baseMinTagPx every
     * frame, and farMinTagPx in tracking crops around small tags and in capped sweeps of one band at
     * a time (at most sweepsPerSecond bands a second).
     */
    public static native void setParams(
            long handle,
            int minTagPx,
            int maxHamming,
            boolean far,
            int baseMinTagPx,
            int farMinTagPx,
            double sweepsPerSecond);

    /** An 8-bit gray image: data address, width, height and row step in bytes. */
    public static native AprilTagDetection[] detect(
            long handle, long dataAddr, int width, int height, int step);

    /** frames, base ms, crops, crop ms, sweeps, sweep ms, far tags (totals since create). */
    public static native double[] stats(long handle);
}
