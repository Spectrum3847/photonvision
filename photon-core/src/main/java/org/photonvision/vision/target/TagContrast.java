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

import org.wpilib.vision.apriltag.AprilTagDetection;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;

/**
 * SpectrumJetson (photonvision-54): how the tags themselves look, so a bad exposure, brightness or
 * gamma shows as a reason ("whites clipping", "blacks lifted") and not only as a lower decision
 * margin. For each detected tag (at least 24 px across), samples two rings through the homography
 * from its corners: half a cell inside the edge (the tag's black border) and half a cell outside
 * (its white margin: 36h11 is 8 cells across the black border, with a 1-cell white margin), along
 * the middle 70% of each side. Reports the median white and black, their difference, and the share
 * of white samples at 250 or more (clipped). At most 5 measurements a second per camera, over the
 * last 2 s.
 */
public final class TagContrast {
    private static final int SAMPLES_PER_SIDE = 16;
    private static final double MIN_SIDE_PX = 24;
    private static final long MIN_INTERVAL_NS = 200_000_000L;
    private static final long WINDOW_NS = 2_000_000_000L;

    /** One frame's measurement over all its tags. */
    public record Measurement(long atNanos, int tags, double white, double black, double clipped) {}

    /** Last few measurements per camera nickname. */
    private static final Map<String, ArrayDeque<Measurement>> byCamera = new LinkedHashMap<>();

    private TagContrast() {}

    /** Measures, if it's time: returns null otherwise or when no tag is big enough. */
    public static Measurement measure(Mat gray, List<AprilTagDetection> detections, long lastNanos) {
        if (detections.isEmpty() || gray == null || gray.empty() || gray.channels() != 1) return null;
        long now = System.nanoTime();
        if (now - lastNanos < MIN_INTERVAL_NS) return null;
        var white = new ArrayList<Double>();
        var black = new ArrayList<Double>();
        int tags = 0;
        for (var d : detections) {
            double[] c = d.getCorners();
            double side = 0;
            for (int i = 0; i < 4; i++) side += Math.hypot(c[2 * ((i + 1) % 4)] - c[2 * i], c[2 * ((i + 1) % 4) + 1] - c[2 * i + 1]);
            if (side / 4 < MIN_SIDE_PX) continue;
            var unit = new MatOfPoint2f(new Point(0, 0), new Point(1, 0), new Point(1, 1), new Point(0, 1));
            var quad = new MatOfPoint2f(new Point(c[0], c[1]), new Point(c[2], c[3]), new Point(c[4], c[5]), new Point(c[6], c[7]));
            Mat h = Imgproc.getPerspectiveTransform(unit, quad);
            double cell = 1.0 / 8;
            // Points along each side of the unit square, half a cell in (black) and out (white).
            var in = new ArrayList<Point>();
            var out = new ArrayList<Point>();
            for (int k = 0; k < SAMPLES_PER_SIDE; k++) {
                double t = 0.15 + 0.7 * (k + 0.5) / SAMPLES_PER_SIDE;
                double a = cell / 2;
                in.add(new Point(t, a));
                in.add(new Point(1 - a, t));
                in.add(new Point(t, 1 - a));
                in.add(new Point(a, t));
                out.add(new Point(t, -a));
                out.add(new Point(1 + a, t));
                out.add(new Point(t, 1 + a));
                out.add(new Point(-a, t));
            }
            sample(gray, h, in, black);
            sample(gray, h, out, white);
            h.release();
            unit.release();
            quad.release();
            tags++;
        }
        if (tags == 0 || white.isEmpty() || black.isEmpty()) return null;
        double clipped = white.stream().filter(v -> v >= 250).count() / (double) white.size();
        return new Measurement(now, tags, median(white), median(black), clipped);
    }

    private static void sample(Mat gray, Mat h, List<Point> unitPoints, List<Double> into) {
        var src = new MatOfPoint2f(unitPoints.toArray(new Point[0]));
        var dst = new MatOfPoint2f();
        org.opencv.core.Core.perspectiveTransform(src, dst, h);
        byte[] px = new byte[1];
        for (Point p : dst.toArray()) {
            int x = (int) Math.round(p.x), y = (int) Math.round(p.y);
            if (x < 0 || y < 0 || x >= gray.cols() || y >= gray.rows()) continue;
            gray.get(y, x, px);
            into.add((double) (px[0] & 0xff));
        }
        src.release();
        dst.release();
    }

    private static double median(List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(a);
        return a[a.length / 2];
    }

    public static synchronized void record(String camera, Measurement m) {
        var q = byCamera.computeIfAbsent(camera, k -> new ArrayDeque<>());
        q.addLast(m);
        while (!q.isEmpty() && m.atNanos() - q.peekFirst().atNanos() > WINDOW_NS) q.removeFirst();
    }

    /**
     * Per camera: the median over the last 2 s of white, black, contrast, clipped share and tag count,
     * and a verdict with what to change. "no tags" if none were measured in the last 3 s.
     */
    public static synchronized Map<String, Map<String, Object>> summary(List<String> cameras) {
        var out = new LinkedHashMap<String, Map<String, Object>>();
        long now = System.nanoTime();
        for (String camera : cameras) {
            var m = new LinkedHashMap<String, Object>();
            var q = byCamera.get(camera);
            var recent = q == null ? List.<Measurement>of() : q.stream().filter(x -> now - x.atNanos() < 3_000_000_000L).toList();
            if (recent.isEmpty()) {
                m.put("verdict", "no tags");
                m.put("advice", "No tag big enough to measure (24 px or more) in the last 3 s.");
                out.put(camera, m);
                continue;
            }
            double white = median(recent.stream().map(Measurement::white).toList());
            double black = median(recent.stream().map(Measurement::black).toList());
            double clipped = median(recent.stream().map(Measurement::clipped).toList());
            int tags = recent.get(recent.size() - 1).tags();
            m.put("white", Math.round(white));
            m.put("black", Math.round(black));
            m.put("contrast", Math.round(white - black));
            m.put("clipped", Math.round(clipped * 100));
            m.put("tags", tags);
            String verdict, advice;
            if (clipped > 0.10) {
                verdict = "whites clipping";
                advice =
                        black > 60
                                ? "Tag whites are clipping and blacks are lifted: lower brightness first, then shorten exposure."
                                : "Tag whites are clipping: shorten exposure (or lower brightness or gamma).";
            } else if (white - black < 50) {
                verdict = "low contrast";
                advice =
                        black > 60
                                ? "Low contrast with blacks lifted: lower brightness, then lengthen exposure if the whites get too dark."
                                : "Low contrast: lengthen exposure (watch motion blur), or raise gamma.";
            } else if (black > 80) {
                verdict = "blacks lifted";
                advice = "Tag blacks are grey: brightness is probably too high.";
            } else {
                verdict = "good";
                advice = "";
            }
            m.put("verdict", verdict);
            m.put("advice", advice);
            out.put(camera, m);
        }
        return out;
    }
}
