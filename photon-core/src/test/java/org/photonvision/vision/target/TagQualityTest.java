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

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.photonvision.common.LoadJNI;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.util.TestUtils;
import org.photonvision.vision.apriltag.AprilTagFamily;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.calibration.CameraLensModel;
import org.photonvision.vision.calibration.JsonMatOfDouble;
import org.photonvision.vision.camera.QuirkyCamera;
import org.photonvision.vision.frame.provider.FileFrameProvider;
import org.photonvision.vision.pipeline.AprilTagPipeline;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.wpilib.fields.Field;
import org.wpilib.fields.FieldTag;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Transform3d;
import org.wpilib.math.geometry.Translation3d;
import org.wpilib.vision.apriltag.AprilTagImageGenerator;

/** SpectrumJetson (photonvision-61): per-tag quality numbers. */
public class TagQualityTest {
    @BeforeEach
    public void init() {
        LoadJNI.loadLibraries();
        ConfigProvider.getInstance().load();
    }

    private static void checkTarget(TrackedTarget t, double width, double height) {
        assertTrue(
                t.getEdgePx() >= 0 && t.getEdgePx() < Math.min(width, height) / 2,
                "edgePx " + t.getEdgePx());
        assertTrue(
                t.getUndistortPx() >= 0 && t.getUndistortPx() < 50, "undistortPx " + t.getUndistortPx());
        assertTrue(t.getReprojBestPx() < 1.0, "reprojBestPx " + t.getReprojBestPx());
        // NaN: no alternate pose (WPILib gives none when the tag faces the camera squarely).
        double alt = t.getReprojAltPx();
        assertTrue(Double.isNaN(alt) || alt >= t.getReprojBestPx() - 1e-6, "alt below best");
    }

    @Test
    public void realImageSingleTag() {
        var pipeline = new AprilTagPipeline();
        pipeline.getSettings().solvePNPEnabled = true;
        pipeline.getSettings().doMultiTarget = false;
        pipeline.getSettings().targetModel = TargetModel.kAprilTag6p5in_36h11;
        pipeline.getSettings().tagFamily = AprilTagFamily.kTag36h11;
        var frameProvider =
                new FileFrameProvider(
                        TestUtils.getApriltagImagePath(TestUtils.ApriltagTestImages.kTag1_640_480, false),
                        TestUtils.WPI2020Image.FOV,
                        TestUtils.get2020LifeCamCoeffs(false));
        frameProvider.requestFrameThresholdType(pipeline.getThresholdType());
        CVPipelineResult result = pipeline.run(frameProvider.get(), QuirkyCamera.DefaultCamera);

        assertFalse(result.targets.isEmpty());
        var t = result.targets.get(0);
        System.out.printf(
                "real tag %d: margin %.1f edge %.1f px, undistort %.2f px, reproj best %.3f alt %.3f px%n",
                t.getFiducialId(),
                t.getDecisionMargin(),
                t.getEdgePx(),
                t.getUndistortPx(),
                t.getReprojBestPx(),
                t.getReprojAltPx());
        checkTarget(t, 640, 480);
        // The image's tag: corners at x 260..315, y 190..245, so 190 px from the top edge.
        assertEquals(190, t.getEdgePx(), 10);

        double[] arr = TagQuality.toArray(42, result.targets);
        assertEquals(42, arr[0]);
        assertEquals(result.targets.size(), arr[1]);
        assertEquals(2 + TagQuality.FIELDS_PER_TAG * result.targets.size(), arr.length);
        assertEquals(t.getFiducialId(), arr[2]);
        assertEquals(t.getReprojBestPx(), arr[6]);
        assertNull(TagQuality.toArray(1, List.of()));
    }

    /**
     * Two tags rendered from the field layout with a pinhole camera, through multi-tag: each tag's
     * reprojection under the multi-tag pose must be near zero. That checks the pose conventions the
     * pipeline uses for multi-tag tags. Then the same with one tag moved 5 cm in the layout: its
     * error must grow.
     */
    @Test
    public void syntheticMultiTag() {
        int w = 1280, h = 800;
        double f = 900;
        var cal = pinhole(w, h, f);
        var layout = ConfigProvider.getInstance().getConfig().getFieldLayout();
        // Two tags facing the same way, close together.
        FieldTag a = null, b = null;
        outer:
        for (var t1 : layout.getTags()) {
            for (var t2 : layout.getTags()) {
                if (t1.getID() >= t2.getID()) continue;
                double dYaw =
                        Math.abs(
                                t1.getPose()
                                        .getRotation()
                                        .toRotation2d()
                                        .minus(t2.getPose().getRotation().toRotation2d())
                                        .getDegrees());
                double dist = t1.getPose().getTranslation().getDistance(t2.getPose().getTranslation());
                if (dYaw < 1
                        && dist > 0.4
                        && dist < 1.5
                        && Math.abs(t1.getPose().getZ() - t2.getPose().getZ()) < 0.5) {
                    a = t1;
                    b = t2;
                    break outer;
                }
            }
        }
        assertNotNull(a, "no two nearby tags facing the same way in the layout");

        // Camera 2.5 m in front of the pair, at their height, looking back at them, yawed a little.
        var mid = a.getPose().getTranslation().plus(b.getPose().getTranslation()).div(2);
        var out = new Translation3d(1, 0, 0).rotateBy(a.getPose().getRotation());
        var camTrl = mid.plus(out.times(2.5)).plus(new Translation3d(0, 0, -0.1));
        var camRot = new Rotation3d(0, 0, a.getPose().getRotation().getZ() + Math.PI + 0.05);
        var cameraPose = new Pose3d(camTrl, camRot);

        Mat img = new Mat(h, w, CvType.CV_8UC1, new org.opencv.core.Scalar(128));
        for (var tag : List.of(a, b)) {
            var verts =
                    org.photonvision.estimation.TargetModel.kAprilTag36h11.getFieldVertices(tag.getPose());
            Point[] px = new Point[4];
            for (int i = 0; i < 4; i++) px[i] = project(cameraPose, verts.get(i), f, w, h);
            warpTag(tag.getID(), px, img);
        }

        var r = runPipeline(img, cal, w, h);
        assertTrue(r.multiTagResult.isPresent(), "multi-tag didn't run");
        assertEquals(2, r.targets.size());
        for (var t : r.targets) {
            System.out.printf(
                    "synthetic tag %d: edge %.1f px, undistort %.3f px, reproj best %.3f alt %.3f px%n",
                    t.getFiducialId(),
                    t.getEdgePx(),
                    t.getUndistortPx(),
                    t.getReprojBestPx(),
                    t.getReprojAltPx());
            checkTarget(t, w, h);
            assertEquals(0, t.getUndistortPx(), 1e-6); // no distortion in this calibration
            assertTrue(t.getReprojBestPx() < 0.5);
        }

        // Move tag b 5 cm sideways in the layout; the image stays the same. b no longer agrees.
        var tags = new java.util.ArrayList<>(layout.getTags());
        final int bId = b.getID();
        tags.replaceAll(
                t ->
                        t.getID() == bId
                                ? new FieldTag(
                                        t.getID(), t.getPose().plus(new Transform3d(0, 0.05, 0, new Rotation3d())))
                                : t);
        var original = layout;
        ConfigProvider.getInstance()
                .getConfig()
                .setFieldLayout(
                        new Field(
                                layout.name,
                                layout.season,
                                layout.game,
                                layout.fieldImage,
                                layout.getFieldLength(),
                                layout.getFieldWidth(),
                                layout.program,
                                tags));
        try {
            var moved = runPipeline(img, cal, w, h);
            double worst = 0;
            for (var t : moved.targets) worst = Math.max(worst, t.getReprojBestPx());
            System.out.printf("tag %d moved 5 cm: worst reproj %.2f px%n", bId, worst);
            assertTrue(worst > 3, "a 5 cm layout error should show (2.5 m, f 900: ~9 px split)");
        } finally {
            ConfigProvider.getInstance().getConfig().setFieldLayout(original);
        }
        img.release();
    }

    private static CVPipelineResult runPipeline(
            Mat img, CameraCalibrationCoefficients cal, int w, int h) {
        var pipeline = new AprilTagPipeline();
        pipeline.getSettings().solvePNPEnabled = true;
        pipeline.getSettings().doMultiTarget = true;
        pipeline.getSettings().doSingleTargetAlways = false;
        pipeline.getSettings().targetModel = TargetModel.kAprilTag6p5in_36h11;
        pipeline.getSettings().tagFamily = AprilTagFamily.kTag36h11;
        pipeline.getSettings().decimate = 1;
        var path = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "tagquality-synth.png");
        Imgcodecs.imwrite(path.toString(), img);
        var frameProvider = new FileFrameProvider(path, 70, cal);
        frameProvider.requestFrameThresholdType(pipeline.getThresholdType());
        return pipeline.run(frameProvider.get(), QuirkyCamera.DefaultCamera);
    }

    private static CameraCalibrationCoefficients pinhole(int w, int h, double f) {
        return new CameraCalibrationCoefficients(
                new Size(w, h),
                new JsonMatOfDouble(3, 3, new double[] {f, 0, w / 2.0, 0, f, h / 2.0, 0, 0, 1}),
                new JsonMatOfDouble(1, 8, new double[8]),
                new double[0],
                List.of(),
                new Size(1, 1),
                1,
                CameraLensModel.LENSMODEL_OPENCV,
                null);
    }

    // Field point to pixel: field NWU -> camera NWU -> OpenCV (x right, y down, z forward).
    private static Point project(Pose3d camera, Translation3d field, double f, int w, int h) {
        var c = field.minus(camera.getTranslation()).rotateBy(camera.getRotation().inverse());
        double x = -c.getY(), y = -c.getZ(), z = c.getX();
        return new Point(f * x / z + w / 2.0, f * y / z + h / 2.0);
    }

    // WPILib's 10x10 tag image, scaled up, warped so the black square's corners land on px (in
    // detection order: bottom-left, bottom-right, top-right, top-left).
    private static void warpTag(int id, Point[] px, Mat dst) {
        var raw = AprilTagImageGenerator.generate36h11AprilTagImage(id);
        var tag =
                new Mat(raw.getHeight(), raw.getWidth(), CvType.CV_8UC1, raw.getData(), raw.getStride());
        int s = 40;
        var big = new Mat();
        Imgproc.resize(tag, big, new Size(), s, s, Imgproc.INTER_NEAREST);
        // Pixel centres: the black square spans cells 1..8, i.e. edges at s*1 - 0.5 and s*9 - 0.5.
        double lo = s - 0.5, hi = 9 * s - 0.5;
        var src =
                new MatOfPoint2f(
                        new Point(lo, hi), new Point(hi, hi), new Point(hi, lo), new Point(lo, lo));
        var m = Imgproc.getPerspectiveTransform(src, new MatOfPoint2f(px));
        Imgproc.warpPerspective(big, dst, m, dst.size(), Imgproc.INTER_LINEAR, Core.BORDER_TRANSPARENT);
        tag.release();
        big.release();
        raw.close();
    }
}
