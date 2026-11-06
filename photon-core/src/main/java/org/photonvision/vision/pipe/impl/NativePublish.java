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

package org.photonvision.vision.pipe.impl;

/**
 * Spectrum (sc/mjpeg-napa) fast path: with streaming NAPA, the camera's streaming-decoder thread publishes each
 * frame's PhotonPipelineResult itself (NapaDetectorJNI.setNativePublish: native pose + the fork's serde on this
 * camera's rawBytes publisher) the moment its tags are known, so the Java pipeline is off the per-frame path. The
 * Java pipeline keeps running for the UI, the stream and health, at most {@link #JAVA_FPS} frames a second, and
 * doesn't publish rawBytes itself while the native side does.
 *
 * <p>SPECTRUM_NATIVE_PUBLISH=1 turns it on (prototype: one camera); SPECTRUM_NATIVE_PUBLISH_JAVA_FPS (default 30)
 * caps the Java pipeline meanwhile.
 */
public final class NativePublish {
    public static final boolean ENABLED = "1".equals(System.getenv("SPECTRUM_NATIVE_PUBLISH"));
    public static final double JAVA_FPS = javaFps();

    // from NTDataPublisher (the camera's rawBytes publisher, its instance, the time-sync state)
    public static volatile int pubHandle = 0;
    public static volatile int instHandle = 0;
    public static volatile long ntOffsetNs = 0;
    public static volatile long sincePongNs = 0;
    // from USBFrameProvider: the frame's first bytes -> mid-exposure (exposure / 2 + camera delay)
    public static volatile long midShiftNs = 0;
    // from AprilTagPipeline: calibration and tag size
    public static volatile double[] intrinsics = null;
    public static volatile double[] distortion = null;
    public static volatile double tagSizeM = 0.1651;

    private static volatile long lastPublished = -1;
    private static volatile long lastProgressNs = 0;

    private NativePublish() {}

    private static double javaFps() {
        try {
            String v = System.getenv("SPECTRUM_NATIVE_PUBLISH_JAVA_FPS");
            return v == null || v.isBlank() ? 30 : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /** NapaDetector, after each streamed frame: refresh the native side's settings, track whether it publishes. */
    static void update(long napaHandle) {
        if (!ENABLED || napaHandle == 0) return;
        long n =
                org.photonvision.jni.NapaDetectorJNI.setNativePublish(
                        napaHandle,
                        pubHandle,
                        instHandle,
                        intrinsics,
                        distortion,
                        tagSizeM,
                        midShiftNs,
                        ntOffsetNs,
                        sincePongNs);
        if (n > lastPublished) {
            lastPublished = n;
            lastProgressNs = System.nanoTime();
        }
    }

    /** Whether the native side published recently (then the Java side doesn't publish rawBytes). */
    public static boolean active() {
        return ENABLED && pubHandle != 0 && System.nanoTime() - lastProgressNs < 200_000_000L;
    }
}
