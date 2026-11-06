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
 * stream and health, at most {@link #JAVA_FPS} frames a second ({@link #STREAM_FPS} while a stream
 * fed by the Java pipeline is watched), and doesn't publish rawBytes itself while the native side
 * does.
 *
 * <p>One instance per camera: its VisionRunner owns it and makes it {@link #current()} on its
 * thread, where the camera's pipeline, NapaDetector, NTDataPublisher and CameraHealthPublisher run,
 * so any number of cameras each publish on their own topics with their own calibration and timing.
 * A camera whose frames aren't streamed (no band-sink result) stays on the Java path.
 *
 * <p>Always on (it engages per camera only when that camera's frames are streamed or captured
 * natively; SPECTRUM_NATIVE_CAMERA=0 opts out of native cameras). SPECTRUM_NATIVE_JAVA_FPS (default
 * 5) is the Java pipeline's rate meanwhile, for the fast path and native cameras alike.
 */
public final class NativePublish {
    public static final boolean ENABLED = true;
    public static final double JAVA_FPS = envFps("SPECTRUM_NATIVE_JAVA_FPS", 5);
    // while someone watches a stream the Java pipeline feeds (the output overlay; the input stream
    // of a native camera is its own JPEG)
    public static final double STREAM_FPS = envFps("SPECTRUM_STREAM_FPS", 30);

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
    // native camera backend: the NAPA handle that owns this camera (0: not native)
    public volatile long cameraHandle = 0;
    private long lastNapaHandle = 0;
    // from the camera's frame provider: its camera-clock stamp (PTS) -> mid-exposure (0: no camera
    // clock)
    public volatile long camShiftNs = 0;
    // from AprilTagPipeline: the frame being detected's published (mid-exposure) capture time
    public volatile long frameCaptureNs = 0;
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
    private org.wpilib.networktables.DoubleArrayPublisher ownQualityPub;
    // Rewind's folder name for this camera (from NTDataPublisher)
    public volatile String rewindCamera;
    // the tagQuality publisher the native side sends each result's quality on (0: none)
    public volatile int qualityHandle = 0;
    private String ownTopic, ownServer;

    /**
     * NTDataPublisher, for each Java result: this camera's rawBytes publisher and the time-sync
     * state. The native side publishes on this camera's own client (same topic, same type) once that
     * is connected, else on PhotonVision's publisher.
     */
    public synchronized void bind(
            org.wpilib.networktables.NetworkTableInstance pvInst,
            org.wpilib.networktables.RawPublisher javaPub,
            org.wpilib.networktables.DoubleArrayPublisher javaQualityPub,
            long offsetNs,
            long sincePong) {
        ntOffsetNs = offsetNs;
        sincePongNs = sincePong;
        int pub = javaPub.getHandle(), inst = pvInst.getHandle();
        int quality = javaQualityPub != null ? javaQualityPub.getHandle() : 0;
        if (OWN_CLIENT) {
            try {
                if (ensureOwnClient(pvInst, javaPub)) {
                    pub = ownPub.getHandle();
                    inst = ownInst.getHandle();
                    quality = ownQualityPub != null ? ownQualityPub.getHandle() : 0;
                }
            } catch (Exception e) {
                // stay on PhotonVision's connection
            }
        }
        pubHandle = pub;
        instHandle = inst;
        qualityHandle = quality;
        // the robot's frame policy for this camera: /photonvision/<camera>/input/*
        String topicName = javaPub.getTopic().getName();
        String dir = topicName.substring(0, topicName.lastIndexOf('/'));
        if (!dir.equals(policyDir)) {
            if (inProcessEvery != null) inProcessEvery.close();
            inProcessEvery = pvInst.getIntegerTopic(dir + "/input/processEvery").subscribe(0);
            policyDir = dir;
        }
    }

    /**
     * Spectrum (sc/mjpeg-napa): the frame policy (docs/FRAME-POLICY.md in SpectrumJetson): frame
     * skipping only, process 1 frame in N (the others aren't decoded). The robot publishes
     * /photonvision/<camera>/input/processEvery; until it does, SPECTRUM_POLICY_PROCESS_EVERY, else
     * every frame. Skipping applies only while nobody watches the camera's stream. (The decode scale
     * was removed: it saved ~1 point of a core at best.)
     */
    private static final int DEF_PROCESS_EVERY = envInt("SPECTRUM_POLICY_PROCESS_EVERY", 1);

    private org.wpilib.networktables.IntegerSubscriber inProcessEvery;
    private String policyDir;

    private volatile int[] activePolicy = {1, 0, 1};

    /** The frame policy in force: {1, 0, processEvery} (scale and fullEvery fixed: skipping only). */
    public int[] activePolicy() {
        return activePolicy;
    }

    private static int envInt(String name, int def) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? def : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private synchronized int[] policy() {
        int every = DEF_PROCESS_EVERY;
        if (inProcessEvery != null) {
            long v = inProcessEvery.get();
            if (v > 0) every = (int) v;
        }
        return new int[] {1, 0, every};
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
            if (ownQualityPub != null) ownQualityPub.close();
            ownPub = ownInst.getRawTopic(name).publish(topic.getTypeString());
            // photonvision-61's tagQuality, on the same connection as the results it belongs to
            ownQualityPub =
                    ownInst
                            .getDoubleArrayTopic(name.substring(0, name.lastIndexOf('/')) + "/tagQuality")
                            .publish(
                                    org.wpilib.networktables.PubSubOption.periodic(0.01),
                                    org.wpilib.networktables.PubSubOption.SEND_ALL,
                                    org.wpilib.networktables.PubSubOption.KEEP_DUPLICATES);
            ownTopic = name;
        }
        return ownInst.isConnected();
    }

    /** The camera's VisionRunner closed: its own client goes. */
    public synchronized void close() {
        pubHandle = 0;
        if (ownPub != null) ownPub.close();
        if (ownQualityPub != null) ownQualityPub.close();
        ownQualityPub = null;
        if (ownInst != null) ownInst.close();
        ownPub = null;
        ownInst = null;
        if (inProcessEvery != null) {
            inProcessEvery.close();
            inProcessEvery = null;
            policyDir = null;
        }
    }

    private static double envFps(String name, double def) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? def : Double.parseDouble(v.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** The Java pipeline's rate while results are published natively. */
    public static double javaFpsNow() {
        return org.photonvision.vision.frame.consumer.MJPGFrameConsumer.anyMatViewers()
                ? STREAM_FPS
                : JAVA_FPS;
    }

    /**
     * NapaDetector, after each streamed frame: refresh this camera's native settings, track whether
     * it publishes.
     */
    void update(long napaHandle) {
        if (napaHandle == 0) return;
        if (napaHandle != lastNapaHandle) { // a native camera reopened: its counters start over
            lastNapaHandle = napaHandle;
            published = 0;
        }
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
        try {
            // the frame just detected: its camera-clock stamp (the V4L2 stamp, hwtimestamps=1) anchors
            // the
            // native side's prediction for the frames it publishes before Java sees them
            long anchor = camShiftNs > 0 && frameCaptureNs > 0 ? frameCaptureNs + camShiftNs : 0;
            camStamped =
                    org.photonvision.jni.NapaDetectorJNI.setNativeCamShift(napaHandle, camShiftNs, anchor);
        } catch (UnsatisfiedLinkError e) {
            // an older NAPA library: first-sight stamps only
        }
        // Rewind: while it records, the native results (with their quality) go into its vision log
        String cam = rewindCamera;
        if (cam != null && org.photonvision.rewind.VisionLog.isOpen()) {
            try {
                byte[][] items = org.photonvision.jni.NapaDetectorJNI.drainNativeResults(napaHandle);
                if (items != null)
                    for (byte[] it : items) {
                        var bb = java.nio.ByteBuffer.wrap(it).order(java.nio.ByteOrder.LITTLE_ENDIAN);
                        long t = bb.getLong();
                        int nq = bb.getInt();
                        double[] q = new double[nq];
                        for (int i = 0; i < nq; i++) q[i] = bb.getDouble();
                        byte[] packet = new byte[bb.remaining()];
                        bb.get(packet);
                        org.photonvision.rewind.VisionLog.rawResult(cam, packet, q, t);
                    }
            } catch (UnsatisfiedLinkError e) {
                // an older NAPA library
            }
        }
        try {
            org.photonvision.jni.NapaDetectorJNI.setNativeQualityPub(napaHandle, qualityHandle);
        } catch (UnsatisfiedLinkError e) {
            // an older NAPA library: no tag quality from the native side
        }
        try {
            int[] pol = policy();
            activePolicy = pol;
            org.photonvision.jni.NapaDetectorJNI.setNativePolicy(napaHandle, pol[0], pol[1], pol[2]);
        } catch (UnsatisfiedLinkError e) {
            // an older NAPA library: full scale, every frame
        }
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

    private volatile long camStamped = 0;

    /** Results the native side stamped from the camera clock so far. */
    public long camStamped() {
        return camStamped;
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
