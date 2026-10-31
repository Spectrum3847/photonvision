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
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfDouble;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
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
    // Spectrum (focus view): 10 readings a second, and the full-size image, while it's open.
    private static final long VIEW_PERIOD_NS = 33_000_000L;
    // Spectrum (focus view): one frame's Laplacian variance jumps with sensor noise, JPEG and the
    // hand on the lens (the view's numbers bounced ~10% with the lens still). Each cell is averaged
    // over ~0.3 s (exponential, weighted by the time since the last reading), and the best is taken
    // from the averaged values, so one lucky frame can't set it.
    private static final double SMOOTH_S = 0.3;
    private double[] smooth = null;
    private long smoothNs = 0;
    private volatile long viewUntilNs = 0;
    private volatile byte[] jpeg = null;

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

    /**
     * Spectrum (focus view): the latest measured image as a JPEG at the camera's full size, and keep
     * making them (and measuring 10 times a second) for the next 3 s. Null until the first one.
     */
    public byte[] frameJpeg() {
        long until = System.nanoTime() + WANTED_NS;
        viewUntilNs = until;
        wantedUntilNs = until;
        return jpeg;
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
        boolean view = now < viewUntilNs;
        nextNs = now + (view ? VIEW_PERIOD_NS : PERIOD_NS);
        Mat copy = new Mat();
        if (src.channels() == 1) src.copyTo(copy);
        else Imgproc.cvtColor(src, copy, Imgproc.COLOR_BGR2GRAY);
        busy.set(true);
        worker.execute(
                () -> {
                    try {
                        measure(copy);
                        if (view) encode(copy);
                    } finally {
                        copy.release();
                        busy.set(false);
                    }
                });
    }

    private void encode(Mat gray) {
        MatOfByte buf = new MatOfByte();
        try {
            if (Imgcodecs.imencode(".jpg", gray, buf, new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, 85))) {
                jpeg = buf.toArray();
            }
        } finally {
            buf.release();
        }
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
            long now = System.nanoTime();
            double dt = smooth == null ? 1 : (now - smoothNs) / 1e9;
            if (smooth == null || dt > 2) smooth = g.clone();
            double a = 1 - Math.exp(-dt / SMOOTH_S);
            for (int i = 0; i < 9; i++) smooth[i] += a * (g[i] - smooth[i]);
            smoothNs = now;
            width = w;
            height = h;
            grid = smooth.clone();
            best = Math.max(best, smooth[4]);
            measuredNs = now;
        } finally {
            lap.release();
            mean.release();
            std.release();
        }
    }
}
