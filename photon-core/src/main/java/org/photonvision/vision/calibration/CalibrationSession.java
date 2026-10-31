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

package org.photonvision.vision.calibration;

import io.avaje.jsonb.Json;
import java.util.ArrayList;
import java.util.List;

/**
 * Spectrum (photonvision-91): the calibration view's bookkeeping for one calibration session: each
 * snapshot's stats, the coverage grid, the distance and tilt quotas, when auto-capture should take
 * a snapshot, and where to move the board next. No OpenCV here (the pipeline measures distance and
 * tilt), so it can be unit tested.
 *
 * <p>Quotas from SpectrumJetson tests/calibration-analysis RESULTS.md: board distance over {@value
 * #NEAR_M}-{@value #FAR_SPAN_M} m with far shots (≥ {@value #FAR_M} m) in at least {@value
 * #FAR_SHARE} of snapshots, and tilts of {@value #TILT_MIN_DEG}-{@value #TILT_MAX_DEG}° in at least
 * {@value #TILT_SHARE} of them (pins fx against distortion).
 */
public final class CalibrationSession {
    public static final double NEAR_M = 0.4;
    public static final double FAR_M = 1.2;
    public static final double FAR_SPAN_M = 1.5;
    public static final double FAR_SHARE = 0.15;
    public static final double TILT_MIN_DEG = 20;
    public static final double TILT_MAX_DEG = 45;
    public static final double TILT_SHARE = 0.30;

    /** Auto-capture: corners added to cells short of target (see CalibrationCoverage.gain). */
    public static final int MIN_GAIN = 10;

    /**
     * One snapshot.
     *
     * @param distanceM board distance, metres (-1 unknown)
     * @param tiltDeg board tilt from facing the camera, degrees (-1 unknown)
     * @param gain coverage it added when taken
     */
    @Json
    public record SnapshotStats(
            int corners,
            double distanceM,
            double tiltDeg,
            double centreX,
            double centreY,
            int gain,
            boolean auto) {}

    /**
     * Where to put the board next: the ghost outline's centre and size in image pixels, the tilt to
     * use (axis "x" = top edge away or toward, "y" = a side edge away; "" for flat), the distance,
     * and a sentence for the user. cell is -1 when everything is met.
     */
    @Json
    public record Guidance(
            int cell,
            double x,
            double y,
            double w,
            double h,
            String tiltAxis,
            double tiltDeg,
            double distanceM,
            String text) {}

    @Json
    public record Summary(
            CalibrationCoverage.Summary coverage,
            List<SnapshotStats> snapshots,
            int far,
            int farTarget,
            int tilted,
            int tiltedTarget,
            double minDistanceM,
            double maxDistanceM,
            Guidance guidance) {}

    private final double width, height, fx, boardW, boardH;
    private final List<SnapshotStats> stats = new ArrayList<>();
    private final List<double[][]> corners = new ArrayList<>();
    private CalibrationCoverage coverage;

    /**
     * @param fx approximate focal length, pixels
     * @param boardWidthM board's printed width (squares x square size), metres
     * @param boardHeightM board's printed height, metres
     */
    public CalibrationSession(
            double width, double height, double fx, double boardWidthM, double boardHeightM) {
        this.width = width;
        this.height = height;
        this.fx = fx;
        this.boardW = boardWidthM;
        this.boardH = boardHeightM;
        this.coverage = new CalibrationCoverage(width, height);
    }

    public synchronized SnapshotStats add(
            double[] x, double[] y, double distanceM, double tiltDeg, boolean auto) {
        int gain = coverage.gain(x, y);
        var s = new SnapshotStats(x.length, distanceM, tiltDeg, mean(x), mean(y), gain, auto);
        stats.add(s);
        corners.add(new double[][] {x.clone(), y.clone()});
        coverage.add(x, y);
        return s;
    }

    public synchronized void remove(int index) {
        if (index < 0 || index >= stats.size()) throw new IndexOutOfBoundsException(index);
        stats.remove(index);
        corners.remove(index);
        coverage = new CalibrationCoverage(width, height);
        for (var c : corners) coverage.add(c[0], c[1]);
    }

    public synchronized void clear() {
        stats.clear();
        corners.clear();
        coverage = new CalibrationCoverage(width, height);
    }

    public synchronized int size() {
        return stats.size();
    }

    public synchronized int gain(double[] x, double[] y) {
        return coverage.gain(x, y);
    }

    public static boolean isFar(double d) {
        return d >= FAR_M;
    }

    public static boolean isTilted(double t) {
        return t >= TILT_MIN_DEG && t <= TILT_MAX_DEG;
    }

    private int farCount() {
        return (int) stats.stream().filter(s -> isFar(s.distanceM())).count();
    }

    private int tiltedCount() {
        return (int) stats.stream().filter(s -> isTilted(s.tiltDeg())).count();
    }

    // Targets grow with the session; at least a few of each from the start
    private int farTarget() {
        return Math.max(5, (int) Math.ceil(FAR_SHARE * stats.size()));
    }

    private int tiltedTarget() {
        return Math.max(8, (int) Math.ceil(TILT_SHARE * stats.size()));
    }

    public synchronized boolean needFar() {
        return farCount() < farTarget();
    }

    public synchronized boolean needTilt() {
        return tiltedCount() < tiltedTarget();
    }

    /**
     * A near-copy of a snapshot already taken: same place (3% of the diagonal), tilt and distance.
     */
    public synchronized boolean isDuplicate(double cx, double cy, double distanceM, double tiltDeg) {
        double diag = Math.hypot(width, height);
        for (var s : stats) {
            if (Math.hypot(s.centreX() - cx, s.centreY() - cy) < 0.03 * diag
                    && Math.abs(s.tiltDeg() - tiltDeg) < 5
                    && Math.abs(s.distanceM() - distanceM) < 0.05 * Math.max(distanceM, 0.1)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether auto-capture should take this (still) board: it isn't a near-copy, and it adds coverage
     * or fills the far or tilted quota.
     */
    public synchronized boolean wants(double[] x, double[] y, double distanceM, double tiltDeg) {
        if (x.length == 0 || isDuplicate(mean(x), mean(y), distanceM, tiltDeg)) return false;
        if (coverage.gain(x, y) >= MIN_GAIN) return true;
        if (needFar() && isFar(distanceM)) return true;
        return needTilt() && isTilted(tiltDeg);
    }

    public synchronized Guidance guidance() {
        int cell = coverage.emptiestCell();
        boolean far = needFar(), tilt = needTilt();
        if (cell < 0 && !far && !tilt) {
            return new Guidance(
                    -1, 0, 0, 0, 0, "", 0, 0, "Coverage complete: solve and check the quality.");
        }
        if (cell < 0)
            cell =
                    (CalibrationCoverage.ROWS / 2) * CalibrationCoverage.COLS + CalibrationCoverage.COLS / 2;
        int col = cell % CalibrationCoverage.COLS, row = cell / CalibrationCoverage.COLS;
        double cw = width / CalibrationCoverage.COLS, ch = height / CalibrationCoverage.ROWS;
        double dist = far ? 1.3 : 0.7;
        double w = fx > 0 ? fx * boardW / dist : width / 3;
        double h = fx > 0 ? fx * boardH / dist : height / 3;
        double x = (col + 0.5) * cw, y = (row + 0.5) * ch;
        // On the ring, the board hangs a quarter of the way off the edge: the corners right at
        // the edge are the ones calibrations lack, and a partial board is fine.
        if (col == 0) x = 0.25 * w;
        if (col == CalibrationCoverage.COLS - 1) x = width - 0.25 * w;
        if (row == 0) y = 0.25 * h;
        if (row == CalibrationCoverage.ROWS - 1) y = height - 0.25 * h;
        String axis = tilt ? (stats.size() % 2 == 0 ? "y" : "x") : "";
        double tiltDeg = tilt ? 30 : 0;

        var t = new StringBuilder();
        boolean ring = CalibrationCoverage.isRing(cell);
        t.append(ring ? "Move the board so it hangs off the " : "Move the board to the ");
        t.append(place(col, row));
        t.append(far ? String.format(", about %.1f m away", dist) : ", about 0.5-0.9 m away");
        if (tilt) {
            t.append(
                    axis.equals("x")
                            ? ", tilted about 30° (top edge away from the camera)"
                            : ", tilted about 30° (one side away from the camera)");
        }
        t.append(". Hold it still.");
        return new Guidance(cell, x, y, w, h, axis, tiltDeg, dist, t.toString());
    }

    private static String place(int col, int row) {
        String v = row <= 1 ? "top" : row >= CalibrationCoverage.ROWS - 2 ? "bottom" : "";
        String hz = col <= 1 ? "left" : col >= CalibrationCoverage.COLS - 2 ? "right" : "";
        if (v.isEmpty() && hz.isEmpty()) return "middle of the image";
        if (v.isEmpty()) return hz + " edge of the image";
        if (hz.isEmpty()) return v + " edge of the image";
        return v + "-" + hz + " corner of the image";
    }

    public synchronized Summary summary() {
        double min =
                stats.stream().mapToDouble(SnapshotStats::distanceM).filter(d -> d > 0).min().orElse(-1);
        double max =
                stats.stream().mapToDouble(SnapshotStats::distanceM).filter(d -> d > 0).max().orElse(-1);
        return new Summary(
                coverage.summary(),
                List.copyOf(stats),
                farCount(),
                farTarget(),
                tiltedCount(),
                tiltedTarget(),
                min,
                max,
                guidance());
    }

    private static double mean(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return a.length > 0 ? s / a.length : 0;
    }
}
