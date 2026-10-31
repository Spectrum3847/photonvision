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
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.jni.CscoreExtras;
import org.photonvision.jni.GpuDetectorJNI;
import org.photonvision.vision.frame.FrameThresholdType;
import org.photonvision.vision.opencv.CVMat;
import org.photonvision.vision.processes.VisionSourceSettables;
import org.wpilib.util.PixelFormat;
import org.wpilib.util.RawFrame;
import org.wpilib.vision.camera.CvSink;
import org.wpilib.vision.camera.UsbCamera;
import org.wpilib.vision.camera.VideoMode;
import org.wpilib.vision.camera.VideoSource;
import org.wpilib.vision.stream.CameraServer;

public class USBFrameProvider extends CpuImageProcessor {
    private final Logger logger;

    private UsbCamera camera = null;
    private CvSink cvSink = null;

    // SpectrumJetson (photonvision-02): when the pipeline wants GREYSCALE (AprilTag, AprilTagCuda,
    // ArUco), grab from a GRAY sink, so no BGR frame is made only to be converted to gray. Other
    // pipelines (HSV, calibration, driver mode) keep the BGR sink.
    private CvSink graySink = null;
    private volatile boolean useGray = false;

    @SuppressWarnings("SpellCheckingInspection")
    private VisionSourceSettables settables;

    private Runnable connectedCallback;

    private long lastTime = 0;

    @SuppressWarnings("SpellCheckingInspection")
    public USBFrameProvider(
            UsbCamera camera, VisionSourceSettables visionSettables, Runnable connectedCallback) {
        this.camera = camera;
        this.cvSink = CameraServer.getVideo(this.camera);
        this.logger =
                new Logger(
                        USBFrameProvider.class, visionSettables.getConfiguration().nickname, LogGroup.Camera);
        this.directGray = new DirectDecode(logger, "Direct MJPEG->gray decode", "cscore's conversion");
        this.directBgr = new DirectDecode(logger, "Hardware MJPEG->BGR decode", "cscore's decode");
        this.cvSink.setEnabled(true);

        this.settables = visionSettables;

        this.connectedCallback = connectedCallback;
    }

    final double CSCORE_DEFAULT_FRAME_TIMEOUT = 1.0 / 4.0;

    @Override
    public CapturedFrame getInputMat() {
        if (!cameraPropertiesCached && camera.isConnected()) {
            onCameraConnected();
        }

        // SpectrumJetson (photonvision-09): MJPEG straight to grayscale. cscore's gray conversion
        // decodes every JPEG to full-colour BGR and then converts that to gray (Frame::ConvertImpl):
        // 8.9 ms a frame on the Jetson, ~90% of a core per camera at 100 fps. Taking the camera's
        // JPEG as it arrived and decoding only the brightness (Y) channel costs ~2.6 ms
        // (libjpeg-turbo in the detector library).
        if (useGray && graySink != null && directGray.shouldTry()) {
            var mode = settables.getCurrentVideoMode();
            if (mode != null && mode.pixelFormat == PixelFormat.MJPEG) {
                return grabMjpegAsGray(mode.width, mode.height, m_blockForFrames ? 0 : lastTime);
            }
        }

        // SpectrumJetson (photonvision-18): colour frames (game pieces, driver mode, calibration)
        // decoded on the Jetson's JPEG hardware when it's switched on (SPECTRUM_JPEG_DECODER=nvjpg):
        // ~1.6 ms of CPU a 1280x800 frame, against ~5.7 ms for cscore's own decode, same pixels.
        if (!useGray && directBgr.shouldTry() && hardwareJpegDecode()) {
            var mode = settables.getCurrentVideoMode();
            if (mode != null && mode.pixelFormat == PixelFormat.MJPEG) {
                return grabMjpegAsBgr(mode.width, mode.height, m_blockForFrames ? 0 : lastTime);
            }
        }

        if (m_blockForFrames) {
            // We allocate memory so we don't fill a Mat in use by another thread (memory model is easier)
            var mat = new CVMat();
            // This is from wpi::nt::Now, or WPIUtilJNI.now(). The epoch from grabFrame is nS since
            // Hal::initialize was called
            // TODO - under the hood, this incurs an extra copy. We should avoid this, if we
            // can.
            CvSink sink = activeSink();
            long captureTimeNs = sink.grabFrame(mat.getMat(), CSCORE_DEFAULT_FRAME_TIMEOUT);

            if (captureTimeNs == 0) {
                var error = sink.getError();
                logger.error("Error grabbing image: " + error);
            }

            return new CapturedFrame(
                    mat, settables.getFrameStaticProperties(), toMidExposureNs(captureTimeNs));
        } else {
            // We allocate memory so we don't fill a Mat in use by another thread (memory model is easier)
            // TODO - consider a frame pool
            // TODO - getCurrentVideoMode is a JNI call for us, but profiling indicates it's fast
            var cameraMode = settables.getCurrentVideoMode();
            CvSink sink = activeSink();
            boolean gray = sink == graySink;
            var frame = new RawFrame();
            frame.setInfo(
                    cameraMode.width,
                    cameraMode.height,
                    cameraMode.width * (gray ? 1 : 3),
                    gray ? PixelFormat.GRAY : PixelFormat.BGR);

            // This is from wpi::nt::Now, or WPIUtilJNI.now(). The epoch from grabFrame is nS since
            // Hal::initialize was called
            long captureTimeNs =
                    CscoreExtras.grabRawSinkFrameTimeoutLastTime(
                            sink.getHandle(), frame.getNativeObj(), CSCORE_DEFAULT_FRAME_TIMEOUT, lastTime);
            lastTime = captureTimeNs;

            CVMat ret;

            if (captureTimeNs == 0) {
                var error = sink.getError();
                logger.error("Error grabbing image: " + error);

                frame.close();
                ret = new CVMat();
            } else {
                // No error! yay
                var mat = new Mat(CscoreExtras.wrapRawFrame(frame.getNativeObj()));

                ret = new CVMat(mat, frame);
            }

            return new CapturedFrame(
                    ret, settables.getFrameStaticProperties(), toMidExposureNs(captureTimeNs));
        }
    }

    private boolean loggedDirectGray = false;

    /**
     * SpectrumJetson (photonvision-27): off after repeated failures (retried every 10 s), or with a
     * detector library that can't. Created in the constructor, after the logger.
     */
    private final DirectDecode directGray;

    // SpectrumJetson (photonvision-27): every frame whose JPEG couldn't be decoded.
    private volatile long decodeFailures = 0;

    /** Frames whose JPEG couldn't be decoded since this camera started. */
    public long getDecodeFailures() {
        return decodeFailures;
    }

    private CapturedFrame directGrayFailed(String why) {
        decodeFailures++;
        directGray.failed(why);
        return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
    }

    /**
     * SpectrumJetson (photonvision-09): the newest frame after {@code afterTimeNs} (0: the next
     * frame), as the camera's own JPEG, decoded straight to 8-bit gray by the detector library.
     */
    private CapturedFrame grabMjpegAsGray(int width, int height, long afterTimeNs) {
        CvSink sink = graySink;
        // A fresh RawFrame each time: its data buffer is then allocated for exactly this frame,
        // so its size is the JPEG's real length (a reused RawFrame keeps its first size).
        var frame = new RawFrame();
        try {
            frame.setInfo(0, 0, 0, PixelFormat.UNKNOWN); // the camera's own image, untouched
            // This JNI call fills the native frame but not the Java RawFrame's fields, so the
            // decode reads the native frame directly.
            long captureTimeNs =
                    CscoreExtras.grabRawSinkFrameTimeoutLastTime(
                            sink.getHandle(), frame.getNativeObj(), CSCORE_DEFAULT_FRAME_TIMEOUT, afterTimeNs);
            if (captureTimeNs == 0) {
                logger.error("Error grabbing image: " + sink.getError());
                return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
            }
            lastTime = captureTimeNs;
            Mat gray = new Mat(height, width, CvType.CV_8UC1);
            int rc;
            try {
                rc = GpuDetectorJNI.decodeMjpegGray(frame.getNativeObj(), gray.getNativeObjAddr());
            } catch (LinkageError e) { // no detector library, or one without the decoder
                gray.release();
                directGray.unavailable(
                        "No MJPEG->gray decoder in the detector library; using cscore's conversion");
                return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
            }
            if (rc == -3) {
                // Not the size of the video mode PhotonVision set (briefly while the camera switches;
                // for good if cscore lost the mode): drop it, and fix the camera if it lasts.
                gray.release();
                if (directGray.sizeMismatch()) {
                    reconnectForVideoMode(width, height, directGray.reconnects());
                }
                return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
            }
            if (rc != 0) {
                gray.release();
                return directGrayFailed(rc == -1 ? "frame not MJPEG" : "bad JPEG");
            }
            directGray.succeeded();
            if (!loggedDirectGray) {
                loggedDirectGray = true;
                logger.info(
                        "Capture format: MJPEG decoded straight to gray ("
                                + gray.cols()
                                + "x"
                                + gray.rows()
                                + ")");
            }
            return new CapturedFrame(
                    new CVMat(gray), settables.getFrameStaticProperties(), toMidExposureNs(captureTimeNs));
        } finally {
            frame.close();
        }
    }

    /** SpectrumJetson (photonvision-18): off after repeated failures, like directGray. */
    private final DirectDecode directBgr;

    private boolean loggedDirectBgr = false;

    private boolean hardwareJpegDecode() {
        try {
            return GpuDetectorJNI.hardwareJpegDecode();
        } catch (LinkageError e) {
            directBgr.unavailable("The detector library has no hardware colour decode; using cscore's");
            return false;
        }
    }

    /**
     * SpectrumJetson (photonvision-18): like grabMjpegAsGray, for colour frames, decoded to BGR by
     * the detector library (the Jetson's JPEG hardware plus a CUDA colour conversion).
     */
    private CapturedFrame grabMjpegAsBgr(int width, int height, long afterTimeNs) {
        CvSink sink = cvSink; // the enabled sink in colour mode; UNKNOWN gets the raw JPEG
        var frame = new RawFrame();
        try {
            frame.setInfo(0, 0, 0, PixelFormat.UNKNOWN);
            long captureTimeNs =
                    CscoreExtras.grabRawSinkFrameTimeoutLastTime(
                            sink.getHandle(), frame.getNativeObj(), CSCORE_DEFAULT_FRAME_TIMEOUT, afterTimeNs);
            if (captureTimeNs == 0) {
                logger.error("Error grabbing image: " + sink.getError());
                return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
            }
            lastTime = captureTimeNs;
            Mat bgr = new Mat(height, width, CvType.CV_8UC3);
            int rc = GpuDetectorJNI.decodeMjpegBgr(frame.getNativeObj(), bgr.getNativeObjAddr());
            if (rc == -3) {
                bgr.release();
                if (directBgr.sizeMismatch()) reconnectForVideoMode(width, height, directBgr.reconnects());
                return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
            }
            if (rc != 0) {
                bgr.release();
                decodeFailures++;
                directBgr.failed(rc == -1 ? "frame not MJPEG" : "bad JPEG");
                return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
            }
            directBgr.succeeded();
            if (!loggedDirectBgr) {
                loggedDirectBgr = true;
                logger.info(
                        "Capture format: MJPEG decoded to BGR by the JPEG hardware ("
                                + width
                                + "x"
                                + height
                                + ")");
            }
            return new CapturedFrame(
                    new CVMat(bgr), settables.getFrameStaticProperties(), toMidExposureNs(captureTimeNs));
        } finally {
            frame.close();
        }
    }

    /**
     * SpectrumJetson (photonvision-27, -37): the camera streams another size than cscore's mode.
     * Seen at ~1 in 10 starts with 4 cameras: cscore opens the camera at its lowest mode, then
     * PhotonVision's mode makes it reopen the device, and VIDIOC_S_FMT fails with EBUSY (the
     * previous handle not yet released). cscore keeps the new mode as its mode anyway, so setting it
     * again is a no-op, and a reconnect (FORCE_CLOSE) only restarts streaming without setting the
     * format. Switching to another mode and back makes two real changes, each reopening the device
     * and setting its format.
     */
    private void reconnectForVideoMode(int width, int height, int attempt) {
        String why =
                "The camera is sending frames that aren't "
                        + width
                        + "x"
                        + height
                        + ", the video mode PhotonVision set; applying the mode again (attempt "
                        + attempt
                        + ")";
        VideoMode want = camera.getVideoMode();
        VideoMode other = null;
        try {
            for (VideoMode m : camera.enumerateVideoModes()) {
                if (m.pixelFormat == want.pixelFormat && (m.width != want.width || m.height != want.height)) {
                    other = m;
                    break;
                }
            }
        } catch (Exception e) {
            other = null;
        }
        if (other != null) {
            logger.warn(why + ": reopening it (switching through " + other.width + "x" + other.height + ")");
            camera.setVideoMode(other);
            camera.setVideoMode(want);
            return;
        }
        logger.warn(why + ": no other video mode to reopen it with; restarting its stream");
        camera.setConnectionStrategy(VideoSource.ConnectionStrategy.FORCE_CLOSE);
        long deadline = System.nanoTime() + 1_000_000_000L;
        while (camera.isConnected() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        camera.setConnectionStrategy(VideoSource.ConnectionStrategy.AUTO_MANAGE);
    }

    /**
     * SpectrumJetson (photonvision-13): the camera's delay from the end of exposure to its first USB
     * packet (sensor readout + JPEG start), in microseconds. Not visible from here; measure it on
     * the robot (spin in front of a tag) and set SPECTRUM_CAMERA_DELAY_US. Default 0.
     */
    private static final long CAMERA_DELAY_US = envLong("SPECTRUM_CAMERA_DELAY_US", 0);

    private static long envLong(String name, long fallback) {
        try {
            String v = System.getenv(name);
            return v == null || v.isBlank() ? fallback : Long.parseLong(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private boolean loggedMidExposure = false;

    /**
     * SpectrumJetson (photonvision-13): move a frame's timestamp to mid-exposure. uvcvideo stamps a
     * frame when its first USB packet arrives, which is after the whole exposure and the camera's
     * internal delay. Robot code fuses the pose at this time, so a late stamp shows up as error
     * while turning (2.5 ms at 360 deg/s is ~1 degree). The exposure is known exactly here and
     * changes when it's retuned, so it's corrected here rather than as a constant on the robot.
     * Only the published time moves; {@code lastTime} keeps the driver's time for "newest frame"
     * checks. WPILib 2027 timestamps are nanoseconds.
     */
    private long toMidExposureNs(long captureTimeNs) {
        if (captureTimeNs == 0) return 0;
        double exposureUs = settables.getExposureTimeUs();
        if (exposureUs <= 0) return captureTimeNs - CAMERA_DELAY_US * 1000;
        long shiftNs = ((long) (exposureUs / 2) + CAMERA_DELAY_US) * 1000;
        if (!loggedMidExposure) {
            loggedMidExposure = true;
            logger.info(
                    "Frame timestamps moved "
                            + shiftNs / 1e6
                            + " ms earlier, to mid-exposure (exposure "
                            + exposureUs / 1000.0
                            + " ms, camera delay "
                            + CAMERA_DELAY_US / 1000.0
                            + " ms)");
        }
        return captureTimeNs - shiftNs;
    }

    private CvSink activeSink() {
        CvSink g = graySink;
        return useGray && g != null ? g : cvSink;
    }

    /** SpectrumJetson (photonvision-02): gray pipelines capture gray (see graySink). */
    @Override
    public void requestFrameThresholdType(FrameThresholdType type) {
        super.requestFrameThresholdType(type);
        boolean gray = type == FrameThresholdType.GREYSCALE;
        synchronized (this) {
            if (cvSink == null || gray == useGray) return;
            if (gray && graySink == null) {
                graySink = new CvSink("opencv_gray_" + camera.getName(), PixelFormat.GRAY);
                graySink.setSource(camera);
            }
            if (graySink != null) graySink.setEnabled(gray);
            cvSink.setEnabled(!gray);
            useGray = gray;
            logger.info(
                    "Capture format: " + (gray ? "gray (MJPEG modes are decoded straight to gray)" : "BGR"));
        }
    }

    @Override
    public String getName() {
        return "USBFrameProvider - " + cvSink.getName();
    }

    @Override
    public void release() {
        CameraServer.removeServer(cvSink.getName());
        cvSink.close();
        cvSink = null;
        synchronized (this) {
            if (graySink != null) {
                graySink.close();
                graySink = null;
            }
            useGray = false;
        }
    }

    @Override
    public void onCameraConnected() {
        logger.info("Camera connected! running callback");

        super.onCameraConnected();

        this.connectedCallback.run();
    }

    @Override
    public boolean checkCameraConnected() {
        return camera.isConnected();
    }

    public void updateSettables(VisionSourceSettables settables) {
        this.settables = settables;
    }
}
