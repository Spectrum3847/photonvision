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

import io.avaje.jsonb.Jsonb;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.calibration.JsonMatOfDouble;
import org.photonvision.vision.camera.PVCameraInfo;

/**
 * Spectrum: per-camera-model defaults ("camera profiles"), keyed by USB vendor:product ID. When
 * PhotonVision first sees a camera of a known model (a brand-new camera with no pipelines, same
 * rule as {@link TeamCameraDefaults}), it starts with the model's video mode, exposure, a generic
 * starting calibration and the FOV computed from that calibration. Unknown models just get
 * TeamCameraDefaults. Existing cameras are never changed.
 *
 * <p>A generic calibration is only a starting point: the UI shows "generic calibration: calibrate
 * this camera" until a real calibration at that resolution replaces it. "Generic" isn't stored;
 * {@link #isGeneric} compares a calibration with the bundled ones.
 *
 * <p>To add a camera: find its VID:PID with {@code lsusb}, put any calibration JSON (exported from
 * PhotonVision) in {@code photon-core/src/main/resources/spectrum/calibrations/}, and add a line to
 * {@link #PROFILES}.
 */
public final class CameraProfiles {
    private CameraProfiles() {}

    static final String CALIBRATION_DIR = "/spectrum/calibrations/";

    /**
     * One camera model.
     *
     * @param name Shown in the log.
     * @param vendorId USB vendor ID.
     * @param productId USB product ID.
     * @param width Preferred width (MJPEG at its highest fps; else TeamCameraDefaults' fallback).
     * @param height Preferred height.
     * @param exposureRaw Exposure in UVC units of 100 us.
     * @param calibrations Starting calibrations, one per resolution. Each entry lists bundled files;
     *     more than one file is averaged.
     */
    public record Profile(
            String name,
            int vendorId,
            int productId,
            int width,
            int height,
            double exposureRaw,
            List<List<String>> calibrations) {}

    /** The profile table. One line per camera model. */
    public static final List<Profile> PROFILES =
            List.of(
                    // Thriftiest Cam (OV9281 mono global shutter). 1280x800 MJPEG 120 fps, 5 ms
                    // exposure (TeamCameraDefaults). Calibration: the average of our two units,
                    // SJ-TC01 and SJ-TC02 (calibrated 2026). Approximate: their cy differs by 48 px
                    // (418 vs 370). fx ~736.4 gives ~82 deg H, 57 deg V, 91 deg diagonal.
                    // 320x240: SJ-TC02's own calibration.
                    new Profile(
                            "Thriftiest Cam",
                            0x1bcf,
                            0x28c5,
                            1280,
                            800,
                            TeamCameraDefaults.EXPOSURE_RAW,
                            List.of(
                                    List.of("SJ-TC01-1280x800.json", "SJ-TC02-1280x800.json"),
                                    List.of("SJ-TC02-320x240.json"))),
                    // Swyft USB Camera (OV9281-class mono, non-distortion lens glued at 5 ft).
                    // Swyft specs: 81 x 52 deg, MJPEG only, 1280x800 @ 120 fps, 640x400 and below
                    // @ 210 fps. Calibration: Swyft's published factory calibration (5 distortion
                    // coefficients, no snapshots).
                    new Profile(
                            "Swyft USB Camera",
                            0x0bda,
                            0x3035,
                            1280,
                            800,
                            TeamCameraDefaults.EXPOSURE_RAW,
                            List.of(List.of("SWYFT-factory-1280x800.json"))),
                    // "Global Shutter Camera": no calibration yet, so PhotonVision's default FOV.
                    new Profile(
                            "Global Shutter Camera",
                            0x32e4,
                            0x0144,
                            1280,
                            800,
                            TeamCameraDefaults.EXPOSURE_RAW,
                            List.of()),
                    // "USB Camera" (same vendor): no calibration yet.
                    new Profile(
                            "USB Camera (32e4)",
                            0x32e4,
                            0x62f0,
                            1280,
                            800,
                            TeamCameraDefaults.EXPOSURE_RAW,
                            List.of()));

    /** The profile for this VID:PID, if any. */
    public static Optional<Profile> forVidPid(int vendorId, int productId) {
        return PROFILES.stream()
                .filter(p -> p.vendorId == vendorId && p.productId == productId)
                .findFirst();
    }

    /** The profile for this camera, if it's a USB camera of a known model. */
    public static Optional<Profile> forCamera(PVCameraInfo info) {
        if (info instanceof PVCameraInfo.PVUsbCameraInfo usb) {
            return forVidPid(usb.vendorId, usb.productId);
        }
        return Optional.empty();
    }

    /** Loads a bundled calibration. Throws if it's missing, so a typo fails the unit test. */
    public static CameraCalibrationCoefficients load(String file) {
        try (InputStream in = CameraProfiles.class.getResourceAsStream(CALIBRATION_DIR + file)) {
            if (in == null) throw new IllegalArgumentException("No bundled calibration " + file);
            return Jsonb.instance().type(CameraCalibrationCoefficients.class).fromJson(in);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Couldn't read bundled calibration " + file, e);
        }
    }

    /**
     * Averages calibrations of the same resolution: intrinsics and distortion coefficients, element
     * by element. Everything else (board, lens model) comes from the first one. No snapshots.
     */
    public static CameraCalibrationCoefficients average(List<CameraCalibrationCoefficients> cals) {
        if (cals.isEmpty()) throw new IllegalArgumentException("Nothing to average");
        var first = cals.get(0);
        if (cals.size() == 1) return withoutObservations(first);
        for (var c : cals) {
            if (!c.resolution.equals(first.resolution)
                    || c.distCoeffs.data.length != first.distCoeffs.data.length) {
                throw new IllegalArgumentException("Can only average calibrations of the same shape");
            }
        }
        double[] k = new double[first.cameraIntrinsics.data.length];
        double[] d = new double[first.distCoeffs.data.length];
        for (var c : cals) {
            for (int i = 0; i < k.length; i++) k[i] += c.cameraIntrinsics.data[i] / cals.size();
            for (int i = 0; i < d.length; i++) d[i] += c.distCoeffs.data[i] / cals.size();
        }
        return new CameraCalibrationCoefficients(
                first.resolution,
                new JsonMatOfDouble(first.cameraIntrinsics.rows, first.cameraIntrinsics.cols, k),
                new JsonMatOfDouble(first.distCoeffs.rows, first.distCoeffs.cols, d),
                new double[0],
                List.of(),
                first.calobjectSize,
                first.calobjectSpacing,
                first.lensmodel,
                null);
    }

    private static CameraCalibrationCoefficients withoutObservations(CameraCalibrationCoefficients c) {
        return new CameraCalibrationCoefficients(
                c.resolution,
                c.cameraIntrinsics,
                c.distCoeffs,
                c.calobjectWarp,
                List.of(),
                c.calobjectSize,
                c.calobjectSpacing,
                c.lensmodel,
                null);
    }

    /** Fresh copies of a profile's starting calibrations (fresh, since configs release them). */
    public static List<CameraCalibrationCoefficients> genericCalibrations(Profile p) {
        var out = new ArrayList<CameraCalibrationCoefficients>();
        for (var files : p.calibrations) {
            out.add(average(files.stream().map(CameraProfiles::load).toList()));
        }
        return out;
    }

    private static List<CameraCalibrationCoefficients> allGeneric;

    private static synchronized List<CameraCalibrationCoefficients> allGeneric() {
        if (allGeneric == null) {
            var all = new ArrayList<CameraCalibrationCoefficients>();
            for (var p : PROFILES) {
                try {
                    all.addAll(genericCalibrations(p));
                } catch (RuntimeException e) {
                    // A broken bundled file only loses the warning, never the camera.
                }
            }
            allGeneric = all;
        }
        return allGeneric;
    }

    /**
     * True if this calibration is one of the bundled generic ones (same resolution, intrinsics and
     * distortion), i.e. nobody has calibrated this camera at this resolution yet.
     */
    public static boolean isGeneric(CameraCalibrationCoefficients c) {
        if (c == null || c.resolution == null || c.cameraIntrinsics == null || c.distCoeffs == null) {
            return false;
        }
        for (var g : allGeneric()) {
            if (g.resolution.equals(c.resolution)
                    && close(g.cameraIntrinsics.data, c.cameraIntrinsics.data)
                    && close(g.distCoeffs.data, c.distCoeffs.data)) {
                return true;
            }
        }
        return false;
    }

    private static boolean close(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Math.abs(a[i] - b[i]) > 1e-9 * Math.max(1, Math.abs(a[i]))) return false;
        }
        return true;
    }

    /**
     * Diagonal FOV in degrees from a calibration's focal lengths (pinhole, centred), which is what
     * PhotonVision's "Maximum Diagonal FOV" means.
     */
    public static double diagonalFovDegrees(CameraCalibrationCoefficients c) {
        double fx = c.cameraIntrinsics.data[0];
        double fy = c.cameraIntrinsics.data[4];
        double x = c.resolution.width / 2 / fx;
        double y = c.resolution.height / 2 / fy;
        return Math.toDegrees(2 * Math.atan(Math.hypot(x, y)));
    }
}
