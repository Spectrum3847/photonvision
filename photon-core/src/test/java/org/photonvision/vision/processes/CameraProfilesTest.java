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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.calibration.JsonMatOfDouble;

/** Spectrum: camera profile lookup, averaging and generic-calibration detection. */
public class CameraProfilesTest {
    @Test
    public void lookupByVidPid() {
        assertEquals("Thriftiest Cam", CameraProfiles.forVidPid(0x1bcf, 0x28c5).orElseThrow().name());
        assertEquals("Swyft USB Camera", CameraProfiles.forVidPid(0x0bda, 0x3035).orElseThrow().name());
        assertTrue(CameraProfiles.forVidPid(0x32e4, 0x0144).isPresent());
        assertTrue(CameraProfiles.forVidPid(0x32e4, 0x62f0).isPresent());
        assertFalse(CameraProfiles.forVidPid(0x046d, 0x0825).isPresent());
    }

    @Test
    public void everyBundledCalibrationLoads() {
        for (var p : CameraProfiles.PROFILES) {
            for (var c : CameraProfiles.genericCalibrations(p)) {
                assertTrue(CameraProfiles.isGeneric(c), p.name());
            }
        }
    }

    @Test
    public void thriftiestAverage() {
        var p = CameraProfiles.forVidPid(0x1bcf, 0x28c5).orElseThrow();
        var cals = CameraProfiles.genericCalibrations(p);
        assertEquals(2, cals.size());
        var c = cals.get(0);
        assertEquals(1280, (int) c.resolution.width);
        assertEquals(800, (int) c.resolution.height);
        var tc01 = CameraProfiles.load("SJ-TC01-1280x800.json");
        var tc02 = CameraProfiles.load("SJ-TC02-1280x800.json");
        for (int i = 0; i < 9; i++) {
            assertEquals(
                    (tc01.cameraIntrinsics.data[i] + tc02.cameraIntrinsics.data[i]) / 2,
                    c.cameraIntrinsics.data[i],
                    1e-9);
        }
        for (int i = 0; i < 8; i++) {
            assertEquals(
                    (tc01.distCoeffs.data[i] + tc02.distCoeffs.data[i]) / 2, c.distCoeffs.data[i], 1e-12);
        }
        assertEquals(736.4, c.cameraIntrinsics.data[0], 0.1);
        assertEquals(91.4, CameraProfiles.diagonalFovDegrees(c), 0.5);
        // Neither real unit counts as generic.
        assertFalse(CameraProfiles.isGeneric(tc01));
        assertFalse(CameraProfiles.isGeneric(tc02));
    }

    @Test
    public void averageOfTwo() {
        var a = cal(new double[] {100, 0, 10, 0, 200, 20, 0, 0, 1}, new double[] {1, 2, 3, 4, 5});
        var b = cal(new double[] {300, 0, 30, 0, 400, 40, 0, 0, 1}, new double[] {3, 4, 5, 6, 7});
        var avg = CameraProfiles.average(List.of(a, b));
        assertEquals(200, avg.cameraIntrinsics.data[0], 1e-12);
        assertEquals(300, avg.cameraIntrinsics.data[4], 1e-12);
        assertEquals(20, avg.cameraIntrinsics.data[2], 1e-12);
        assertEquals(1, avg.cameraIntrinsics.data[8], 1e-12);
        assertEquals(6, avg.distCoeffs.data[4], 1e-12);
    }

    private static CameraCalibrationCoefficients cal(double[] k, double[] d) {
        return new CameraCalibrationCoefficients(
                new org.opencv.core.Size(1280, 800),
                new JsonMatOfDouble(3, 3, k),
                new JsonMatOfDouble(1, d.length, d),
                new double[0],
                List.of(),
                new org.opencv.core.Size(11, 8),
                0.03,
                null,
                null);
    }
}
