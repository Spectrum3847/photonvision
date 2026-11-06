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

import java.util.ArrayList;
import java.util.List;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.MatOfPoint3f;
import org.opencv.core.Point;
import org.opencv.core.Point3;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.pipe.CVPipe;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Transform3d;
import org.wpilib.math.geometry.Translation3d;
import org.wpilib.math.linalg.MatBuilder;
import org.wpilib.math.util.Nat;
import org.wpilib.vision.apriltag.AprilTagDetection;
import org.wpilib.vision.apriltag.AprilTagPoseEstimate;
import org.wpilib.vision.apriltag.AprilTagPoseEstimator;
import org.wpilib.vision.apriltag.AprilTagPoseEstimator.Config;

public class AprilTagPoseEstimatorPipe
        extends CVPipe<
                AprilTagDetection,
                AprilTagPoseEstimate,
                AprilTagPoseEstimatorPipe.AprilTagPoseEstimatorPipeParams> {
    private final AprilTagPoseEstimator m_poseEstimator =
            new AprilTagPoseEstimator(new AprilTagPoseEstimator.Config(0, 0, 0, 0, 0));

    public AprilTagPoseEstimatorPipe() {
        super();
    }

    MatOfPoint2f cornersMat = new MatOfPoint2f();

    // Spectrum (sc/mjpeg-napa): the native IPPE + Gauss-Newton pose when the NAPA library is there
    // (SPECTRUM_NATIVE_POSE=0, a DEBUG switch only, not a setting: WPILib's orthogonal iteration as
    // before, Java path only). Measured: the Java path
    // (orthogonal
    // iteration + OpenCV IPPE for the alternate) was 0.28 ms of every frame's latency on the
    // SystemCore.
    private static final boolean NATIVE_POSE = !"0".equals(System.getenv("SPECTRUM_NATIVE_POSE"));

    private static Transform3d nativeTransform(double[] r, int o) {
        var m =
                MatBuilder.fill(
                        Nat.N3(), Nat.N3(), r[o + 3], r[o + 4], r[o + 5], r[o + 6], r[o + 7], r[o + 8],
                        r[o + 9], r[o + 10], r[o + 11]);
        return new Transform3d(new Translation3d(r[o], r[o + 1], r[o + 2]), new Rotation3d(m));
    }

    @Override
    protected AprilTagPoseEstimate process(AprilTagDetection in) {
        if (NATIVE_POSE && NapaDetector.usable() && params.calibration() != null) {
            try {
                double[] c = new double[8];
                for (int i = 0; i < 4; i++) {
                    c[2 * i] = in.getCornerX(i);
                    c[2 * i + 1] = in.getCornerY(i);
                }
                double[] r =
                        org.photonvision.jni.NapaDetectorJNI.poseSquare(
                                c,
                                params.calibration().getIntrinsicsArr(),
                                params.calibration().getDistCoeffsArr(),
                                params.config().tagSize);
                if (r != null)
                    return new AprilTagPoseEstimate(
                            nativeTransform(r, 0), nativeTransform(r, 13), r[12], r[25]);
            } catch (UnsatisfiedLinkError e) {
                // an older NAPA library: the Java path below
            }
        }
        // Save the corner points of our detection to an array
        Point[] corners = new Point[4];
        for (int i = 0; i < 4; i++) {
            corners[i] = new Point(in.getCornerX(i), in.getCornerY(i));
        }
        // And shove into our matofpoints
        cornersMat.fromArray(corners);

        // Probably overwrites what was in temp before. I hope
        Calib3d.undistortImagePoints(
                cornersMat,
                cornersMat,
                params.calibration().getCameraIntrinsicsMat(),
                params.calibration().getDistCoeffsMat());

        // Save out undistorted corners
        corners = cornersMat.toArray();

        // Apriltagdetection expects an array in form [x1 y1 x2 y2 ...]
        var fixedCorners = new double[8];
        for (int i = 0; i < 4; i++) {
            fixedCorners[i * 2] = corners[i].x;
            fixedCorners[i * 2 + 1] = corners[i].y;
        }

        // Create a new Detection with the fixed corners
        var corrected =
                new AprilTagDetection(
                        in.getFamily(),
                        in.getId(),
                        in.getHamming(),
                        in.getDecisionMargin(),
                        in.getHomography(),
                        in.getCenterX(),
                        in.getCenterY(),
                        fixedCorners);

        var est = m_poseEstimator.estimateOrthogonalIteration(corrected, params.nIters());
        return withAlternate(est, corners);
    }

    /**
     * Spectrum (sc/mjpeg-napa): AprilTag's orthogonal iteration often finds only one minimum; it then
     * returns an all-zero second pose with error 0, so PhotonVision published altCameraToTarget =
     * (0,0,0) (the robot "at the tag") and poseAmbiguity 0 (passes every ambiguity gate). Fill the
     * alternate from IPPE's two solutions for the square (the undistorted corners, AprilTag's tag
     * coordinates) and set error2 so that getAmbiguity() = IPPE's best/alt reprojection-error ratio.
     */
    private AprilTagPoseEstimate withAlternate(AprilTagPoseEstimate est, Point[] undistorted) {
        if (est.error2 > 0 && est.pose2.getTranslation().getNorm() > 1e-6) return est;
        double h = params.config().tagSize / 2;
        var obj =
                new MatOfPoint3f(
                        new Point3(-h, h, 0), new Point3(h, h, 0), new Point3(h, -h, 0), new Point3(-h, -h, 0));
        var img = new MatOfPoint2f(undistorted);
        var k = params.calibration().getCameraIntrinsicsMat();
        var noDist = new MatOfDouble(0, 0, 0, 0, 0);
        List<Mat> rvecs = new ArrayList<>(), tvecs = new ArrayList<>();
        var errs = new Mat();
        try {
            int n =
                    Calib3d.solvePnPGeneric(
                            obj,
                            img,
                            k,
                            noDist,
                            rvecs,
                            tvecs,
                            false,
                            Calib3d.SOLVEPNP_IPPE_SQUARE,
                            new Mat(),
                            new Mat(),
                            errs);
            if (n < 2 || errs.rows() < 2) return est;
            double e0 = errs.get(0, 0)[0], e1 = errs.get(1, 0)[0];
            int alt = e0 <= e1 ? 1 : 0;
            double eb = Math.min(e0, e1), ea = Math.max(e0, e1);
            Transform3d altPose = toAprilTagFrame(rvecs.get(alt), tvecs.get(alt));
            // getAmbiguity() = min(error1, error2) / max(error1, error2): keep error1, scale error2
            double amb = ea > 0 ? eb / ea : 0;
            double error2 = amb > 0 ? est.error1 / amb : Double.MAX_VALUE;
            return new AprilTagPoseEstimate(est.pose1, altPose, est.error1, error2);
        } finally {
            obj.release();
            img.release();
            noDist.release();
            errs.release();
            for (var m : rvecs) m.release();
            for (var m : tvecs) m.release();
        }
    }

    /**
     * IPPE's pose (tag axes x right, y up as AprilTag's homography) in AprilTag's frame (y down, z
     * into tag).
     */
    private static Transform3d toAprilTagFrame(Mat rvec, Mat tvec) {
        var R = new Mat();
        Calib3d.Rodrigues(rvec, R);
        double[] r = new double[9];
        var Rd = new Mat();
        R.convertTo(Rd, CvType.CV_64F);
        Rd.get(0, 0, r);
        R.release();
        Rd.release();
        // R_at = R_h * diag(1, -1, -1)
        var m =
                MatBuilder.fill(
                        Nat.N3(), Nat.N3(), r[0], -r[1], -r[2], r[3], -r[4], -r[5], r[6], -r[7], -r[8]);
        double[] t = new double[3];
        tvec.get(0, 0, t);
        return new Transform3d(new Translation3d(t[0], t[1], t[2]), new Rotation3d(m));
    }

    @Override
    public void setParams(AprilTagPoseEstimatorPipe.AprilTagPoseEstimatorPipeParams newParams) {
        if (this.params == null || !this.params.config().equals(newParams.config())) {
            m_poseEstimator.setConfig(newParams.config());
        }

        super.setParams(newParams);
    }

    @Override
    public void release() {
        cornersMat.release();
    }

    public static record AprilTagPoseEstimatorPipeParams(
            Config config, CameraCalibrationCoefficients calibration, int nIters) {}
}
