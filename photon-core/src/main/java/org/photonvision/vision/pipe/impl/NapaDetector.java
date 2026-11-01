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

package org.photonvision.vision.pipe.impl;

import java.util.List;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.jni.NapaDetectorJNI;
import org.photonvision.vision.apriltag.AprilTagFamily;
import org.photonvision.vision.opencv.Releasable;
import org.wpilib.vision.apriltag.AprilTagDetection;

/**
 * Spectrum: one camera's NAPA detector (CPU AprilTag detector by Zachary Wilson, GPL-3.0-only;
 * {@link NapaDetectorJNI}), used by the AprilTag pipeline's "NAPA" detector choice and the CUDA /
 * AprilTag + Object pipelines' "Tags on CPU (NAPA)". Detections come back in OpenCV's pixel
 * convention (don't pass them through AprilTagPixelConvention). Created lazily; when the library
 * isn't installed, {@link #usable()} is false and the caller keeps its normal detector.
 */
public class NapaDetector implements Releasable {
    private static final Logger logger = new Logger(NapaDetector.class, LogGroup.VisionModule);
    private static boolean warned = false;

    private long handle = 0;
    private String family = null;
    private int[] sent = null;
    private double sentSweeps = -1;

    /** True when libspectrum-napa-apriltag.so is loaded (logs once why not). */
    public static boolean usable() {
        if (!NapaDetectorJNI.isAvailable() && !warned) {
            warned = true;
            logger.warn(
                    "NAPA detector selected but libspectrum-napa-apriltag isn't installed ("
                            + NapaDetectorJNI.loadError()
                            + "); using the normal detector");
        }
        return NapaDetectorJNI.isAvailable();
    }

    public void configure(
            AprilTagFamily fam,
            int minTagPx,
            int maxHamming,
            boolean far,
            int farMinTagPx,
            double sweeps) {
        String name = fam == null ? "tag36h11" : fam.getNativeName();
        if (handle == 0 || !name.equals(family)) {
            release();
            handle = NapaDetectorJNI.create(name);
            family = name;
            sent = null;
            if (handle == 0) logger.error("NAPA has no tag family " + name);
        }
        if (handle == 0) return;
        int[] want = {minTagPx, maxHamming, far ? 1 : 0, farMinTagPx};
        if (sent == null || !java.util.Arrays.equals(sent, want) || sentSweeps != sweeps) {
            // Base pass 32 with far search on (tests/napa-eval: 5x cheaper than 16, crops/sweeps add the
            // small tags back).
            NapaDetectorJNI.setParams(handle, minTagPx, maxHamming, far, 32, farMinTagPx, sweeps);
            sent = want;
            sentSweeps = sweeps;
        }
    }

    public List<AprilTagDetection> detect(Mat gray) {
        if (handle == 0 || gray == null || gray.empty() || gray.type() != CvType.CV_8UC1)
            return List.of();
        var ret =
                NapaDetectorJNI.detect(
                        handle, gray.dataAddr(), gray.cols(), gray.rows(), (int) gray.step1());
        return ret == null ? List.of() : List.of(ret);
    }

    @Override
    public void release() {
        if (handle != 0) NapaDetectorJNI.destroy(handle);
        handle = 0;
    }
}
