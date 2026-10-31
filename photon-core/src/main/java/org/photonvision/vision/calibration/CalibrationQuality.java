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
import org.opencv.core.Point;
import org.opencv.core.Point3;

/**
 * Spectrum (photonvision-91): a solved calibration's quality, and the pass/fail gate the
 * calibration view shows before saving. Criteria from SpectrumJetson tests/calibration-analysis
 * RESULTS.md §6.4: inlier rms ≤ {@value #MAX_RMS} px, no empty cell, the model doesn't fold inside
 * the image, and fx stable to ±{@value #MAX_FX_SPREAD} px (2σ from the solve's covariance).
 */
public final class CalibrationQuality {
    public static final double MAX_RMS = 0.25;
    public static final double MAX_FX_SPREAD = 1.0;

    private CalibrationQuality() {}

    // JSON has no NaN: unknown values are sent as -1
    private static double fin(double v) {
        return Double.isFinite(v) ? v : -1;
    }

    @Json
    public record Check(String name, boolean pass, String value, String hint) {}

    /** One outlier: where it is in the image, which snapshot, and its residual (pixels). */
    @Json
    public record Outlier(int snapshot, double x, double y, double error) {}

    @Json
    public record Report(
            boolean pass,
            List<Check> checks,
            double rms, // -1: unknown
            int inliers,
            int outliers,
            List<Outlier> outlierPositions,
            double[] snapshotRms,
            double fx,
            double fy,
            double cx,
            double cy,
            double fxSigma,
            double[] distortion,
            LensFoldCheck.Result fold,
            CalibrationCoverage.Summary coverage,
            String solver,
            List<Point3> uncertainty) {}

    /**
     * @param fxSigma fx's standard deviation from the solve (NaN if the solver didn't give one)
     * @param solver which solver produced it, for the readout
     * @param uncertainty mrcal's projection uncertainty grid (u, v, pixels), or null
     */
    public static Report evaluate(
            CameraCalibrationCoefficients cal, double fxSigma, String solver, List<Point3> uncertainty) {
        var coverage = new CalibrationCoverage(cal.resolution.width, cal.resolution.height);
        double sumSq = 0;
        int inliers = 0, outliers = 0;
        var outlierPositions = new ArrayList<Outlier>();
        double[] snapshotRms = new double[cal.observations.size()];
        for (int s = 0; s < cal.observations.size(); s++) {
            var obs = cal.observations.get(s);
            var pts = obs.locationInImageSpace;
            boolean aligned = obs.residualsAligned();
            var xs = new ArrayList<Double>();
            var ys = new ArrayList<Double>();
            double snapSq = 0;
            int snapN = 0;
            for (int i = 0; i < pts.size(); i++) {
                Point p = pts.get(i);
                if (p.x < 0 || p.y < 0) continue; // missing corner (old full-board layout)
                double err =
                        aligned
                                ? Math.hypot(obs.reprojectionErrors.get(i).x, obs.reprojectionErrors.get(i).y)
                                : Double.NaN;
                if (obs.cornersUsed[i]) {
                    xs.add(p.x);
                    ys.add(p.y);
                    inliers++;
                    if (!Double.isNaN(err)) {
                        sumSq += err * err;
                        snapSq += err * err;
                        snapN++;
                    }
                } else {
                    outliers++;
                    outlierPositions.add(new Outlier(s, p.x, p.y, fin(err)));
                }
            }
            snapshotRms[s] = snapN > 0 ? Math.sqrt(snapSq / snapN) : -1;
            coverage.add(
                    xs.stream().mapToDouble(Double::doubleValue).toArray(),
                    ys.stream().mapToDouble(Double::doubleValue).toArray());
        }
        double rms = inliers > 0 ? Math.sqrt(sumSq / inliers) : Double.NaN;
        var cov = coverage.summary();
        var fold = LensFoldCheck.check(cal);
        double[] k = cal.getIntrinsicsArr();

        var checks = new ArrayList<Check>();
        checks.add(
                new Check(
                        "Reprojection rms ≤ " + MAX_RMS + " px",
                        rms <= MAX_RMS,
                        String.format("%.3f px", rms),
                        "Check focus and lighting, hold the board still (auto-capture waits for it), and"
                                + " delete snapshots with a high rms below."));
        checks.add(
                new Check(
                        "No empty cell",
                        cov.emptyCells() == 0,
                        cov.emptyCells() + " empty, " + cov.cellsMet() + "/80 at target",
                        "Fill the empty cells (dark on the overlay): let the board hang off that edge or"
                                + " corner of the image, partial boards count."));
        checks.add(
                new Check(
                        "Model doesn't fold inside the image",
                        !fold.folds(),
                        fold.folds()
                                ? String.format(
                                        "folds at %.0f px, corner at %.0f px",
                                        fold.maxRadiusPx(), fold.cornerRadiusPx())
                                : String.format(
                                        "good to %.0f px (corner %.0f px)", fold.maxRadiusPx(), fold.cornerRadiusPx()),
                        "The lens model is extrapolating past where corners were seen. Get corners into the"
                                + " image corners (within 10 px of the edges) and solve again."));
        boolean fxKnown = !Double.isNaN(fxSigma);
        checks.add(
                new Check(
                        "fx stable to ±" + MAX_FX_SPREAD + " px",
                        fxKnown && 2 * fxSigma <= MAX_FX_SPREAD,
                        fxKnown ? String.format("fx %.2f ± %.2f px (2σ)", k[0], 2 * fxSigma) : "unknown",
                        fxKnown
                                ? "Add tilted shots (20-45° about both axes) and far ones (1.2-1.5 m): they"
                                        + " separate focal length from distortion."
                                : "This solver gave no spread: turn on Keep edge corners and solve again."));
        boolean pass = checks.stream().allMatch(Check::pass);
        return new Report(
                pass,
                checks,
                fin(rms),
                inliers,
                outliers,
                outlierPositions,
                snapshotRms,
                k[0],
                k[4],
                k[2],
                k[5],
                fin(fxSigma),
                cal.getDistCoeffsArr().clone(),
                fold,
                cov,
                solver,
                uncertainty);
    }
}
