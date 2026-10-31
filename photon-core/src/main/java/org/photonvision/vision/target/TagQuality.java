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

package org.photonvision.vision.target;

import org.opencv.calib3d.Calib3d;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.photonvision.vision.frame.FrameStaticProperties;
import org.wpilib.math.geometry.Transform3d;
import org.wpilib.math.geometry.Translation3d;
import org.wpilib.vision.apriltag.AprilTagDetection;
import org.wpilib.vision.apriltag.AprilTagPoseEstimate;

/**
 * SpectrumJetson (photonvision-61): how much the robot should trust each tag, measured where the
 * pixels are. Published per frame on {@code /photonvision/<camera>/tagQuality} next to rawBytes,
 * because PhotonLib's message format can't change (the robot checks its hash).
 *
 * <ul>
 *   <li><b>edgePx:</b> the corner nearest the image edge, in pixels. Distortion is strongest and
 *       the calibration weakest there, and a tag at the edge may be cut off.
 *   <li><b>undistortPx:</b> how far undistortion moved the corner that moved most. Large means the
 *       pose leans heavily on the lens model.
 *   <li><b>reprojBestPx, reprojAltPx:</b> RMS pixel error of the tag's corners under its best and
 *       alternate single-tag poses. For a tag used in multi-tag, both are under the multi-tag pose
 *       instead: how well this tag agrees with the others and the field layout.
 * </ul>
 *
 * NaN when it can't be known (no calibration, or no pose).
 */
public final class TagQuality {
    /** Values per tag on the NetworkTables topic, after the frame's sequence ID and tag count. */
    public static final int FIELDS_PER_TAG = 6;

    private TagQuality() {}

    /**
     * Measures one tag and stores the values on its target.
     *
     * @param pose the AprilTag-frame pose the target was built from (x right, y down, z out of the
     *     camera), or null
     */
    public static void fill(
            TrackedTarget target,
            AprilTagDetection detection,
            AprilTagPoseEstimate pose,
            FrameStaticProperties props,
            double tagSizeMeters) {
        double[] c = detection.getCorners();
        double edge = Double.POSITIVE_INFINITY;
        for (int i = 0; i < 4; i++) {
            double x = c[2 * i], y = c[2 * i + 1];
            edge =
                    Math.min(
                            edge,
                            Math.min(
                                    Math.min(x, y), Math.min(props.imageWidth - 1 - x, props.imageHeight - 1 - y)));
        }
        double undistort = Double.NaN, best = Double.NaN, alt = Double.NaN;

        var cal = props.cameraCalibration;
        var k = cal == null ? null : cal.getCameraIntrinsicsMat();
        var d = cal == null ? null : cal.getDistCoeffsMat();
        if (k != null && d != null && k.rows() == 3) {
            Point[] raw = new Point[4];
            for (int i = 0; i < 4; i++) raw[i] = new Point(c[2 * i], c[2 * i + 1]);
            var pts = new MatOfPoint2f(raw);
            try {
                // Same call as AprilTagPoseEstimatorPipe, so these are the corners the pose saw.
                Calib3d.undistortImagePoints(pts, pts, k, d);
                Point[] u = pts.toArray();
                undistort = 0;
                for (int i = 0; i < 4; i++) {
                    undistort = Math.max(undistort, Math.hypot(u[i].x - raw[i].x, u[i].y - raw[i].y));
                }
                if (pose != null) {
                    double fx = k.get(0, 0)[0], fy = k.get(1, 1)[0];
                    double cx = k.get(0, 2)[0], cy = k.get(1, 2)[0];
                    boolean firstIsBest = pose.error1 <= pose.error2;
                    best = reproj(firstIsBest ? pose.pose1 : pose.pose2, u, tagSizeMeters, fx, fy, cx, cy);
                    alt = reproj(firstIsBest ? pose.pose2 : pose.pose1, u, tagSizeMeters, fx, fy, cx, cy);
                }
            } finally {
                pts.release();
            }
        }
        target.setTagQuality(edge, undistort, best, alt);
    }

    // The tag's corners in its own frame, in detection order: bottom-left, bottom-right, top-right,
    // top-left as seen in the image (AprilTag's pose convention).
    private static final double[][] CORNERS = {{-1, 1}, {1, 1}, {1, -1}, {-1, -1}};

    private static double reproj(
            Transform3d camToTag,
            Point[] undistorted,
            double size,
            double fx,
            double fy,
            double cx,
            double cy) {
        if (camToTag == null) return Double.NaN;
        double sum = 0;
        for (int i = 0; i < 4; i++) {
            var p =
                    new Translation3d(CORNERS[i][0] * size / 2, CORNERS[i][1] * size / 2, 0)
                            .rotateBy(camToTag.getRotation())
                            .plus(camToTag.getTranslation());
            if (p.getZ() <= 1e-6) return Double.NaN;
            double du = fx * p.getX() / p.getZ() + cx - undistorted[i].x;
            double dv = fy * p.getY() / p.getZ() + cy - undistorted[i].y;
            sum += du * du + dv * dv;
        }
        return Math.sqrt(sum / 4);
    }

    /**
     * The topic's value: [sequenceID, tag count, then per tag in the result's order: fiducial ID,
     * decision margin, edgePx, undistortPx, reprojBestPx, reprojAltPx]. Null when there are no tags.
     */
    public static double[] toArray(long sequenceID, java.util.List<TrackedTarget> targets) {
        int n = 0;
        for (var t : targets) if (t.getFiducialId() >= 0) n++;
        if (n == 0) return null;
        double[] out = new double[2 + FIELDS_PER_TAG * n];
        out[0] = sequenceID;
        out[1] = n;
        int i = 2;
        for (var t : targets) {
            if (t.getFiducialId() < 0) continue;
            out[i++] = t.getFiducialId();
            out[i++] = t.getDecisionMargin();
            out[i++] = t.getEdgePx();
            out[i++] = t.getUndistortPx();
            out[i++] = t.getReprojBestPx();
            out[i++] = t.getReprojAltPx();
        }
        return out;
    }
}
