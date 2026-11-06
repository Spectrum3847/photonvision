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

package org.photonvision.vision.frame.provider;

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.imgproc.Imgproc;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.jni.NapaDetectorJNI;
import org.photonvision.rewind.RewindRecorder;
import org.photonvision.vision.frame.FrameThresholdType;
import org.photonvision.vision.opencv.CVMat;
import org.photonvision.vision.processes.VisionSourceSettables;
import org.wpilib.util.PixelFormat;
import org.wpilib.vision.camera.CvSink;
import org.wpilib.vision.camera.UsbCamera;
import org.wpilib.vision.camera.VideoMode;

/**
 * Spectrum (sc/mjpeg-napa) native camera backend (SpectrumJetson
 * docs/SYSTEMCORE-NATIVE-BACKEND.md): the frame provider of a camera with no cscore UsbCamera. A
 * native thread (NapaDetectorJNI.cameraOpen) owns the V4L2 queue and captures, decodes as the bytes
 * land, runs streaming NAPA, pose, publish, tag quality and Rewind every frame; this only hands the
 * Java pipeline (UI, stream, health) the latest image at its own low rate.
 *
 * <p>cscore handover: while the calibration pipeline runs, or the selected video mode isn't MJPEG,
 * or the native open failed, the native capture is closed and a temporary cscore UsbCamera streams
 * the device for the Java pipeline (closed again, and native reopened, afterwards).
 */
public class NativeFrameProvider extends CpuImageProcessor {
    private final Logger logger;
    private final String dev;
    private final String name;
    private VisionSourceSettables settables;

    // read by the health getters, which run on this camera's vision thread like every close
    private volatile long handle = 0;
    private VideoMode openMode = null;
    private volatile boolean reopen = false;
    private volatile boolean calibrating = false;
    private boolean nativeFailed = false;
    private Mat gray;
    private RewindRecorder rewind;
    private long opens = 0;
    private volatile String state = "starting";
    private volatile String problem = "";

    // the cscore handover (calibration, non-MJPEG modes, native open failure)
    private UsbCamera cscore;
    private CvSink cscoreSink;
    private VideoMode cscoreMode;

    public NativeFrameProvider(String dev, String name, VisionSourceSettables settables) {
        this.dev = dev;
        this.name = name;
        this.settables = settables;
        this.logger =
                new Logger(
                        NativeFrameProvider.class, settables.getConfiguration().nickname, LogGroup.Camera);
        cameraPropertiesCached = true; // the settables read the device already
    }

    /** The video mode changed (settables): reopen in it on the vision thread. */
    public void onVideoModeChanged(VideoMode mode) {
        reopen = true;
    }

    /** VisionRunner: the calibration pipeline is (not) running; it gets cscore's frames. */
    public void setCalibrating(boolean on) {
        if (on != calibrating) {
            calibrating = on;
            reopen = true;
        }
    }

    /** The NAPA handle that owns this camera (0: not capturing natively right now). */
    public long nativeCameraHandle() {
        return handle;
    }

    public boolean isNative() {
        return handle != 0;
    }

    // test hook: while this file exists, every native camera is handed to cscore as for calibration
    private static final java.io.File HANDOVER_TEST_FILE =
            new java.io.File("/tmp/spectrum-native-handover-test");
    private long handoverTestCheckedNs = 0;
    private boolean handoverTest = false;

    private boolean wantNative() {
        long now = System.nanoTime();
        if (now - handoverTestCheckedNs > 500_000_000L) {
            handoverTestCheckedNs = now;
            boolean t = HANDOVER_TEST_FILE.exists();
            if (t != handoverTest) {
                handoverTest = t;
                reopen = true;
                logger.info("Native camera: handover test " + (t ? "on" : "off"));
            }
        }
        VideoMode m = settables.getCurrentVideoMode();
        return !calibrating
                && !handoverTest
                && !nativeFailed
                && m != null
                && m.pixelFormat == PixelFormat.MJPEG;
    }

    private void closeNative() {
        if (rewind != null) {
            rewind.stop();
            rewind = null;
        }
        if (handle != 0) {
            // this camera's NativePublish (this is its vision thread) must not use the handle again
            var np = org.photonvision.vision.pipe.impl.NativePublish.current();
            if (np != null && np.cameraHandle == handle) np.cameraHandle = 0;
            long h = handle;
            // sequence IDs keep rising across the handover (the Java path continues from the native one)
            sequenceID = (int) Math.max(sequenceID, NapaDetectorJNI.cameraSequence(h));
            synchronized (jpegLock) { // the stream pump uses the handle under this lock
                handle = 0;
            }
            NapaDetectorJNI.destroy(h); // stops the camera thread, closes the capture fd
            openMode = null;
        }
    }

    private boolean openNative(VideoMode m) {
        long h = 0;
        for (int attempt = 0; attempt < 10 && h == 0; attempt++) {
            h = NapaDetectorJNI.cameraOpenSeq(dev, m.width, m.height, m.fps, "tag36h11", sequenceID);
            if (h == 0) sleepMs(100);
        }
        if (h == 0) return false;
        handle = h;
        openMode = m;
        opens++;
        if (gray == null || gray.cols() != m.width || gray.rows() != m.height) {
            if (gray != null) gray.release();
            gray = new Mat(m.height, m.width, CvType.CV_8UC1);
        }
        rewind = RewindRecorder.forNative(h, () -> settables.getConfiguration().nickname);
        logger.info(
                "Native camera: "
                        + dev
                        + " captured natively ("
                        + m.width
                        + "x"
                        + m.height
                        + " MJPEG "
                        + m.fps
                        + " fps)");
        return true;
    }

    private void closeCscore() {
        if (cscoreSink != null) {
            cscoreSink.close();
            cscoreSink = null;
        }
        if (cscore != null) {
            cscore.close(); // closes the device: native may take it again
            cscore = null;
            cscoreMode = null;
            logger.info("Native camera: cscore handover ended");
        }
    }

    private void openCscore(VideoMode m) {
        if (cscore == null) {
            cscore = new UsbCamera(name + "-handover", dev);
            cscoreSink = new CvSink("native_handover_" + name);
            cscoreSink.setSource(cscore);
            logger.info(
                    "Native camera: cscore handover ("
                            + (calibrating || handoverTest
                                    ? "calibration"
                                    : nativeFailed ? "native open failed" : "non-MJPEG mode")
                            + ")");
        }
        if (!m.equals(cscoreMode)) {
            cscore.setVideoMode(m);
            cscoreMode = m;
        }
    }

    /** On the vision thread: native or the cscore handover, in the selected mode. */
    private void sync() {
        VideoMode m = settables.getCurrentVideoMode();
        boolean wantNative = wantNative();
        if (!reopen && (wantNative ? handle != 0 : cscore != null)) return;
        reopen = false;
        if (wantNative) {
            if (handle != 0 && m.equals(openMode)) return;
            closeNative();
            closeCscore();
            if (!openNative(m)) {
                logger.error("Native camera: could not open " + dev + " natively; using cscore for it");
                problem = "native open failed";
                nativeFailed = true;
                openCscore(m);
            } else {
                problem = "";
            }
        } else {
            closeNative();
            openCscore(m);
        }
    }

    // timestamps: the native thread stamps from the camera clock; this keeps NativePublish's shifts
    private long cameraDelayUs = -1, ptsDelayUs = -1;

    /** First bytes -> mid-exposure for this camera's native publish (see USBFrameProvider). */
    public volatile long nativeMidShiftNs = 0;

    /** Camera-clock stamp -> mid-exposure for the native publish; 0 without hwtimestamps. */
    public volatile long nativeCamShiftNs = 0;

    private long toMidExposureNs(long stampNs) {
        if (stampNs == 0) return 0;
        if (cameraDelayUs < 0) {
            var c = settables.getConfiguration();
            cameraDelayUs =
                    USBFrameProvider.cameraDelayUs(
                            USBFrameProvider.CAMERA_DELAY_SPEC, c.nickname, c.uniqueName, 400);
            ptsDelayUs =
                    USBFrameProvider.cameraDelayUs(
                            USBFrameProvider.PTS_DELAY_SPEC, c.nickname, c.uniqueName, 180);
        }
        long halfExposureUs =
                USBFrameProvider.realExposureUs(settables, settables.getExposureTimeUs()) / 2;
        nativeMidShiftNs = (halfExposureUs + cameraDelayUs + USBFrameProvider.SEEN_LAG_US) * 1000;
        nativeCamShiftNs = USBFrameProvider.HW_TIMESTAMPS ? (halfExposureUs + ptsDelayUs) * 1000 : 0;
        long shiftUs =
                USBFrameProvider.HW_TIMESTAMPS
                        ? halfExposureUs + ptsDelayUs
                        : halfExposureUs + cameraDelayUs + USBFrameProvider.UVC_STAMP_LAG_US;
        return stampNs - shiftUs * 1000;
    }

    private long lastFrameNs = 0;

    @Override
    CapturedFrame getInputMat() {
        try {
            sync();
        } catch (RuntimeException e) {
            logger.error("Native camera: switching capture failed", e);
        }
        if (handle != 0) {
            state = "streaming";
            long end = System.nanoTime() + 200_000_000L;
            while (System.nanoTime() < end) {
                long stamp =
                        NapaDetectorJNI.cameraLatestGray(handle, gray.dataAddr(), gray.cols(), gray.rows());
                // the image kept since the last request is up to a Java period old (the camera stamp is
                // CLOCK_MONOTONIC, like nanoTime): this call asked for the next one; wait for it
                if (stamp != 0 && System.nanoTime() - stamp > 60_000_000L) stamp = 0;
                if (stamp != 0) {
                    lastFrameNs = System.nanoTime();
                    return new CapturedFrame(
                            new CVMat(forPipeline(gray)),
                            settables.getFrameStaticProperties(),
                            toMidExposureNs(stamp));
                }
                sleepMs(2);
            }
            state = "no-frames";
        } else if (cscoreSink != null) {
            state = "cscore-handover";
            Mat mat = new Mat();
            long t = cscoreSink.grabFrame(mat, 0.25);
            if (t != 0 && !mat.empty()) {
                lastFrameNs = System.nanoTime();
                return new CapturedFrame(
                        new CVMat(mat), settables.getFrameStaticProperties(), toMidExposureNs(t));
            }
            mat.release();
        }
        return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
    }

    /** The gray image as the pipeline wants it: gray as is, else BGR (HSV / colored pipelines). */
    private Mat forPipeline(Mat g) {
        if (m_processType == FrameThresholdType.GREYSCALE) return g.clone();
        Mat bgr = new Mat();
        Imgproc.cvtColor(g, bgr, Imgproc.COLOR_GRAY2BGR);
        return bgr;
    }

    // health (CameraHealthPublisher)

    public long getDecodeFailures() {
        long[] s = handle != 0 ? NapaDetectorJNI.cameraStats(handle) : null;
        return s != null ? s[1] : 0;
    }

    public long getRecoveries() {
        return Math.max(0, opens - 1);
    }

    public String getProblem() {
        return problem;
    }

    public String getHealthState() {
        return state;
    }

    public long getLastFrameAgeMs() {
        if (handle != 0) {
            long[] s = NapaDetectorJNI.cameraStats(handle);
            if (s != null) return s[3];
        }
        return lastFrameNs == 0 ? -1 : (System.nanoTime() - lastFrameNs) / 1_000_000;
    }

    @Override
    public String getName() {
        return "NativeFrameProvider - " + name;
    }

    @Override
    protected boolean checkCameraConnected() {
        return new java.io.File(dev).exists();
    }

    public void updateSettables(VisionSourceSettables settables) {
        this.settables = settables;
        reopen = true;
    }

    // ---- stream on demand: the camera's own JPEG to the input stream, no re-encode ----
    private final Object jpegLock = new Object();
    private volatile org.photonvision.vision.frame.consumer.MJPGFrameConsumer jpegTarget;
    private Thread jpegPump;
    private static final double STREAM_FPS = streamFps();

    private static double streamFps() {
        try {
            String v = System.getenv("SPECTRUM_STREAM_FPS");
            return v == null ? 30 : Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    /** VisionModule: the input stream this camera's JPEGs go to while it is watched. */
    public synchronized void setJpegStream(
            org.photonvision.vision.frame.consumer.MJPGFrameConsumer target) {
        jpegTarget = target;
        if (jpegPump == null
                && target != null
                && !"0".equals(System.getenv("SPECTRUM_STREAM_PASSTHROUGH"))) {
            jpegPump = new Thread(this::pumpJpeg, "NativeStream-" + name);
            jpegPump.setDaemon(true);
            jpegPump.start();
        }
    }

    /** True while the input stream is fed natively (the Java pipeline need not feed it). */
    public boolean streamsNatively() {
        return jpegPump != null && handle != 0;
    }

    private void pumpJpeg() {
        var buf = java.nio.ByteBuffer.allocateDirect(1 << 20);
        long period = STREAM_FPS > 0 ? (long) (1e9 / STREAM_FPS) : 33_000_000L;
        long next = System.nanoTime();
        while (!Thread.currentThread().isInterrupted()) {
            var t = jpegTarget;
            boolean sent = false;
            if (t != null && t.hasViewers()) {
                synchronized (jpegLock) {
                    long h = handle;
                    VideoMode m = openMode;
                    if (h != 0 && m != null) {
                        buf.clear();
                        int n = NapaDetectorJNI.cameraLatestJpeg(h, buf);
                        if (n < 0) {
                            buf = java.nio.ByteBuffer.allocateDirect(-n * 2);
                        } else if (n > 0) {
                            buf.limit(n);
                            t.acceptJpeg(buf, m.width, m.height);
                            sent = true;
                        }
                    }
                }
            }
            // nobody watching (or no native capture): check 4 times a second; else the stream rate
            long now = System.nanoTime();
            next =
                    sent
                            ? Math.max(next + period, now)
                            : now + (t != null && t.hasViewers() ? 5_000_000L : 250_000_000L);
            long sleep = next - now;
            if (sleep > 0) {
                try {
                    Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
    }

    @Override
    public void release() {
        Thread p;
        synchronized (this) {
            p = jpegPump;
            jpegPump = null;
        }
        if (p != null) p.interrupt();
        closeNative();
        closeCscore();
        if (gray != null) {
            gray.release();
            gray = null;
        }
        try {
            NapaDetectorJNI.deviceClose(dev);
        } catch (UnsatisfiedLinkError e) {
            // no library: nothing open
        }
    }

    private static void sleepMs(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
