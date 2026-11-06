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
 * Spectrum (sc/mjpeg-napa) fast path: with streaming NAPA, each camera's streaming-decoder thread
 * publishes that camera's PhotonPipelineResult itself (NapaDetectorJNI.setNativePublish: native
 * pose + the fork's serde on the camera's own rawBytes publisher) the moment its tags are known, so
 * the Java pipeline is off the per-frame path. The Java pipeline keeps running for the UI, the
 * stream and health, at most {@link #JAVA_FPS} frames a second, and doesn't publish rawBytes itself
 * while the native side does.
 *
 * <p>One instance per camera: its VisionRunner owns it and makes it {@link #current()} on its
 * thread, where the camera's pipeline, NapaDetector, NTDataPublisher and CameraHealthPublisher run,
 * so any number of cameras each publish on their own topics with their own calibration and timing.
 * A camera whose frames aren't streamed (no band-sink result) stays on the Java path.
 *
 * <p>SPECTRUM_NATIVE_PUBLISH=1 turns it on; SPECTRUM_NATIVE_PUBLISH_JAVA_FPS (default 30) caps the
 * Java pipeline meanwhile.
 */
public final class NativePublish {
    public static final boolean ENABLED = "1".equals(System.getenv("SPECTRUM_NATIVE_PUBLISH"));
    public static final double JAVA_FPS = javaFps();

    private static final ThreadLocal<NativePublish> CURRENT = new ThreadLocal<>();

    /** The camera whose vision thread this is, or null (off, or not a vision thread). */
    public static NativePublish current() {
        return ENABLED ? CURRENT.get() : null;
    }

    /** VisionRunner, on its own thread: this camera's instance (null: none). */
    public static void makeCurrent(NativePublish p) {
        CURRENT.set(p);
    }

    // from NTDataPublisher (the camera's rawBytes publisher, its instance, the time-sync state)
    public volatile int pubHandle = 0;
    public volatile int instHandle = 0;
    public volatile long ntOffsetNs = 0;
    public volatile long sincePongNs = 0;
    // from the camera's frame provider: the frame's first bytes -> mid-exposure (exposure/2 + delay)
    public volatile long midShiftNs = 0;
    // from AprilTagPipeline: calibration and tag size
    public volatile double[] intrinsics = null;
    public volatile double[] distortion = null;
    public volatile double tagSizeM = 0.1651;

    private volatile long published = -1;
    private volatile long lastProgressNs = 0;
    private volatile double latencyMs = 0;

    /**
     * Whether each camera publishes on its own NetworkTables client (default;
     * SPECTRUM_NATIVE_NT_CLIENT=0 shares PhotonVision's). ntcore drops a flush that comes within 5 ms
     * of the connection's last send (NetworkOutgoingQueue::SendOutgoing, MIN_PERIOD_MS) and the value
     * then waits for the next send: with two cameras at 120 fps on one connection, capture ->
     * received p95 went from ~13 to ~21 ms. Its own connection keeps each camera's flushes clear of
     * the others' and of PhotonVision's own sends.
     */
    static final boolean OWN_CLIENT = !"0".equals(System.getenv("SPECTRUM_NATIVE_NT_CLIENT"));

    private org.wpilib.networktables.NetworkTableInstance ownInst;
    private org.wpilib.networktables.RawPublisher ownPub;
    private String ownTopic, ownServer;

    /**
     * NTDataPublisher, for each Java result: this camera's rawBytes publisher and the time-sync
     * state. The native side publishes on this camera's own client (same topic, same type) once that
     * is connected, else on PhotonVision's publisher.
     */
    public synchronized void bind(
            org.wpilib.networktables.NetworkTableInstance pvInst,
            org.wpilib.networktables.RawPublisher javaPub,
            long offsetNs,
            long sincePong) {
        ntOffsetNs = offsetNs;
        sincePongNs = sincePong;
        int pub = javaPub.getHandle(), inst = pvInst.getHandle();
        if (OWN_CLIENT) {
            try {
                if (ensureOwnClient(pvInst, javaPub)) {
                    pub = ownPub.getHandle();
                    inst = ownInst.getHandle();
                }
            } catch (Exception e) {
                // stay on PhotonVision's connection
            }
        }
        pubHandle = pub;
        instHandle = inst;
    }

    private boolean ensureOwnClient(
            org.wpilib.networktables.NetworkTableInstance pvInst,
            org.wpilib.networktables.RawPublisher javaPub) {
        // PhotonVision as a client of the robot: connect to the same server. (As a server it has no
        // remote to share a flush with but its own clients: stay on it.)
        if (!pvInst
                .getNetworkMode()
                .contains(org.wpilib.networktables.NetworkTableInstance.NetworkMode.CLIENT)) return false;
        var conns = pvInst.getConnections();
        if (conns.length == 0) return ownInst != null && ownInst.isConnected() && ownPub != null;
        String server = conns[0].remoteIp + ":" + conns[0].remotePort;
        var topic = javaPub.getTopic();
        String name = topic.getName();
        if (ownInst == null) {
            ownInst = org.wpilib.networktables.NetworkTableInstance.create();
            ownInst.startClient("photonvision-native-" + System.identityHashCode(this));
        }
        if (!server.equals(ownServer)) {
            ownInst.setServer(conns[0].remoteIp, conns[0].remotePort);
            ownServer = server;
        }
        if (!name.equals(ownTopic)) { // first use, or the camera was renamed
            if (ownPub != null) ownPub.close();
            ownPub = ownInst.getRawTopic(name).publish(topic.getTypeString());
            ownTopic = name;
        }
        return ownInst.isConnected();
    }

    /** The camera's VisionRunner closed: its own client goes. */
    public synchronized void close() {
        pubHandle = 0;
        if (ownPub != null) ownPub.close();
        if (ownInst != null) ownInst.close();
        ownPub = null;
        ownInst = null;
    }

    private static double javaFps() {
        try {
            String v = System.getenv("SPECTRUM_NATIVE_PUBLISH_JAVA_FPS");
            return v == null || v.isBlank() ? 30 : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /**
     * NapaDetector, after each streamed frame: refresh this camera's native settings, track whether
     * it publishes.
     */
    void update(long napaHandle) {
        if (napaHandle == 0) return;
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
        if (n > published) {
            published = n;
            lastProgressNs = System.nanoTime();
            double l = org.photonvision.jni.NapaDetectorJNI.nativeLatencyMs(napaHandle);
            if (l >= 0) latencyMs = l;
        }
    }

    /** Whether the native side published recently (then the Java side doesn't publish rawBytes). */
    public boolean active() {
        return pubHandle != 0 && System.nanoTime() - lastProgressNs < 200_000_000L;
    }

    /** Results the native side published so far (for health), -1 before the first. */
    public long published() {
        return published;
    }

    /** Mean capture -> publish of the native results since the last update that saw new ones, ms. */
    public double latencyMs() {
        return latencyMs;
    }
}
