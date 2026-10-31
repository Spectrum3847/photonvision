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
 * FRC 4143: the CUDA AprilTag detector, libspectrum-gpu-apriltag.so (built by SpectrumJetson from
 * frc971's detector; lib971apriltag.so before 2026-10-31, still loaded if it's the only one). Its
 * native functions are named after this package and class, so neither can move.
 */
public class GpuDetectorJNI {
    static boolean libraryLoaded = false;

    static {
        if (!libraryLoaded) {
            try {
                System.loadLibrary("spectrum-gpu-apriltag");
            } catch (UnsatisfiedLinkError e) {
                System.loadLibrary("971apriltag"); // the name before 2026-10-31
            }
        }
        libraryLoaded = true;
    }

    public static native long createGpuDetector(int width, int height);

    public static native void destroyGpuDetector(long handle);

    public static native void setparams(
            long handle,
            double fx,
            double cx,
            double fy,
            double cy,
            double k1,
            double k2,
            double p1,
            double p2,
            double k3);

    public static native AprilTagDetection[] processimage(long handle, long p);

    /**
     * SpectrumJetson (photonvision-40, detector-07): the detection mask. mode 0 off, 1 ignore inside the
     * boxes, 2 search only inside them; rects: x, y, w, h fractions of the image, 4 per box. Takes
     * effect on the next frame.
     */
    public static native void setMask(long handle, int mode, double[] rects);

    /**
     * SpectrumJetson (photonvision-04): all 8 coefficients of the OpenCV rational model
     * PhotonVision's calibration produces (k1 k2 p1 p2 k3 k4 k5 k6). setparams() drops k4..k6. Only
     * the SpectrumJetson gpu build of libspectrum-gpu-apriltag has this; callers fall back to setparams().
     */
    public static native void setparams8(
            long handle,
            double fx,
            double cx,
            double fy,
            double cy,
            double k1,
            double k2,
            double p1,
            double p2,
            double k3,
            double k4,
            double k5,
            double k6);

    /**
     * SpectrumJetson (photonvision-09): decodes a camera's MJPEG frame (a cscore RawFrame, by its
     * native address) straight to 8-bit gray into a Mat Java allocated (WxH CV_8UC1). 0 ok, -1 not
     * an MJPEG frame, -2 bad JPEG, -3 the Mat isn't the frame's size.
     */
    public static native int decodeMjpegGray(long rawFramePtr, long cvMatPtr);

    /**
     * SpectrumJetson (photonvision-18): whether colour frames are decoded on the Jetson's JPEG
     * hardware (SPECTRUM_JPEG_DECODER=nvjpg and libspectrumnvjpg.so loaded).
     */
    public static native boolean hardwareJpegDecode();

    /**
     * SpectrumJetson (photonvision-18): decodes a camera's MJPEG frame to BGR on the JPEG hardware
     * into a WxH CV_8UC3 Mat. Return codes as decodeMjpegGray.
     */
    public static native int decodeMjpegBgr(long rawFramePtr, long cvMatPtr);

    /**
     * SpectrumJetson (photonvision-55): the far-tag search (detector/far_search.h): full-size
     * searches, one camera at a time, only while no camera has a good view. {@code sweepsPerSecond}
     * caps the full-size searches across all cameras.
     */
    public static native void setFarSearch(boolean enabled, double sweepsPerSecond);

    /**
     * SpectrumJetson (photonvision-69): the excluded tags, which don't count toward the far search's
     * good view: they're left out of the multi-tag solve.
     */
    public static native void setFarSearchExcluded(int[] ids);

    /**
     * SpectrumJetson (photonvision-55): {enabled, starved now, full-size searches, crops, far tags
     * returned, seconds starved, ms in full-size searches, ms in crops}, since PhotonVision started.
     */
    public static native double[] farSearchStatus();
}
