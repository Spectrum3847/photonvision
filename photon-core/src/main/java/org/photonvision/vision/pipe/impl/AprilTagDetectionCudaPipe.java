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
import org.opencv.core.Mat;
import org.photonvision.jni.GpuDetectorJNI;
import org.photonvision.vision.opencv.CVMat;
import org.photonvision.vision.opencv.Releasable;
import org.photonvision.vision.pipe.CVPipe;
import org.photonvision.vision.pipeline.DetectionMask;
import org.photonvision.vision.processes.FarSearch;
import org.wpilib.vision.apriltag.AprilTagDetection;

public class AprilTagDetectionCudaPipe
        extends CVPipe<CVMat, List<AprilTagDetection>, AprilTagDetectionCudaPipeParams>
        implements Releasable {
    private GpuDetectorJNI m_cudadetector = new GpuDetectorJNI();
    private long handle = 0;

    public AprilTagDetectionCudaPipe() {
        super();

        handle = m_cudadetector.createGpuDetector(640, 480); // just a guess
    }

    // SpectrumJetson (photonvision-40): the mask last sent to the detector library.
    private DetectionMask sentMask = null;
    private boolean maskUnsupported = false;

    /** Sends the detection mask to the GPU detector when it changed (detector-07). */
    public void setMask(DetectionMask mask) {
        if (maskUnsupported || mask == null || mask.equals(sentMask)) return;
        try {
            GpuDetectorJNI.setMask(handle, mask.active() ? mask.mode : 0, mask.flatBoxes());
            // A copy, so later edits to the setting show up as a change.
            var copy = new DetectionMask();
            copy.mode = mask.mode;
            for (var b : mask.boxes) {
                copy.boxes.add(new DetectionMask.Box(b.x, b.y, b.w, b.h));
            }
            sentMask = copy;
        } catch (UnsatisfiedLinkError e) {
            maskUnsupported = true; // an older libspectrum-gpu-apriltag.so without setMask
        }
    }

    @Override
    protected List<AprilTagDetection> process(CVMat in) {
        if (in.getMat().empty()) {
            return List.of();
        }

        // SpectrumJetson (photonvision-55): the far-search settings, once the library is loaded.
        FarSearch.apply();

        var ret = m_cudadetector.processimage(handle, in.getMat().getNativeObjAddr());

        if (ret == null) {
            return List.of();
        }

        return List.of(ret);
    }

    // SpectrumJetson (photonvision-66): the intrinsics last sent, for clearing the distortion.
    private double[] sentIntrinsics = null;

    @Override
    public void setParams(AprilTagDetectionCudaPipeParams newParams) {
        if (this.params == null || !this.params.equals(newParams)) {
            if (newParams.cameraCalibrationCoefficients == null) {
                // SpectrumJetson (photonvision-66): no calibration now (deleted, or none at this
                // resolution). The detector kept straightening tag edges with the old lens model;
                // zero distortion makes that a no-op (the intrinsics then don't matter).
                if (sentIntrinsics != null) {
                    var k = sentIntrinsics;
                    m_cudadetector.setparams(handle, k[0], k[1], k[2], k[3], 0, 0, 0, 0, 0);
                    sentIntrinsics = null;
                }
                super.setParams(newParams);
                return;
            }

            final Mat cameraMatrix = newParams.cameraCalibrationCoefficients.getCameraIntrinsicsMat();
            final Mat distCoeffs = newParams.cameraCalibrationCoefficients.getDistCoeffsMat();
            if (cameraMatrix == null || distCoeffs == null) return;
            var cx = cameraMatrix.get(0, 2)[0];
            var cy = cameraMatrix.get(1, 2)[0];
            var fx = cameraMatrix.get(0, 0)[0];
            var fy = cameraMatrix.get(1, 1)[0];
            sentIntrinsics = new double[] {fx, cx, fy, cy};
            // SpectrumJetson (photonvision-04): OpenCV order k1 k2 p1 p2 k3 [k4 k5 k6]. Read as a
            // flat array so a 1x8 or 8x1 Mat both work.
            double[] d = new double[(int) distCoeffs.total()];
            distCoeffs.get(0, 0, d);
            double k1 = d.length > 0 ? d[0] : 0, k2 = d.length > 1 ? d[1] : 0;
            double p1 = d.length > 2 ? d[2] : 0, p2 = d.length > 3 ? d[3] : 0;
            double k3 = d.length > 4 ? d[4] : 0;

            boolean sent = false;
            if (d.length >= 8) {
                // Pass the full rational model; setparams() drops k4..k6, which biases the
                // detector's edge refinement toward the image edges.
                try {
                    m_cudadetector.setparams8(
                            handle, fx, cx, fy, cy, k1, k2, p1, p2, k3, d[5], d[6], d[7]);
                    sent = true;
                } catch (UnsatisfiedLinkError e) {
                    System.out.println(
                            "libspectrum-gpu-apriltag has no setparams8; sending 5 of 8 distortion coefficients");
                }
            }
            if (!sent) {
                m_cudadetector.setparams(handle, fx, cx, fy, cy, k1, k2, p1, p2, k3);
            }
        }

        super.setParams(newParams);
    }

    @Override
    public void release() {
        m_cudadetector.destroyGpuDetector(handle);
        m_cudadetector = null;
    }
}
