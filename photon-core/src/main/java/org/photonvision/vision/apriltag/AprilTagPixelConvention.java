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

package org.photonvision.vision.apriltag;

import org.wpilib.vision.apriltag.AprilTagDetection;

/**
 * Spectrum: AprilTag puts a pixel's centre at +0.5 (pixel (0,0) spans 0..1); OpenCV, and so every
 * camera calibration and the pose solve, puts it at 0. Left alone, every corner sits 0.5 px
 * down-right of where the calibration expects it: a fixed bearing bias (~0.04 deg at 1280x800) in
 * every pose. WPILib's detector (the CPU AprilTag pipeline) returns AprilTag's convention, so its
 * results go through {@link #toOpenCv}. The CUDA pipeline's library already returns OpenCV's
 * (SpectrumJetson detector/GpuDetectorJNI.cc), so it must not.
 */
public final class AprilTagPixelConvention {
    private static final double kPixelCentre = 0.5;

    private AprilTagPixelConvention() {}

    /**
     * The detection in OpenCV's convention: corners and centre shifted by -0.5 px, and the
     * homography (tag coordinates to image) with them, H' = T*H, T = [[1,0,-0.5],[0,1,-0.5],[0,0,1]].
     */
    public static AprilTagDetection toOpenCv(AprilTagDetection d) {
        double[] h = d.getHomography();
        double[] hs = new double[9];
        for (int c = 0; c < 3; c++) {
            hs[c] = h[c] - kPixelCentre * h[6 + c];
            hs[3 + c] = h[3 + c] - kPixelCentre * h[6 + c];
            hs[6 + c] = h[6 + c];
        }
        double[] corners = d.getCorners();
        double[] cs = new double[corners.length];
        for (int i = 0; i < corners.length; i++) cs[i] = corners[i] - kPixelCentre;
        return new AprilTagDetection(
                d.getFamily(),
                d.getId(),
                d.getHamming(),
                d.getDecisionMargin(),
                hs,
                d.getCenterX() - kPixelCentre,
                d.getCenterY() - kPixelCentre,
                cs);
    }
}
