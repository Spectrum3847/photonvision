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

package org.photonvision.jni;

import org.wpilib.vision.apriltag.AprilTagDetection;

/**
 * Spectrum: NAPA, a sparse-sampling CPU AprilTag detector by Zachary Wilson (github.com/zwatx/NAPA,
 * v0.5.1, GPL-3.0-only; NOTICE), as libspectrum-napa-apriltag.so (SpectrumJetson detector/napa,
 * scripts/jetson/07-build-napa-detector.sh). One handle per camera; its detections are in OpenCV's
 * pixel convention already. Optional: {@link #isAvailable()} is false when the library isn't
 * installed, and callers fall back to their normal detector.
 */
public final class NapaDetectorJNI {
    private static final boolean available;
    private static String loadError = null;

    static {
        boolean ok = false;
        try {
            System.loadLibrary("spectrum-napa-apriltag");
            ok = true;
        } catch (UnsatisfiedLinkError e) {
            loadError = e.getMessage();
        }
        available = ok;
    }

    private NapaDetectorJNI() {}

    public static boolean isAvailable() {
        return available;
    }

    public static String loadError() {
        return loadError;
    }

    /** A detector for a NAPA family name ("tag36h11", "tag16h5"); 0 if unknown. */
    public static native long create(String family);

    public static native void destroy(long handle);

    /**
     * minTagPx: the full-frame pass (far search off). far: the full frame at baseMinTagPx every
     * frame, and farMinTagPx in tracking crops around small tags and in capped sweeps of one band at
     * a time (at most sweepsPerSecond bands a second).
     */
    public static native void setParams(
            long handle,
            int minTagPx,
            int maxHamming,
            boolean far,
            int baseMinTagPx,
            int farMinTagPx,
            double sweepsPerSecond);

    /** An 8-bit gray image: data address, width, height and row step in bytes. */
    public static native AprilTagDetection[] detect(
            long handle, long dataAddr, int width, int height, int step);

    /** True when libspectrum-stream.so (the streaming decoder) could be opened. */
    public static native boolean streamAvailable();

    /**
     * Streaming decode for a machine without the CUDA library: 0 when the camera's frame was decoded
     * while it arrived (into the gray Mat), 1 when it wasn't (decode it yourself), -1 not MJPEG or no
     * stream library.
     */
    public static native int streamDecodeGray(
            long rawFramePtr, long dataAddr, int width, int height, int step);

    /**
     * Spectrum (sc/mjpeg-napa): false = streamDecodeGray hands over a streamed frame without copying
     * its pixels (only a tag that detectStreamed reads), for frames whose tags the band sink already
     * found and when nobody watches the stream. Returns false if the library can't.
     */
    public static native boolean setStreamCopy(boolean on);

    /**
     * Spectrum (sc/mjpeg-napa): single-tag pose natively (IPPE for a square + Gauss-Newton refine,
     * ~10 us). corners: AprilTag order, raw pixels (x0, y0, ...); k: 3x3 intrinsics row-major; dist:
     * OpenCV distortion (up to 8). Returns {best tx, ty, tz, R (9, row-major), err px, alt tx, ty,
     * tz, R (9), err px} in AprilTag's tag frame, or null.
     */
    public static native double[] poseSquare(
            double[] corners, double[] k, double[] dist, double tagSize);

    /**
     * Spectrum (sc/mjpeg-napa): mean capture (mid-exposure) -> publish of this camera's natively
     * published results since the last call, ms; -1 if none.
     */
    public static native double nativeLatencyMs(long handle);

    /**
     * Spectrum (sc/mjpeg-napa): the tagQuality DoubleArray publisher (ntcore handle; 0: none) on
     * which the native publish sends each result's tag quality, with the result's sequence ID, just
     * before the result.
     */
    public static native void setNativeQualityPub(long handle, int pub);

    /**
     * Spectrum (sc/mjpeg-napa): the results this camera's native publish sent since the last call,
     * for Rewind's vision log: each [publish time ns (8 bytes LE)][quality count (4)][quality
     * doubles][the serialized result]. Calling it turns the collection on (off 1 s after the last
     * call). Null if none.
     */
    public static native byte[][] drainNativeResults(long handle);

    // Spectrum (sc/mjpeg-napa) native camera backend (SpectrumJetson
    // docs/SYSTEMCORE-NATIVE-BACKEND.md)

    /**
     * Opens the camera (its /dev/video path) natively: MJPEG width x height at fps, its own capture
     * thread; decode, streaming NAPA, pose and publish there. Returns a NAPA handle (the setNative*
     * calls, setParams, detect and destroy work on it; destroy closes the camera), or 0.
     */
    public static native long cameraOpen(String dev, int width, int height, int fps, String family);

    /** Sets a V4L2 control, reading it first: 1 written, 0 already that value, -1 error. */
    public static native int cameraSetControl(long handle, int id, int value);

    /** A V4L2 control's value, or Integer.MIN_VALUE. */
    public static native int cameraGetControl(long handle, int id);

    /**
     * The latest full-size gray image into dataAddr (width x height, stride width). Returns its
     * camera stamp (ns), or 0 when none is ready yet.
     */
    public static native long cameraLatestGray(long handle, long dataAddr, int width, int height);

    /**
     * {frames, bad frames, skipped by the policy, ms since the last frame (-1: none), Rewind frames
     * kept}.
     */
    public static native long[] cameraStats(long handle);

    /**
     * Rewind video from the camera's own thread: prefix (path without _NNNN; null stops), ring MB (32
     * if <= 0), record fps (0: every frame). True when recording.
     */
    public static native boolean cameraRecord(
            long handle, String prefix, int ringMb, double recordFps);

    /**
     * The camera's latest JPEG, as the camera sent it (for the stream: no re-encode), into a direct
     * buffer. Returns its length; 0 when none newer is ready yet; -n when the buffer needs n bytes.
     */
    public static native int cameraLatestJpeg(long handle, java.nio.ByteBuffer direct);

    /** As cameraOpen; the first published result's sequence ID is lastSeq + 1. */
    public static native long cameraOpenSeq(
            String dev, int width, int height, int fps, String family, long lastSeq);

    /** The last sequence ID the camera's native publish used. */
    public static native long cameraSequence(long handle);

    // The device itself, without a capture handle (NativeCameraSettables: no cscore UsbCamera)

    /**
     * The device's video modes as the driver lists them: {fourcc, width, height, fps} per mode, or
     * null if it can't be opened.
     */
    public static native int[] deviceModes(String dev);

    /**
     * The device's controls, one "id,type,min,max,step,default,value,flags,name" string each (the
     * camera's own units), or null if it can't be opened.
     */
    public static native String[] deviceControls(String dev);

    /** Sets a control, reading it first: 1 written, 0 already that value, -1 error. */
    public static native int deviceSetControl(String dev, int id, int value);

    /** A control's value, or Integer.MIN_VALUE. */
    public static native int deviceGetControl(String dev, int id);

    /** Closes the device's control fd. */
    public static native void deviceClose(String dev);

    /**
     * Spectrum (sc/mjpeg-napa): this camera's frame policy for streamed frames: decode scale 1, 2 or
     * 4, every fullEvery-th processed frame at full scale (0: never), process 1 frame in
     * processEvery. Applies while nobody watches the stream. Returns frames decoded reduced so far.
     */
    public static native long setNativePolicy(
            long handle, int scale, int fullEvery, int processEvery);

    /**
     * Spectrum (sc/mjpeg-napa): the camera-clock stamp (PTS, hwtimestamps=1) -> mid-exposure for this
     * camera's native publish, ns (0: stamp from the first sight of the frame's bytes), and the
     * camera-clock stamp of the frame detectStreamed last returned (0: none), which anchors the
     * prediction of the stamps of the frames published before Java sees them. Returns results stamped
     * from the camera clock so far.
     */
    public static native long setNativeCamShift(long handle, long camShiftNs, long lastFrameCamNs);

    /**
     * Spectrum (sc/mjpeg-napa) fast path: the streaming thread publishes each frame's
     * PhotonPipelineResult on the ntcore raw publisher pub (0 = off) as soon as its tags are known.
     * Returns frames published, -1 if unavailable.
     */
    public static native long setNativePublish(
            long handle,
            int pub,
            int inst,
            double[] k,
            double[] dist,
            double tagSize,
            long midShiftNs,
            long ntOffsetNs,
            long sincePongNs);

    /**
     * As {@link #detect}, for a frame the streaming decoder just wrote into dataAddr on this thread:
     * returns the result NAPA computed band by band while the frame arrived (identical to detect()),
     * or detects the whole frame if it wasn't streamed. wantAfterNanos: when this camera next takes a
     * frame (System.nanoTime; 0 = every frame).
     */
    public static native AprilTagDetection[] detectStreamed(
            long handle, long dataAddr, int width, int height, int step, long wantAfterNanos);

    /** frames, base ms, crops, crop ms, sweeps, sweep ms, far tags (totals since create). */
    public static native double[] stats(long handle);
}
