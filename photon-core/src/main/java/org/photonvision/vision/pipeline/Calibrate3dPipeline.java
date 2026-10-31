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

package org.photonvision.vision.pipeline;

import java.awt.Color;
import java.nio.file.Path;
import io.avaje.jsonb.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.opencv.calib3d.Calib3d;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;
import org.photonvision.common.dataflow.DataChangeService;
import org.photonvision.common.dataflow.events.OutgoingUIEvent;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.ColorHelper;
import org.photonvision.common.util.SerializationUtils;
import org.photonvision.vision.calibration.BoardObservation;
import org.photonvision.vision.calibration.CalibrationQuality;
import org.photonvision.vision.calibration.CalibrationSession;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.frame.Frame;
import org.photonvision.vision.frame.FrameThresholdType;
import org.photonvision.vision.opencv.CVMat;
import org.photonvision.vision.opencv.ImageRotationMode;
import org.photonvision.vision.pipe.CVPipe.CVPipeResult;
import org.photonvision.vision.pipe.impl.CalculateFPSPipe;
import org.photonvision.vision.pipe.impl.Calibrate3dPipe;
import org.photonvision.vision.pipe.impl.Calibrate3dPipe.CalibrationInput;
import org.photonvision.vision.pipe.impl.FindBoardCornersPipe;
import org.photonvision.vision.pipe.impl.FindBoardCornersPipe.FindBoardCornersPipeResult;
import org.photonvision.vision.pipeline.result.CVPipelineResult;
import org.photonvision.vision.pipeline.result.CalibrationPipelineResult;
import org.wpilib.math.util.Units;
import org.wpilib.util.Pair;

public class Calibrate3dPipeline
        extends CVPipeline<CVPipelineResult, Calibration3dPipelineSettings> {
    // For logging
    private static final Logger logger = new Logger(Calibrate3dPipeline.class, LogGroup.General);

    // Find board corners decides internally between opencv and mrgingham
    private final FindBoardCornersPipe findBoardCornersPipe = new FindBoardCornersPipe();
    private final Calibrate3dPipe calibrate3dPipe = new Calibrate3dPipe();
    private final CalculateFPSPipe calculateFPSPipe = new CalculateFPSPipe();

    // Getter methods have been set for calibrate and takeSnapshot
    private boolean takeSnapshot = false;

    // Percentage of the image diagonal the board must move before a new snapshot is captured
    private static final double MIN_MOVEMENT_FRACTION = 0.05;

    // Opacity of the red tint drawn over the exclusion zone's interior
    private static final double TINT_ALPHA = 0.3;

    private Point lastSnapshotCentroid = null;

    // Progress hint for the UI during auto calibration: null when no board is visible
    private Boolean movedFarEnough = null;

    // Output of the corners
    public final List<FindBoardCornersPipeResult> foundCornersList;

    /// Output of the calibration, getter method is set for this.
    private CVPipeResult<CameraCalibrationCoefficients> calibrationOutput;

    private boolean calibrating = false;

    private static final FrameThresholdType PROCESSING_TYPE = FrameThresholdType.NONE;

    public Calibrate3dPipeline() {
        super(PROCESSING_TYPE);
        this.settings = new Calibration3dPipelineSettings();
        this.foundCornersList = new ArrayList<>();
    }

    @Override
    protected void setPipeParamsImpl() {
        findBoardCornersPipe.setParams(
                new FindBoardCornersPipe.FindCornersPipeParams(
                        settings.boardHeight,
                        settings.boardWidth,
                        settings.boardType,
                        settings.tagFamily,
                        settings.gridSize,
                        settings.markerSize,
                        settings.streamingFrameDivisor,
                        settings.useOldPattern));

        calibrate3dPipe.setParams(
                new Calibrate3dPipe.CalibratePipeParams(
                        settings.boardHeight,
                        settings.boardWidth,
                        settings.gridSize,
                        settings.useMrCal,
                        settings.keepEdgeCorners));
    }

    @Override
    protected CVPipelineResult process(Frame frame, Calibration3dPipelineSettings settings) {
        Mat inputColorMat = frame.colorImage.getMat();

        if (this.calibrating || inputColorMat.empty()) {
            return new CVPipelineResult(frame.sequenceID, 0, 0, null, frame);
        }

        if (getSettings().inputImageRotationMode != ImageRotationMode.DEG_0) {
            // All this calibration assumes zero rotation. If we want a rotation, it should
            // be applied at
            // the output
            logger.error(
                    "Input image rotation was non-zero! Calibration wasn't designed to deal with this. Attempting to manually change back to zero");
            getSettings().inputImageRotationMode = ImageRotationMode.DEG_0;
            return new CVPipelineResult(frame.sequenceID, 0, 0, List.of(), frame);
        }

        long sumPipeNanosElapsed = 0L;

        // Check if the frame has chessboard corners
        var outputColorCVMat = new CVMat();
        inputColorMat.copyTo(outputColorCVMat.getMat());

        FindBoardCornersPipeResult findBoardResult =
                findBoardCornersPipe.run(Pair.of(inputColorMat, outputColorCVMat.getMat())).output;

        // Spectrum (photonvision-91): board motion, distance and tilt for the calibration view
        // and auto-capture
        updateLive(findBoardResult, frame);

        if (takeSnapshot) {
            // Set snapshot to false even if we don't find a board
            takeSnapshot = false;

            if (findBoardResult != null) {
                captureSnapshot(findBoardResult, inputColorMat, false);

                if (settings.autoCalibrate) {
                    // Keep auto calibration from immediately re-capturing at this pose
                    lastSnapshotCentroid = computeCentroid(findBoardResult.imagePoints.toList());
                }
            }
            broadcastState();
        } else if (settings.autoCalibrate) {
            // We don't call broadcast state since the function broadcasts user hints
            autoTakeSnapshot(findBoardResult, inputColorMat);
        }

        if (settings.showExclusionZone) {
            if (lastSnapshotCentroid != null) {
                drawExclusionZone(outputColorCVMat.getMat());
            }
            if (findBoardResult != null) {
                Point centroid = computeCentroid(findBoardResult.imagePoints.toList());
                drawCurrentCentroid(outputColorCVMat.getMat(), centroid);
            }
        }

        var fpsResult = calculateFPSPipe.run(null);
        var fps = fpsResult.output;

        offerViewFrame(inputColorMat);

        frame.release();

        // Return the drawn chessboard if corners are found, if not, then return the
        // input image.
        return new CalibrationPipelineResult(
                frame.sequenceID,
                sumPipeNanosElapsed,
                fps, // Unused but here in case
                new Frame(
                        frame.sequenceID,
                        new CVMat(),
                        outputColorCVMat,
                        FrameThresholdType.NONE,
                        frame.frameStaticProperties),
                getCornersList());
    }

    /**
     * Draws the last snapshot's centroid and the exclusion zone that the next snapshot's centroid
     * must be outside of for auto-calibration to capture it.
     */
    private void drawExclusionZone(Mat image) {
        var imgSz = image.size();
        double frameDiagonal = Math.hypot(imgSz.width, imgSz.height);

        var color = ColorHelper.colorToScalar(Color.RED);
        // Match the thickness/crosshair heuristics used by DrawCalibrationPipe
        int thickness = (int) Math.max(frameDiagonal * 1.0 / 600.0, 1);
        int crosshair = (int) Math.max(frameDiagonal * 4.0 / 750.0, 3);
        int radius = (int) Math.round(MIN_MOVEMENT_FRACTION * frameDiagonal);

        Point center = lastSnapshotCentroid;

        // Tint the zone's interior red so the excluded area is clearly visible
        Mat mask = Mat.zeros(image.size(), CvType.CV_8UC1);
        Mat tint = new Mat(image.size(), image.type(), color);
        Mat blended = new Mat();
        try {
            Imgproc.circle(mask, center, radius, new Scalar(255), Imgproc.FILLED);
            Core.addWeighted(tint, TINT_ALPHA, image, 1.0 - TINT_ALPHA, 0, blended);
            blended.copyTo(image, mask);
        } finally {
            mask.release();
            tint.release();
            blended.release();
        }

        Imgproc.circle(image, center, radius, color, thickness);
        Imgproc.line(
                image,
                new Point(center.x - crosshair, center.y),
                new Point(center.x + crosshair, center.y),
                color,
                thickness);
        Imgproc.line(
                image,
                new Point(center.x, center.y - crosshair),
                new Point(center.x, center.y + crosshair),
                color,
                thickness);
    }

    /** Draws the detected centroid of the board in the current frame as a bright green crosshair. */
    private void drawCurrentCentroid(Mat image, Point centroid) {
        double frameDiagonal = Math.hypot(image.size().width, image.size().height);

        var color = ColorHelper.colorToScalar(Color.GREEN);
        int thickness = (int) Math.max(frameDiagonal * 1.0 / 600.0, 1);
        int crosshair = (int) Math.max(frameDiagonal * 4.0 / 750.0, 3);

        Imgproc.line(
                image,
                new Point(centroid.x - crosshair, centroid.y),
                new Point(centroid.x + crosshair, centroid.y),
                color,
                thickness);
        Imgproc.line(
                image,
                new Point(centroid.x, centroid.y - crosshair),
                new Point(centroid.x, centroid.y + crosshair),
                color,
                thickness);
    }

    /** Adds a detected board to the snapshot list and updates the UI. */
    private synchronized void captureSnapshot(
            FindBoardCornersPipeResult findBoardResult, Mat inputColorMat, boolean auto) {
        // Only copy the image into the result when we absolutely must
        findBoardResult.inputImage = inputColorMat.clone();

        // Spectrum (photonvision-91): the session first (it's rebuilt from the list if it changed)
        var sess = session();
        foundCornersList.add(findBoardResult);
        var xy = xyOf(findBoardResult);
        var pose = boardPose(findBoardResult);
        sess.add(xy[0], xy[1], pose[0], pose[1], auto);
    }

    // ---- Spectrum (photonvision-91): calibration view ------------------------------------------

    // Auto-capture: the board is still when its corners move less than this between frames...
    private static final double STILL_PX_PER_FRAME = 0.3;
    // ...for this long
    private static final long STILL_NS = 300_000_000L;

    private CalibrationSession session;
    private String sessionKey = "";
    private Map<Integer, Point> lastCorners = null;
    private long stillSinceNs = 0;
    private double[] lastPose = {-1, -1};
    private boolean lastStill = false;
    private volatile Live live = null;
    private volatile long liveNs = 0;
    private volatile CalibrationQuality.Report lastQuality = null;

    /** The live view's state, for GET /api/calibration/live. */
    @Json
    public record Live(
            int width,
            int height,
            double[] corners, // x0, y0, x1, y1, ... of the board in view, or empty
            double motionPx,
            boolean still,
            int gain,
            double distanceM,
            double tiltDeg,
            boolean autoCalibrate,
            boolean calibrating,
            long ageMs,
            CalibrationSession.SessionSummary session) {}

    /**
     * The session for the current resolution and board, rebuilt from the snapshots if they changed.
     */
    private synchronized CalibrationSession session() {
        var fsp = frameStaticProperties;
        int w = fsp != null ? fsp.imageWidth : (int) settings.resolution.width;
        int h = fsp != null ? fsp.imageHeight : (int) settings.resolution.height;
        String key =
                w + "x" + h + "/" + settings.boardWidth + "x" + settings.boardHeight + "/"
                        + settings.gridSize;
        if (session == null || !key.equals(sessionKey)) {
            double fx = fsp != null ? fsp.horizontalFocalLength : 0;
            session =
                    new CalibrationSession(
                            w,
                            h,
                            fx,
                            settings.boardWidth * settings.gridSize,
                            settings.boardHeight * settings.gridSize);
            sessionKey = key;
            for (var r : foundCornersList) {
                var xy = xyOf(r);
                var pose = boardPose(r);
                session.add(xy[0], xy[1], pose[0], pose[1], false);
            }
        }
        return session;
    }

    private static double[][] xyOf(FindBoardCornersPipeResult r) {
        var pts = r.imagePoints.toArray();
        double[] x = new double[pts.length], y = new double[pts.length];
        for (int i = 0; i < pts.length; i++) {
            x[i] = pts[i].x;
            y[i] = pts[i].y;
        }
        return new double[][] {x, y};
    }

    /**
     * The board's distance (m) and tilt from facing the camera (degrees), from a pose solved with
     * the camera's current (or generic) focal length and no distortion: rough, but plenty for the
     * distance and tilt quotas. {-1, -1} if it can't be solved.
     */
    private double[] boardPose(FindBoardCornersPipeResult r) {
        var fsp = frameStaticProperties;
        if (fsp == null || r.objectPoints.total() < 4) return new double[] {-1, -1};
        var k = new Mat(3, 3, CvType.CV_64F);
        k.put(
                0,
                0,
                fsp.horizontalFocalLength,
                0,
                fsp.centerX,
                0,
                fsp.verticalFocalLength,
                fsp.centerY,
                0,
                0,
                1);
        var d = new MatOfDouble();
        var rvec = new Mat();
        var tvec = new Mat();
        var rot = new Mat();
        try {
            if (!Calib3d.solvePnP(r.objectPoints, r.imagePoints, k, d, rvec, tvec)) {
                return new double[] {-1, -1};
            }
            double[] t = new double[3];
            tvec.get(0, 0, t);
            Calib3d.Rodrigues(rvec, rot);
            // The board's normal is the rotation's third column; facing the camera, it's along z
            double nz = Math.abs(rot.get(2, 2)[0]);
            double tilt = Math.toDegrees(Math.acos(Math.min(1, nz)));
            return new double[] {Math.sqrt(t[0] * t[0] + t[1] * t[1] + t[2] * t[2]), tilt};
        } catch (Exception e) {
            return new double[] {-1, -1};
        } finally {
            k.release();
            d.release();
            rvec.release();
            tvec.release();
            rot.release();
        }
    }

    /** Corner motion since the last frame, by corner id (pixels, mean); infinite if unknown. */
    private double updateMotion(FindBoardCornersPipeResult r) {
        if (r == null) {
            lastCorners = null;
            return Double.POSITIVE_INFINITY;
        }
        var pts = r.imagePoints.toArray();
        int[] ids = r.ids != null ? r.ids.toArray() : null;
        var now = new HashMap<Integer, Point>();
        for (int i = 0; i < pts.length; i++) now.put(ids != null ? ids[i] : i, pts[i]);
        double sum = 0;
        int n = 0;
        if (lastCorners != null) {
            for (var e : now.entrySet()) {
                var p = lastCorners.get(e.getKey());
                if (p == null) continue;
                sum += Math.hypot(p.x - e.getValue().x, p.y - e.getValue().y);
                n++;
            }
        }
        lastCorners = now;
        return n >= 4 ? sum / n : Double.POSITIVE_INFINITY;
    }

    private void updateLive(FindBoardCornersPipeResult r, Frame frame) {
        long now = System.nanoTime();
        double motion = updateMotion(r);
        if (motion < STILL_PX_PER_FRAME) {
            if (stillSinceNs == 0) stillSinceNs = now;
        } else {
            stillSinceNs = 0;
        }
        lastStill = stillSinceNs != 0 && now - stillSinceNs >= STILL_NS;
        boolean viewing = now < viewUntilNs;
        if (!viewing && !settings.autoCalibrate) return;

        lastPose = r != null ? boardPose(r) : new double[] {-1, -1};
        if (!viewing) return;
        double[][] xy = r != null ? xyOf(r) : new double[][] {new double[0], new double[0]};
        var sess = session();
        int gain = r != null ? sess.gain(xy[0], xy[1]) : 0;
        double[] flat = new double[xy[0].length * 2];
        for (int i = 0; i < xy[0].length; i++) {
            flat[2 * i] = xy[0][i];
            flat[2 * i + 1] = xy[1][i];
        }
        live =
                new Live(
                        frame.frameStaticProperties.imageWidth,
                        frame.frameStaticProperties.imageHeight,
                        flat,
                        Double.isFinite(motion) ? motion : -1,
                        lastStill,
                        gain,
                        lastPose[0],
                        lastPose[1],
                        settings.autoCalibrate,
                        calibrating,
                        0,
                        sess.summary());
        liveNs = now;
    }

    /** The live state, and keep it (and the view's frames) coming for the next 3 s. */
    public Live live() {
        viewUntilNs = System.nanoTime() + VIEW_NS;
        var l = live;
        if (l == null) return null;
        // Snapshots taken or deleted since the last frame show at once
        var sess = session;
        return new Live(
                l.width(),
                l.height(),
                l.corners(),
                l.motionPx(),
                l.still(),
                l.gain(),
                l.distanceM(),
                l.tiltDeg(),
                l.autoCalibrate(),
                calibrating,
                (System.nanoTime() - liveNs) / 1_000_000L,
                sess != null ? sess.summary() : l.session());
    }

    private static final long VIEW_NS = 3_000_000_000L;
    private static final long VIEW_PERIOD_NS = 100_000_000L;
    private volatile long viewUntilNs = 0;
    private long nextViewNs = 0;
    private volatile byte[] viewJpeg = null;
    private final AtomicBoolean encoding = new AtomicBoolean(false);
    private final ExecutorService viewWorker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "CalibrationView");
                        t.setDaemon(true);
                        return t;
                    });

    /** The camera's image at full size, as a JPEG (null until the first), while the view is open. */
    public byte[] viewJpeg() {
        viewUntilNs = System.nanoTime() + VIEW_NS;
        return viewJpeg;
    }

    private void offerViewFrame(Mat image) {
        long now = System.nanoTime();
        if (now > viewUntilNs || now < nextViewNs || encoding.get() || image.empty()) return;
        nextViewNs = now + VIEW_PERIOD_NS;
        Mat copy = image.clone();
        encoding.set(true);
        viewWorker.execute(
                () -> {
                    var buf = new MatOfByte();
                    try {
                        if (Imgcodecs.imencode(
                                ".jpg", copy, buf, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85))) {
                            viewJpeg = buf.toArray();
                        }
                    } finally {
                        buf.release();
                        copy.release();
                        encoding.set(false);
                    }
                });
    }

    /** Grade a solved calibration (the view's quality readout and save gate). */
    public CalibrationQuality.Report evaluate(CameraCalibrationCoefficients cal) {
        List<org.opencv.core.Point3> uncertainty = null;
        if (cal.optimizationInputs != null) {
            try {
                uncertainty = cal.estimateUncertainty();
            } catch (Exception e) {
                logger.warn("No uncertainty map for this calibration: " + e.getMessage());
            }
        }
        var q =
                CalibrationQuality.evaluate(
                        cal, calibrate3dPipe.lastFxSigma(), calibrate3dPipe.lastSolver(), uncertainty);
        lastQuality = q;
        return q;
    }

    public CalibrationQuality.Report lastQuality() {
        return lastQuality;
    }

    /**
     * In auto mode, takes a snapshot if the board adds coverage and has been still for 0.3 s
     * (Spectrum, photonvision-91; upstream: moved 5% of the diagonal and steady for 3 frames). Also
     * updates the UI with a hint for when users ought to move the board.
     */
    private void autoTakeSnapshot(FindBoardCornersPipeResult findBoardResult, Mat inputColorMat) {
        Boolean newMovedFarEnough;
        boolean captured = false;

        if (findBoardResult == null) {
            newMovedFarEnough = null;
        } else {
            Point centroid = computeCentroid(findBoardResult.imagePoints.toList());
            // Spectrum (photonvision-91): a snapshot is taken when the corners have held still
            // (< 0.3 px a frame for 0.3 s, see updateLive) and it adds coverage: corners in cells
            // short of their target, or a far or tilted shot while those are short. Not when it's
            // a near-copy of one already taken. movedFarEnough: true = hold still, it's wanted;
            // false = move somewhere new.
            var xy = xyOf(findBoardResult);
            boolean wanted = session().wants(xy[0], xy[1], lastPose[0], lastPose[1]);
            if (!wanted) {
                newMovedFarEnough = false;
            } else if (!lastStill) {
                newMovedFarEnough = true;
            } else {
                newMovedFarEnough = false;
                movedFarEnough = newMovedFarEnough;
                captured = true;

                captureSnapshot(findBoardResult, inputColorMat, true);
                lastSnapshotCentroid = centroid;
                stillSinceNs = 0;

                logger.info("Automatically took calibration snapshot " + foundCornersList.size());
            }
        }

        if (captured) {
            // The snapshot count changed even if the hint didn't
            broadcastState();
        } else if (!Objects.equals(newMovedFarEnough, movedFarEnough)) {
            movedFarEnough = newMovedFarEnough;
            broadcastState();
        }
    }

    private static Point computeCentroid(List<Point> points) {
        Point centroid = new Point(0, 0);
        for (Point p : points) {
            centroid.x += p.x;
            centroid.y += p.y;
        }
        centroid.x /= points.size();
        centroid.y /= points.size();
        return centroid;
    }

    Boolean getMovedFarEnough() {
        return movedFarEnough;
    }

    List<List<Point>> getCornersList() {
        return foundCornersList.stream().map(it -> it.imagePoints.toList()).toList();
    }

    public synchronized CameraCalibrationCoefficients tryCalibration(Path imageSavePath) {
        this.calibrating = true;

        /*
         * Pass the board corners to the pipe, which will check again to see if all
         * boards are valid
         * and returns the corresponding image and object points
         */
        calibrationOutput =
                calibrate3dPipe.run(
                        new CalibrationInput(foundCornersList, frameStaticProperties, imageSavePath));

        this.calibrating = false;

        return calibrationOutput.output;
    }

    public void takeSnapshot() {
        takeSnapshot = true;
    }

    public List<BoardObservation> perViewErrors() {
        return calibrationOutput.output.observations;
    }

    public synchronized void finishCalibration() {
        foundCornersList.forEach(it -> it.release());
        foundCornersList.clear();
        if (session != null) session.clear();
        lastQuality = null;
        lastCorners = null;

        lastSnapshotCentroid = null;
        movedFarEnough = null;

        broadcastState();
    }

    public void broadcastState() {
        Map<String, Object> state =
                SerializationUtils.objectToHashMap(
                        new UICalibrationData(
                                foundCornersList.size(),
                                settings.cameraVideoModeIndex,
                                Units.metersToInches(settings.gridSize),
                                Units.metersToInches(settings.markerSize),
                                settings.boardWidth,
                                settings.boardHeight,
                                settings.boardType,
                                settings.useOldPattern,
                                settings.tagFamily,
                                settings.autoCalibrate,
                                movedFarEnough));

        DataChangeService.getInstance()
                .publishEvent(OutgoingUIEvent.wrappedOf("calibrationData", state));
    }

    public synchronized boolean removeSnapshot(int index) {
        try {
            // Spectrum (photonvision-91): the view's stats and coverage follow
            var sess = session();
            var observation = foundCornersList.remove(index);
            observation.release();
            sess.remove(index);
            lastQuality = null;
            broadcastState();
            return true;
        } catch (IndexOutOfBoundsException e) {
            logger.error("Could not remove snapshot at index " + index, e);
            return false;
        }
    }

    public CameraCalibrationCoefficients cameraCalibrationCoefficients() {
        return calibrationOutput.output;
    }

    @Override
    public void release() {
        foundCornersList.forEach(it -> it.release());
        foundCornersList.clear();

        findBoardCornersPipe.release();
        calibrate3dPipe.release();
        viewWorker.shutdown();
        calculateFPSPipe.release();
        super.release();
    }
}
