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

package org.photonvision.vision.processes;

import io.avaje.jsonb.Json;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;
import org.photonvision.common.dataflow.CVPipelineResultConsumer;
import org.photonvision.vision.pipeline.result.CVPipelineResult;

/**
 * SpectrumJetson (photonvision-33): a focus score for turning a camera's lens, like Limelight's.
 *
 * <p>Only while someone is looking (a request in the last 3 s), up to 5 times a second, it measures
 * the variance of the Laplacian of the camera's input image on a 3x3 grid. The centre cell is the
 * main score, and the others show a tilted lens or soft corners. Higher is sharper.
 *
 * <p>The score depends on the scene, so compare values while turning the lens with the camera and
 * scene still. "Best" is the centre's peak since the last reset. The vision thread only copies the
 * image; the measurement runs on its own thread.
 */
public class FocusMeter implements CVPipelineResultConsumer {
    private static final long WANTED_NS = 3_000_000_000L;
    private static final long PERIOD_NS = 200_000_000L;

    /** The latest measurement: grid is row-major, cell 4 is the centre. */
    @Json
    public record Reading(double[] grid, double centre, double best, int width, int height, long ageMs) {}

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    r -> {
                        Thread t = new Thread(r, "FocusMeter");
                        t.setDaemon(true);
                        return t;
                    });
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile long wantedUntilNs = 0;
    private long nextNs = 0;
    private volatile double[] grid = null;
    private volatile double best = 0;
    private volatile long measuredNs = 0;
    private volatile int width = 0, height = 0;

    /** The latest reading, and keep measuring for the next 3 s. Null until the first one. */
    public Reading read() {
        wantedUntilNs = System.nanoTime() + WANTED_NS;
        double[] g = grid;
        if (g == null) return null;
        return new Reading(
                g.clone(), g[4], best, width, height, (System.nanoTime() - measuredNs) / 1_000_000L);
    }

    public void resetBest() {
        best = 0;
    }

    @Override
    public void accept(CVPipelineResult result) {
        long now = System.nanoTime();
        if (now > wantedUntilNs || now < nextNs || busy.get()) return;
        var frame = result.inputAndOutputFrame;
        if (frame == null || frame.colorImage == null) return;
        Mat src = frame.colorImage.getMat();
        if (src == null || src.empty()) return;
        nextNs = now + PERIOD_NS;
        Mat copy = new Mat();
        if (src.channels() == 1) src.copyTo(copy);
        else Imgproc.cvtColor(src, copy, Imgproc.COLOR_BGR2GRAY);
        busy.set(true);
        worker.execute(
                () -> {
                    try {
                        measure(copy);
                    } finally {
                        copy.release();
                        busy.set(false);
                    }
                });
    }

    private void measure(Mat gray) {
        Mat lap = new Mat();
        MatOfDouble mean = new MatOfDouble(), std = new MatOfDouble();
        try {
            Imgproc.Laplacian(gray, lap, CvType.CV_32F);
            int w = gray.cols(), h = gray.rows();
            double[] g = new double[9];
            for (int r = 0; r < 3; r++) {
                for (int c = 0; c < 3; c++) {
                    Mat cell = lap.submat(new Rect(c * w / 3, r * h / 3, w / 3, h / 3));
                    Core.meanStdDev(cell, mean, std);
                    double s = std.toArray()[0];
                    g[r * 3 + c] = s * s;
                    cell.release();
                }
            }
            width = w;
            height = h;
            grid = g;
            best = Math.max(best, g[4]);
            measuredNs = System.nanoTime();
        } finally {
            lap.release();
            mean.release();
            std.release();
        }
    }
}
