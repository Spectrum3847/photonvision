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

import org.wpilib.vision.apriltag.AprilTagDetection;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;
import org.photonvision.common.LoadJNI;

/** SpectrumJetson (photonvision-54): TagContrast on synthetic tags with known pixel values. */
public class TagContrastTest {
    @BeforeAll
    public static void setup() {
        LoadJNI.loadLibraries();
    }

    /** A gray image of `margin`, with a filled quad of `black` at the given corners. */
    private static Mat tag(int margin, int black, Point... corners) {
        var m = new Mat(480, 640, CvType.CV_8UC1, new Scalar(margin));
        Imgproc.fillPoly(m, List.of(new MatOfPoint(corners)), new Scalar(black));
        return m;
    }

    private static AprilTagDetection detection(Point... c) {
        double[] corners = new double[8];
        for (int i = 0; i < 4; i++) {
            corners[2 * i] = c[i].x;
            corners[2 * i + 1] = c[i].y;
        }
        return new AprilTagDetection("tag36h11", 1, 0, 50f, new double[9], 0, 0, corners);
    }

    @Test
    public void goodTag() {
        Point[] q = {new Point(200, 140), new Point(360, 140), new Point(360, 300), new Point(200, 300)};
        var m = TagContrast.measure(tag(200, 30, q), List.of(detection(q)), 0);
        assertNotNull(m);
        assertEquals(1, m.tags());
        assertEquals(200, m.white(), 1);
        assertEquals(30, m.black(), 1);
        assertEquals(0, m.clipped(), 1e-9);
    }

    @Test
    public void tiltedTagClipped() {
        // A tag at an angle, its margin blown out to 255.
        Point[] q = {new Point(180, 150), new Point(380, 120), new Point(400, 330), new Point(170, 310)};
        var m = TagContrast.measure(tag(255, 60, q), List.of(detection(q)), 0);
        assertNotNull(m);
        assertEquals(255, m.white(), 1);
        assertEquals(60, m.black(), 1);
        assertEquals(1.0, m.clipped(), 1e-9);
    }

    @Test
    public void smallTagsAndRateLimit() {
        Point[] small = {new Point(300, 200), new Point(320, 200), new Point(320, 220), new Point(300, 220)};
        assertNull(TagContrast.measure(tag(200, 30, small), List.of(detection(small)), 0), "under 24 px");
        Point[] q = {new Point(200, 140), new Point(360, 140), new Point(360, 300), new Point(200, 300)};
        assertNull(TagContrast.measure(tag(200, 30, q), List.of(detection(q)), System.nanoTime()), "too soon");
    }

    @Test
    public void verdicts() {
        long now = System.nanoTime();
        TagContrast.record("clipping", new TagContrast.Measurement(now, 1, 255, 70, 0.4));
        TagContrast.record("dim", new TagContrast.Measurement(now, 1, 90, 60, 0));
        TagContrast.record("fine", new TagContrast.Measurement(now, 2, 190, 35, 0));
        var s = TagContrast.summary(List.of("clipping", "dim", "fine", "none"));
        assertEquals("whites clipping", s.get("clipping").get("verdict"));
        assertTrue(((String) s.get("clipping").get("advice")).contains("lower brightness first"));
        assertEquals("low contrast", s.get("dim").get("verdict"));
        assertEquals("good", s.get("fine").get("verdict"));
        assertEquals(155L, s.get("fine").get("contrast"));
        assertEquals("no tags", s.get("none").get("verdict"));
    }
}
