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

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.commons.io.FileUtils;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.*;
import org.opencv.imgcodecs.Imgcodecs;
import org.photonvision.common.LoadJNI;
import org.photonvision.common.LoadJNI.JNITypes;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.math.MathUtils;
import org.photonvision.mrcal.MrCalJNI;
import org.photonvision.mrcal.MrCalJNI.MrCalObservation;
import org.photonvision.mrcal.MrCalJNI.MrCalResult;
import org.photonvision.vision.calibration.BoardObservation;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients.OptimizationInputs;
import org.photonvision.vision.calibration.CameraLensModel;
import org.photonvision.vision.calibration.CornerAlignment;
import org.photonvision.vision.calibration.JsonMatOfDouble;
import org.photonvision.vision.calibration.LensFoldCheck;
import org.photonvision.vision.frame.FrameStaticProperties;
import org.photonvision.vision.pipe.CVPipe;
import org.wpilib.math.geometry.Pose3d;
import org.wpilib.math.geometry.Rotation3d;
import org.wpilib.math.geometry.Translation3d;
import org.wpilib.math.linalg.VecBuilder;

public class Calibrate3dPipe
        extends CVPipe<
                Calibrate3dPipe.CalibrationInput,
                CameraCalibrationCoefficients,
                Calibrate3dPipe.CalibratePipeParams> {
    public static class CalibrationInput {
        final List<FindBoardCornersPipe.FindBoardCornersPipeResult> observations;
        final FrameStaticProperties imageProps;
        final Path imageSavePath;

        public CalibrationInput(
                List<FindBoardCornersPipe.FindBoardCornersPipeResult> observations,
                FrameStaticProperties imageProps,
                Path imageSavePath) {
            this.observations =
                    observations.stream()
                            .filter(
                                    observation ->
                                            observation != null
                                                    && observation.imagePoints != null
                                                    && observation.objectPoints != null
                                                    && observation.size != null)
                            .toList();
            this.imageProps = imageProps;
            this.imageSavePath = imageSavePath;
        }
    }

    // For logging
    private static final Logger logger = new Logger(Calibrate3dPipe.class, LogGroup.General);

    // The Standard deviation of the estimated parameters
    private final Mat stdDeviationsIntrinsics = new Mat();
    private final Mat stdDeviationsExtrinsics = new Mat();

    // Contains the re projection error of each snapshot by re projecting the
    // corners we found and
    // finding the Euclidean distance between the actual corners.
    private final Mat perViewErrors = new Mat();

    final MatOfPoint3f objectPoints = new MatOfPoint3f();

    // Spectrum (photonvision-91): the last solve's fx standard deviation (pixels, NaN if the solver
    // gave none) and which solver produced it, for the calibration view's quality readout.
    private volatile double lastFxSigma = Double.NaN;
    private volatile String lastSolver = "";

    public double lastFxSigma() {
        return lastFxSigma;
    }

    public String lastSolver() {
        return lastSolver;
    }
    private final MatOfPoint2f projectedMat = new MatOfPoint2f();

    /**
     * Runs the process for the pipe.
     *
     * @param in Input for pipe processing. In the format (Input image, object points, image points)
     * @return Result of processing.
     */
    @Override
    protected CameraCalibrationCoefficients process(CalibrationInput in) {
        if (in.observations.isEmpty()) {
            logger.error("No observations provided for calibration!");
            return null;
        }

        CameraCalibrationCoefficients ret;
        var start = System.nanoTime();
        lastFxSigma = Double.NaN;
        lastSolver = "";

        if (LoadJNI.hasLoaded(JNITypes.MRCAL) && params.useMrCal) {
            logger.debug("Calibrating with mrcal!");
            ret = calibrateMrcal(in.observations, in.imageProps, in.imageSavePath);
        } else {
            logger.debug("Calibrating with opencv!");
            ret = calibrateOpenCV(in.observations, in.imageProps, in.imageSavePath);
        }

        var dt = System.nanoTime() - start;

        // A negative focal length is physically impossible, so reject the calibration
        if (ret != null && (ret.cameraIntrinsics.data[0] < 0 || ret.cameraIntrinsics.data[4] < 0)) {
            logger.error(
                    "Calibration produced a negative focal length (fx="
                            + ret.cameraIntrinsics.data[0]
                            + ", fy="
                            + ret.cameraIntrinsics.data[4]
                            + "); rejecting result");
            ret.release();
            ret = null;
        }

        if (ret != null) {
            logger.info(
                    "CALIBRATION SUCCESS for res "
                            + in.observations.get(0).size
                            + " in "
                            + dt / 1e6
                            + "ms! camMatrix: \n"
                            + Arrays.toString(ret.cameraIntrinsics.data)
                            + "\ndistortionCoeffs:\n"
                            + Arrays.toString(ret.distCoeffs.data)
                            + "\n");
        } else {
            logger.info("Calibration failed! Review log for more details");
        }

        return ret;
    }

    protected CameraCalibrationCoefficients calibrateOpenCV(
            List<FindBoardCornersPipe.FindBoardCornersPipeResult> observationCorners,
            FrameStaticProperties imageProps,
            Path imageSavePath) {
        // The observation levels are ignored since they are never used to skip points with the
        // current detectors. If this changes, the relevant points should be filtered out so they are
        // not processed.

        Mat cameraMatrix = new Mat(3, 3, CvType.CV_64F);
        MatOfDouble distortionCoefficients = new MatOfDouble();
        List<Mat> rvecs = new ArrayList<>();
        List<Mat> tvecs = new ArrayList<>();

        // initial camera matrix guess
        double cx = (observationCorners.get(0).size.width / 2.0) - 0.5;
        double cy = (observationCorners.get(0).size.height / 2.0) - 0.5;
        cameraMatrix.put(
                0,
                0,
                new double[] {
                    imageProps.horizontalFocalLength, 0, cx, 0, imageProps.verticalFocalLength, cy, 0, 0, 1
                });

        try {
            // FindBoardCorners pipe outputs all the image points, object points, and frames to
            // calculate imageSize from, other parameters are output Mats

            Calib3d.calibrateCameraExtended(
                    observationCorners.stream().map(it -> (Mat) it.objectPoints).toList(),
                    observationCorners.stream().map(it -> (Mat) it.imagePoints).toList(),
                    new Size(observationCorners.get(0).size.width, observationCorners.get(0).size.height),
                    cameraMatrix,
                    distortionCoefficients,
                    rvecs,
                    tvecs,
                    stdDeviationsIntrinsics,
                    stdDeviationsExtrinsics,
                    perViewErrors,
                    // Spectrum (photonvision-91): 8 coefficients, as mrcal gives; 5 can't follow
                    // our lenses out to the corners (SpectrumJetson tests/calibration-analysis)
                    Calib3d.CALIB_USE_LU
                            + Calib3d.CALIB_USE_INTRINSIC_GUESS
                            + Calib3d.CALIB_RATIONAL_MODEL);
            lastFxSigma = stdDeviationsIntrinsics.get(0, 0)[0];
            lastSolver = "OpenCV";
        } catch (Exception e) {
            logger.error("Calibration failed!", e);
            e.printStackTrace();
            cameraMatrix.release();
            distortionCoefficients.release();
            return null;
        }

        JsonMatOfDouble cameraMatrixMat = JsonMatOfDouble.fromMat(cameraMatrix);
        JsonMatOfDouble distortionCoefficientsMat = JsonMatOfDouble.fromMat(distortionCoefficients);

        // Opencv is lame, so we can only assume all points are inliers
        var inliners =
                observationCorners.stream()
                        .map(
                                it -> {
                                    var array = new boolean[it.objectPoints.rows() * it.objectPoints.cols()];
                                    Arrays.fill(array, true);
                                    return array;
                                })
                        .toList();

        var observations =
                createObservations(
                        observationCorners,
                        cameraMatrix,
                        distortionCoefficients,
                        rvecs,
                        tvecs,
                        inliners,
                        new double[] {0, 0},
                        imageSavePath);

        cameraMatrix.release();
        distortionCoefficients.release();
        rvecs.forEach(Mat::release);
        tvecs.forEach(Mat::release);

        return new CameraCalibrationCoefficients(
                observationCorners.get(0).size,
                cameraMatrixMat,
                distortionCoefficientsMat,
                new double[0],
                observations,
                new Size(params.boardWidth, params.boardHeight),
                params.squareSize,
                CameraLensModel.LENSMODEL_OPENCV,
                null);
    }

    protected CameraCalibrationCoefficients calibrateMrcal(
            List<FindBoardCornersPipe.FindBoardCornersPipeResult> observationCorners,
            FrameStaticProperties imageProps,
            Path imageSavePath) {
        List<MrCalObservation> observationData =
                observationCorners.stream()
                        .map(
                                observation -> {
                                    var corners = observation.imagePoints.toArray();

                                    var levels = new float[(int) observation.imagePoints.total()];
                                    Arrays.fill(levels, observation.level);

                                    var ids = observation.ids != null ? observation.ids.toArray() : null;

                                    return new MrCalObservation(corners, levels, ids);
                                })
                        .toList();

        int imageWidth = (int) observationCorners.get(0).size.width;
        int imageHeight = (int) observationCorners.get(0).size.height;

        MrCalResult result =
                MrCalJNI.calibrateCamera(
                        observationData,
                        params.boardWidth,
                        params.boardHeight,
                        params.squareSize,
                        imageWidth,
                        imageHeight,
                        (imageProps.horizontalFocalLength + imageProps.verticalFocalLength) / 2.0);

        // intrinsics are fx fy cx cy from mrcal
        JsonMatOfDouble cameraMatrixMat =
                new JsonMatOfDouble(
                        3,
                        3,
                        CvType.CV_64FC1,
                        new double[] {
                            // fx 0 cx
                            result.intrinsics[0],
                            0,
                            result.intrinsics[2],
                            // 0 fy cy
                            0,
                            result.intrinsics[1],
                            result.intrinsics[3],
                            // 0 0 1
                            0,
                            0,
                            1
                        });
        JsonMatOfDouble distortionCoefficientsMat =
                new JsonMatOfDouble(1, 8, CvType.CV_64FC1, Arrays.copyOfRange(result.intrinsics, 4, 12));

        // Spectrum (photonvision-91): mrcal's cornersUsed is indexed by board corner id; put it on
        // the detections (see CornerAlignment).
        List<boolean[]> used = new ArrayList<>();
        for (int i = 0; i < observationCorners.size(); i++) {
            var obs = observationCorners.get(i);
            used.add(
                    CornerAlignment.perDetection(
                            result.cornersUsed.get(i),
                            obs.ids != null ? obs.ids.toArray() : null,
                            (int) obs.imagePoints.total()));
        }
        lastSolver = "mrcal";

        if (params.keepEdgeCorners) {
            var refit =
                    refitKeepingEdgeCorners(
                            observationCorners,
                            cameraMatrixMat,
                            distortionCoefficientsMat,
                            used,
                            imageSavePath);
            if (refit != null) {
                cameraMatrixMat.release();
                distortionCoefficientsMat.release();
                return refit;
            }
        }

        // We get these from the JNI (retsult.optimizedPoses), but these are subtly different from the
        // ones our code used to produce. To preserve consistency, continue to redo this math.
        // Spectrum (photonvision-91): from the inliers only
        List<Mat> rvecs = new ArrayList<>();
        List<Mat> tvecs = new ArrayList<>();
        for (int i = 0; i < observationCorners.size(); i++) {
            var rt =
                    solvePose(
                            observationCorners.get(i),
                            used.get(i),
                            cameraMatrixMat.getAsMatOfDouble(),
                            distortionCoefficientsMat.getAsMatOfDouble());
            rvecs.add(rt[0]);
            tvecs.add(rt[1]);
        }

        List<BoardObservation> observations =
                createObservations(
                        observationCorners,
                        cameraMatrixMat.getAsMatOfDouble(),
                        distortionCoefficientsMat.getAsMatOfDouble(),
                        rvecs,
                        tvecs,
                        used,
                        new double[] {result.warp_x, result.warp_y},
                        imageSavePath);

        rvecs.forEach(Mat::release);
        tvecs.forEach(Mat::release);

        return new CameraCalibrationCoefficients(
                observationCorners.get(0).size,
                cameraMatrixMat,
                distortionCoefficientsMat,
                new double[] {result.warp_x, result.warp_y},
                observations,
                new Size(params.boardWidth, params.boardHeight),
                params.squareSize,
                CameraLensModel.LENSMODEL_OPENCV,
                // If we only saved rvecs/tvecs (in our BoardObservations), it will break mrcal uncertainty
                // calculation -- mrcal needs the optimization state vector at the solution. So save both
                new OptimizationInputs(result.optimizedPoses));
    }

    /**
     * Spectrum (photonvision-91): the board pose from the corners flagged used (all of them if fewer
     * than 4 are). Returns {rvec, tvec}; the caller releases them.
     */
    private static Mat[] solvePose(
            FindBoardCornersPipe.FindBoardCornersPipeResult obs,
            boolean[] used,
            Mat cameraMatrix,
            MatOfDouble dist) {
        var obj = obs.objectPoints.toList();
        var img = obs.imagePoints.toList();
        var o = new ArrayList<Point3>();
        var p = new ArrayList<Point>();
        for (int i = 0; i < img.size(); i++) {
            if (used[i]) {
                o.add(obj.get(i));
                p.add(img.get(i));
            }
        }
        if (o.size() < 4) {
            o = new ArrayList<>(obj);
            p = new ArrayList<>(img);
        }
        var om = new MatOfPoint3f();
        var pm = new MatOfPoint2f();
        om.fromList(o);
        pm.fromList(p);
        var rvec = new Mat();
        var tvec = new Mat();
        try {
            Calib3d.solvePnP(om, pm, cameraMatrix, dist, rvec, tvec);
        } finally {
            om.release();
            pm.release();
        }
        return new Mat[] {rvec, tvec};
    }

    /** Spectrum (photonvision-91): each detection's residual (pixels) under a pose and model. */
    private static double[] residuals(
            FindBoardCornersPipe.FindBoardCornersPipeResult obs,
            Mat rvec,
            Mat tvec,
            Mat cameraMatrix,
            MatOfDouble dist) {
        var projected = new MatOfPoint2f();
        try {
            Calib3d.projectPoints(obs.objectPoints, rvec, tvec, cameraMatrix, dist, projected);
            var pr = projected.toArray();
            var img = obs.imagePoints.toArray();
            double[] r = new double[img.length];
            for (int i = 0; i < img.length; i++) {
                r[i] = Math.hypot(pr[i].x - img[i].x, pr[i].y - img[i].y);
            }
            return r;
        } finally {
            projected.release();
        }
    }

    // Spectrum (photonvision-91): re-admitting mrcal's outliers. A detection comes back in if its
    // residual under the current model is within max(FLOOR, K x robust sigma).
    private static final double READMIT_FLOOR_PX = 1.0;
    private static final double READMIT_SIGMAS = 5.0;
    private static final int MIN_CORNERS_IN_SOLVE = 6;

    /**
     * Spectrum (photonvision-91): keep the edge corners mrcal threw away.
     *
     * <p>mrcal's outlier rejection (inside the native solver, not adjustable from here) judges each
     * corner against a model fitted mostly to the centre, where nearly all corners are. Edge and
     * corner detections, which a model that folds near the edge fits worst, were the ones dropped:
     * re-fitted with them kept, they come out at 0.23-0.43 px, and the result measured tags at the
     * field better (median -0.23 px, p90 -40%; SpectrumJetson tests/calibration-analysis §4-5).
     *
     * <p>So: start from mrcal's solution, take back every corner within max(1 px, 5 sigma) of its
     * model (sigma robust, from the median residual), refit the 8-coefficient model with OpenCV, and
     * repeat the test against the refit. The refit is used unless it folds inside the image where
     * mrcal's didn't. It has no board warp, and its covariance gives fx's spread for the quality
     * readout.
     *
     * @return the refit calibration, or null to keep mrcal's
     */
    private CameraCalibrationCoefficients refitKeepingEdgeCorners(
            List<FindBoardCornersPipe.FindBoardCornersPipeResult> obs,
            JsonMatOfDouble mrcalK,
            JsonMatOfDouble mrcalD,
            List<boolean[]> mrcalUsed,
            Path imageSavePath) {
        Mat k = mrcalK.getAsMatOfDouble().clone();
        MatOfDouble d = new MatOfDouble();
        mrcalD.getAsMatOfDouble().copyTo(d);
        List<boolean[]> used = mrcalUsed;
        try {
            int before = count(mrcalUsed);
            for (int pass = 0; pass < 2; pass++) {
                var res = new ArrayList<double[]>();
                for (int i = 0; i < obs.size(); i++) {
                    var rt = solvePose(obs.get(i), used.get(i), k, d);
                    res.add(residuals(obs.get(i), rt[0], rt[1], k, d));
                    rt[0].release();
                    rt[1].release();
                }
                double thr = Math.max(READMIT_FLOOR_PX, READMIT_SIGMAS * robustSigma(res, used));
                used = new ArrayList<>();
                for (var r : res) {
                    boolean[] u = new boolean[r.length];
                    for (int j = 0; j < r.length; j++) u[j] = r[j] <= thr;
                    used.add(u);
                }
                if (!calibrateSubset(obs, used, k, d)) {
                    logger.warn("Edge-corner refit failed; keeping mrcal's solution");
                    return null;
                }
                logger.info(
                        "Edge-corner refit pass "
                                + pass
                                + ": threshold "
                                + String.format("%.2f", thr)
                                + " px, corners used "
                                + count(used)
                                + " (mrcal kept "
                                + before
                                + ")");
            }

            var size = obs.get(0).size;
            double[] kArr = new double[9];
            k.get(0, 0, kArr);
            double[] dArr = d.toArray();
            var foldRefit = LensFoldCheck.check(kArr, dArr, size.width, size.height);
            var foldMrcal = LensFoldCheck.check(mrcalK.data, mrcalD.data, size.width, size.height);
            if (foldRefit.folds() && !foldMrcal.folds()) {
                logger.warn(
                        "Edge-corner refit folds inside the image and mrcal's doesn't; keeping mrcal's");
                return null;
            }

            // Final poses and residuals, with the final flags
            List<Mat> rvecs = new ArrayList<>();
            List<Mat> tvecs = new ArrayList<>();
            List<Pose3d> poses = new ArrayList<>();
            for (int i = 0; i < obs.size(); i++) {
                var rt = solvePose(obs.get(i), used.get(i), k, d);
                rvecs.add(rt[0]);
                tvecs.add(rt[1]);
                double[] r = new double[3], t = new double[3];
                rt[0].get(0, 0, r);
                rt[1].get(0, 0, t);
                poses.add(
                        new Pose3d(
                                new Translation3d(t[0], t[1], t[2]),
                                new Rotation3d(VecBuilder.fill(r[0], r[1], r[2]))));
            }
            var kJson = JsonMatOfDouble.fromMat(k);
            var dJson = new JsonMatOfDouble(1, dArr.length, CvType.CV_64FC1, dArr);
            var observations =
                    createObservations(obs, k, d, rvecs, tvecs, used, new double[] {0, 0}, imageSavePath);
            rvecs.forEach(Mat::release);
            tvecs.forEach(Mat::release);
            lastSolver =
                    "mrcal, refit keeping edge corners ("
                            + count(used)
                            + " used, mrcal kept "
                            + before
                            + ")";
            return new CameraCalibrationCoefficients(
                    size,
                    kJson,
                    dJson,
                    new double[] {0, 0},
                    observations,
                    new Size(params.boardWidth, params.boardHeight),
                    params.squareSize,
                    CameraLensModel.LENSMODEL_OPENCV,
                    // mrcal's uncertainty is evaluated at this solution (no warp)
                    new OptimizationInputs(poses));
        } catch (Exception e) {
            logger.error("Edge-corner refit failed; keeping mrcal's solution", e);
            return null;
        } finally {
            k.release();
            d.release();
        }
    }

    private static int count(List<boolean[]> used) {
        int n = 0;
        for (var u : used) for (boolean b : u) if (b) n++;
        return n;
    }

    /**
     * Per-axis sigma from the median residual magnitude of the used corners (a 2D Gaussian's
     * magnitude has median 1.1774 sigma), so a few bad corners don't inflate it.
     */
    static double robustSigma(List<double[]> res, List<boolean[]> used) {
        var all = new ArrayList<Double>();
        for (int i = 0; i < res.size(); i++) {
            for (int j = 0; j < res.get(i).length; j++) if (used.get(i)[j]) all.add(res.get(i)[j]);
        }
        if (all.isEmpty()) return 0;
        all.sort(Double::compare);
        return all.get(all.size() / 2) / 1.1774;
    }

    /** OpenCV 8-coefficient refit from (k, d) on the used corners; updates k and d in place. */
    private boolean calibrateSubset(
            List<FindBoardCornersPipe.FindBoardCornersPipeResult> obs,
            List<boolean[]> used,
            Mat k,
            MatOfDouble d) {
        List<Mat> objs = new ArrayList<>();
        List<Mat> imgs = new ArrayList<>();
        for (int i = 0; i < obs.size(); i++) {
            var o = obs.get(i).objectPoints.toList();
            var p = obs.get(i).imagePoints.toList();
            var oo = new ArrayList<Point3>();
            var pp = new ArrayList<Point>();
            for (int j = 0; j < p.size(); j++) {
                if (used.get(i)[j]) {
                    oo.add(o.get(j));
                    pp.add(p.get(j));
                }
            }
            if (oo.size() < MIN_CORNERS_IN_SOLVE) continue;
            var om = new MatOfPoint3f();
            om.fromList(oo);
            var pm = new MatOfPoint2f();
            pm.fromList(pp);
            objs.add(om);
            imgs.add(pm);
        }
        List<Mat> rvecs = new ArrayList<>();
        List<Mat> tvecs = new ArrayList<>();
        try {
            if (objs.size() < 3) return false;
            Calib3d.calibrateCameraExtended(
                    objs,
                    imgs,
                    obs.get(0).size,
                    k,
                    d,
                    rvecs,
                    tvecs,
                    stdDeviationsIntrinsics,
                    stdDeviationsExtrinsics,
                    perViewErrors,
                    Calib3d.CALIB_USE_LU
                            + Calib3d.CALIB_USE_INTRINSIC_GUESS
                            + Calib3d.CALIB_RATIONAL_MODEL);
            lastFxSigma = stdDeviationsIntrinsics.get(0, 0)[0];
            return k.get(0, 0)[0] > 0 && k.get(1, 1)[0] > 0;
        } catch (Exception e) {
            logger.error("OpenCV refit failed", e);
            return false;
        } finally {
            objs.forEach(Mat::release);
            imgs.forEach(Mat::release);
            rvecs.forEach(Mat::release);
            tvecs.forEach(Mat::release);
        }
    }

    private List<BoardObservation> createObservations(
            List<FindBoardCornersPipe.FindBoardCornersPipeResult> observationData,
            Mat cameraMatrix_,
            MatOfDouble distortionCoefficients_,
            List<Mat> rvecs,
            List<Mat> tvecs,
            List<boolean[]> cornersUsed,
            double[] calobject_warp,
            Path imageSavePath) {
        // Clear the calibration image folder of any old images before we save the new ones.
        try {
            FileUtils.cleanDirectory(imageSavePath.toFile());
        } catch (Exception e) {
            logger.error("Failed to clean calibration image directory", e);
        }

        // For each observation, calc reprojection error
        List<BoardObservation> observations = new ArrayList<>();
        for (int snapshotId = 0; snapshotId < observationData.size(); snapshotId++) {
            // Copy object points to a new mat to allow warp modification without affecting underlying
            // data
            observationData.get(snapshotId).objectPoints.copyTo(objectPoints);

            List<Point> iPoints = observationData.get(snapshotId).imagePoints.toList();

            if (objectPoints.rows() != iPoints.size()) {
                var rows = objectPoints.rows();
                objectPoints.release();
                throw new RuntimeException(
                        "Objpts size ("
                                + rows
                                + ") != imgpts size ("
                                + iPoints.size()
                                + ") for snapshot "
                                + snapshotId
                                + "!");
            }

            // Apply warp, if set
            if (calobject_warp != null && calobject_warp.length == 2) {
                // mrcal warp model!
                // The chessboard spans [-1, 1] on the x and y axies. We then let
                // z=k_x(1-x^2)+k_y(1-y^2)

                double xmin = 0;
                double ymin = 0;
                double xmax = params.boardWidth * params.squareSize;
                double ymax = params.boardHeight * params.squareSize;
                double k_x = calobject_warp[0];
                double k_y = calobject_warp[1];

                // Convert to list, remap z, and back to cv::Mat
                var oPoints = objectPoints.toArray();
                for (var pt : oPoints) {
                    double x_norm = MathUtils.map(pt.x, xmin, xmax, -1, 1);
                    double y_norm = MathUtils.map(pt.y, ymin, ymax, -1, 1);
                    pt.z = k_x * (1 - x_norm * x_norm) + k_y * (1 - y_norm * y_norm);
                }
                objectPoints.fromArray(oPoints);
            }

            // Project distorted object points to image space
            try {
                Calib3d.projectPoints(
                        objectPoints,
                        rvecs.get(snapshotId),
                        tvecs.get(snapshotId),
                        cameraMatrix_,
                        distortionCoefficients_,
                        projectedMat);
            } catch (Exception e) {
                e.printStackTrace();
                continue;
            }

            // Calculate reprojection error for each point. Spectrum (photonvision-91): for every
            // corner, outliers too, so reprojectionError[i] is corner i's (cornersUsed says which
            // were used). Upstream skipped the outliers, and the list no longer lined up.
            var reprojectionError = new ArrayList<Point>();
            var projectedPoints = projectedMat.toList();
            for (int pointIdx = 0; pointIdx < projectedPoints.size(); pointIdx++) {

                // error = (measured - expected)
                var measured = projectedPoints.get(pointIdx);
                var expected = iPoints.get(pointIdx);

                // Sanity check -- negative corners make no sense here
                if (!(measured.x >= 0 && measured.y >= 0 && expected.x >= 0 && expected.y >= 0)) {
                    logger.warn(
                            "Negative corner in reprojection error calc! Measured: "
                                    + measured
                                    + ", expected: "
                                    + expected);
                }

                var error = new Point(measured.x - expected.x, measured.y - expected.y);
                reprojectionError.add(error);
            }

            var camToBoard = MathUtils.opencvRTtoPose3d(rvecs.get(snapshotId), tvecs.get(snapshotId));

            var inputImage = observationData.get(snapshotId).inputImage;
            Path image_path = null;
            String snapshotName = "img" + snapshotId + ".png";
            if (inputImage != null) {
                image_path = Paths.get(imageSavePath.toString(), snapshotName);
                Imgcodecs.imwrite(image_path.toString(), inputImage);
            }

            var cornerIdsMat = observationData.get(snapshotId).ids;
            var cornerIds = cornerIdsMat != null ? cornerIdsMat.toArray() : null;

            observations.add(
                    new BoardObservation(
                            objectPoints.toList(),
                            iPoints,
                            reprojectionError,
                            camToBoard,
                            cornerIds,
                            cornersUsed.get(snapshotId),
                            snapshotName,
                            image_path));
        }

        return observations;
    }

    public static class CalibratePipeParams {
        // Size (in # of corners) of the calibration object
        public int boardHeight;
        public int boardWidth;
        // And size of each square
        public double squareSize;

        public boolean useMrCal;

        // Spectrum (photonvision-91): re-admit mrcal's outliers that fit, and refit (see
        // refitKeepingEdgeCorners)
        public boolean keepEdgeCorners;

        public CalibratePipeParams(
                int boardHeightSquares, int boardWidthSquares, double squareSize, boolean usemrcal) {
            this(boardHeightSquares, boardWidthSquares, squareSize, usemrcal, false);
        }

        public CalibratePipeParams(
                int boardHeightSquares,
                int boardWidthSquares,
                double squareSize,
                boolean usemrcal,
                boolean keepEdgeCorners) {
            this.boardHeight = boardHeightSquares - 1;
            this.boardWidth = boardWidthSquares - 1;
            this.squareSize = squareSize;
            this.useMrCal = usemrcal;
            this.keepEdgeCorners = keepEdgeCorners;
        }
    }

    @Override
    public void release() {
        stdDeviationsIntrinsics.release();
        stdDeviationsExtrinsics.release();
        perViewErrors.release();
        objectPoints.release();
        projectedMat.release();
    }
}
