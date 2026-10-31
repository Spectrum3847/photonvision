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
import org.photonvision.vision.processes.IdleMode;
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

    // SpectrumJetson (photonvision-29): notices a connected camera that delivers no usable frames and
    // recovers it (reconnect, then a USB-level reset).
    private final StuckCameraWatchdog stuckWatchdog = new StuckCameraWatchdog(System::nanoTime);
    private static final java.nio.file.Path STUCK_TEST_FILE =
            java.nio.file.Path.of("/tmp/spectrum-camera-stuck-test");
    private long stuckTestCheckedNs = 0;
    private boolean stuckTest = false;

    @Override
    public CapturedFrame getInputMat() {
        CapturedFrame frame = grabInputMat();
        boolean usable =
                frame.captureTimestamp != 0
                        && frame.colorImage != null
                        && !frame.colorImage.getMat().empty();
        if (usable && stuckTestActive()) {
            // Test hook: pretend this camera is stuck, so the recovery can be tried on purpose.
            frame.colorImage.release();
            frame = new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
            usable = false;
        }
        if (usable) {
            String fixedBy = stuckWatchdog.good();
            if (!fixedBy.isEmpty())
                logger.info("The camera is delivering frames again, after " + fixedBy);
            if (!problem.isEmpty()) logger.info("The camera is working again (it had: " + problem + ")");
            problem = "";
        } else if (!camera.isConnected()) {
            String port = usbPort();
            // photonvision-81: a camera that stays disconnected with its USB device still there gets
            // USB resets; one gone from USB gives up (TXMAN1: nothing was tried for either).
            boolean devicePresent =
                    port != null
                            && java.nio.file.Files.exists(java.nio.file.Path.of("/sys/bus/usb/devices", port))
                            && portIsOurs(port); // photonvision-86
            if (stuckWatchdog.notConnected(devicePresent) == StuckCameraWatchdog.Action.USB_RESET) {
                recoverUsb(
                        String.format(
                                devicePresent
                                        ? "disconnected for %.0f s with its USB device still there"
                                        : "gone from USB for %.0f s", // photonvision-84
                                stuckWatchdog.secondsDisconnected()),
                        devicePresent);
            }
            // SpectrumJetson (photonvision-33): it's plugged in but won't answer (the kernel couldn't
            // enumerate it). A USB hub reset leaves a Thriftiest Cam like this, and on this carrier
            // board only a real power cut revives it: USB power can't be switched from software.
            // photonvision-81: the problem stays until the camera delivers frames again (it was
            // cleared 90 s after the kernel's message, with the camera still dead).
            if (org.photonvision.common.hardware.UsbTrouble.connectFailed(port, BANDWIDTH_WINDOW_NS)) {
                if (!problem.startsWith("not answering")) {
                    problem = "not answering on USB port " + port + ": replug it, or power-cycle the robot";
                    logger.error(
                            "The camera on USB port "
                                    + port
                                    + " is plugged in but won't answer (the kernel can't enumerate it). A USB hub"
                                    + " reset can leave a camera like this. Switching its port's power off and on is"
                                    + " tried (a Thriftiest Cam on the usbrecov firmware comes back from that); if it"
                                    + " doesn't, it needs its power cut: replug it, or power-cycle the robot.");
                }
            } else if (stuckWatchdog.goneFromUsb() && problem.isEmpty()) {
                problem = "gone from USB port " + port + ": replug it, or power-cycle the robot";
                logger.error(
                        "The camera has been gone from USB port "
                                + port
                                + " for over "
                                + Math.round(stuckWatchdog.secondsDisconnected())
                                + " s, and "
                                + StuckCameraWatchdog.MAX_USB_RESETS
                                + " port power toggles haven't brought it back. If it's still plugged in, it"
                                + " needs its power cut: replug it, or power-cycle the robot. Look in the kernel"
                                + " log (journalctl -k) for why it left.");
            }
        } else if (org.photonvision.common.hardware.UsbTrouble.bandwidthFailed(
                usbPort(), BANDWIDTH_WINDOW_NS)) {
            // SpectrumJetson (photonvision-31): the kernel refuses this camera USB bandwidth. Say so,
            // and don't reset it (that can't help); retry once a minute in case some frees up.
            if (problem.isEmpty()) {
                problem = "not enough USB bandwidth (USB port " + usbPort() + ")";
                logger.error(
                        "Not enough USB bandwidth for this camera on USB port "
                                + usbPort()
                                + ": other cameras on the same root port have reserved it. Run"
                                + " ~/SpectrumJetson/scripts/jetson/usb-bandwidth.py on the Jetson, or open the"
                                + " USB Bandwidth page: lower an allocation (every USB 2.0 port shares one"
                                + " budget).");
            }
            if (stuckWatchdog.noFrameNoBandwidth() == StuckCameraWatchdog.Action.RECONNECT) {
                reconnectCamera("retrying: not enough USB bandwidth a minute ago");
            }
        } else {
            switch (stuckWatchdog.noFrame()) {
                case RECONNECT ->
                        reconnectCamera(
                                String.format(
                                        "no usable frames for %.1f s while connected (e.g. only corrupt JPEGs);"
                                                + " reconnecting it",
                                        stuckWatchdog.secondsWithoutFrames()));
                case USB_RESET ->
                        recoverUsb(
                                String.format(
                                        "still no usable frames %.1f s later", stuckWatchdog.secondsWithoutFrames()),
                                true);
                case NONE -> {}
            }
            // photonvision-81: connected, but USB resets haven't brought it back.
            if (stuckWatchdog.state() == StuckCameraWatchdog.State.GAVE_UP && problem.isEmpty()) {
                problem =
                        "no frames after "
                                + StuckCameraWatchdog.MAX_USB_RESETS
                                + " USB resets: power-cycle the robot";
                logger.error(
                        "Giving up on this camera: still no usable frames after "
                                + StuckCameraWatchdog.MAX_USB_RESETS
                                + " USB resets. It needs its power cut (replug it, or power-cycle the robot). One"
                                + " more reset every "
                                + StuckCameraWatchdog.GAVE_UP_RETRY_NS / 1_000_000_000L
                                + " s.");
            }
        }
        return frame;
    }

    /** photonvision-81: health/state ("streaming", "stalled", "gave-up", ...). */
    public String getHealthState() {
        return stuckWatchdog.state().topicValue;
    }

    /** photonvision-81: milliseconds since the last usable frame, or -1 if none yet. */
    public long getLastFrameAgeMs() {
        return stuckWatchdog.lastFrameAgeMs();
    }

    // SpectrumJetson (photonvision-33): with a camera gone every grab failed and logged, ~40 lines a
    // second per camera (13 MB a minute with 4 cameras), so the system log kept only ~25 minutes.
    // Now: the first failure, then at most one line per 5 s, with a count.
    private long grabErrorLoggedNs = 0;
    private int grabErrorsSince = 0;

    private void logGrabError(String error) {
        long now = System.nanoTime();
        if (grabErrorLoggedNs != 0 && now - grabErrorLoggedNs < 5_000_000_000L) {
            grabErrorsSince++;
            return;
        }
        logger.error(
                "Error grabbing image: "
                        + error
                        + (grabErrorsSince > 0
                                ? " (and " + grabErrorsSince + " more since the last message)"
                                : ""));
        grabErrorLoggedNs = now;
        grabErrorsSince = 0;
    }

    /** Reconnects and USB resets done for this camera, for CameraHealthPublisher. */
    public long getRecoveries() {
        return stuckWatchdog.recoveries();
    }

    // SpectrumJetson (photonvision-31): why the camera gets no frames, when we know ("" if fine).
    private static final long BANDWIDTH_WINDOW_NS = 90_000_000_000L;
    private volatile String problem = "";
    private String usbPortCache = null;
    private long usbPortCheckedNs = 0;

    /** Why this camera gets no frames, if known: e.g. "not enough USB bandwidth (USB port 1-2.2)". */
    public String getProblem() {
        return problem;
    }

    /** The camera's USB port (sysfs name, e.g. "1-2.4"), or null (photonvision-32: public). */
    public String getUsbPort() {
        return usbPort();
    }

    /**
     * SpectrumJetson (photonvision-32): applies a new USB bandwidth allocation (UsbBandwidth) by
     * resetting the camera at the USB level, like a replug, so the driver probes it again under the
     * new cap. (A cscore reconnect isn't enough: the driver kept its last negotiation, 1600 bytes 4 s
     * after a change to 256.)
     */
    public void applyUsbBandwidth(int wantBytes) {
        String port = usbPort();
        usbReset("USB bandwidth allocation changed to " + wantBytes + " bytes per microframe", false);
        long deadline = System.nanoTime() + 6_000_000_000L;
        int now = -1;
        while (System.nanoTime() < deadline) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            now = org.photonvision.common.hardware.UsbBandwidth.reservedNow(port);
            if (now == wantBytes) {
                logger.info("Now reserving " + wantBytes + " bytes per microframe on USB port " + port);
                return;
            }
        }
        logger.warn(
                "Reserving "
                        + now
                        + " bytes per microframe on USB port "
                        + port
                        + " 6 s later, not "
                        + wantBytes);
    }

    /** The camera's USB port (sysfs name, e.g. "1-2.4"), or null; looked up at most every 5 s. */
    private String usbPort() {
        long now = System.nanoTime();
        if (usbPortCache == null || now - usbPortCheckedNs > 5_000_000_000L) {
            usbPortCheckedNs = now;
            try {
                var dev = java.nio.file.Path.of(camera.getPath()).toRealPath();
                usbPortCache =
                        java.nio.file.Path.of("/sys/class/video4linux", dev.getFileName().toString(), "device")
                                .toRealPath()
                                .getParent()
                                .getFileName()
                                .toString();
            } catch (Exception e) {
                // not there right now (unplugged, mid-reset): keep the last answer
            }
        }
        return usbPortCache;
    }

    /**
     * SpectrumJetson (photonvision-86): the camera in this USB port is ours, as far as we can tell. A
     * camera known by a serial of its own (its path is /dev/v4l/by-id/..._SJ-xxx-video-index0) can
     * move to another port, and another camera can take the port we last saw it in: then the device
     * there has a different serial. Found on the robot 2026-10-29: swapping two cameras' ports, one
     * camera's watchdog switched the other's port power off. A camera known by its port, or a port
     * with nothing in it, counts as ours.
     */
    private boolean portIsOurs(String port) {
        String path = camera.getPath();
        if (!path.contains("/by-id/") || !path.endsWith("-video-index0")) return true;
        String name = java.nio.file.Path.of(path).getFileName().toString();
        String serial =
                name.substring(name.lastIndexOf('_') + 1, name.length() - "-video-index0".length());
        try {
            var there = java.nio.file.Path.of("/sys/bus/usb/devices", port, "serial");
            return !java.nio.file.Files.exists(there)
                    || java.nio.file.Files.readString(there).strip().equals(serial);
        } catch (Exception e) {
            return true;
        }
    }

    /** Test hook: /tmp/spectrum-camera-stuck-test lists camera names to treat as stuck. */
    private boolean stuckTestActive() {
        long now = System.nanoTime();
        if (now - stuckTestCheckedNs > 1_000_000_000L) {
            stuckTestCheckedNs = now;
            boolean was = stuckTest;
            try {
                stuckTest =
                        java.nio.file.Files.exists(STUCK_TEST_FILE)
                                && java.util.Arrays.asList(
                                                java.nio.file.Files.readString(STUCK_TEST_FILE).strip().split("[,\\s]+"))
                                        .contains(settables.getConfiguration().nickname);
            } catch (Exception e) {
                stuckTest = false;
            }
            if (stuckTest != was) {
                logger.warn(
                        stuckTest
                                ? "Stuck-camera test: dropping this camera's frames"
                                : "Stuck-camera test over");
            }
        }
        return stuckTest;
    }

    /**
     * SpectrumJetson (photonvision-29): resets the camera at the USB level, like unplugging it: turns
     * its USB device's sysfs "authorized" off and on, so it re-enumerates and the driver probes it
     * again. PhotonVision opens cameras by their /dev/v4l/by-path name, so it finds it again.
     */
    private boolean usbReset(String why, boolean asError) {
        try {
            // photonvision-81: by the USB port (remembered from when the camera was there), so a
            // camera that has lost its /dev/video node can be reset too.
            String port = usbPort();
            if (port == null) throw new java.io.IOException("its USB port isn't known");
            var usbDevice = java.nio.file.Path.of("/sys/bus/usb/devices", port); // e.g. .../2-2.3
            var authorized = usbDevice.resolve("authorized");
            String msg =
                    "Resetting the camera at the USB level (" + usbDevice.getFileName() + "): " + why;
            if (asError) logger.error(msg);
            else logger.info(msg);
            java.nio.file.Files.writeString(authorized, "0");
            Thread.sleep(300); // photonvision-84: was 1 s
            java.nio.file.Files.writeString(authorized, "1");
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("USB reset failed: " + e);
        }
        return false;
    }

    /**
     * SpectrumJetson (photonvision-84): the watchdog's USB-level recovery, escalating. Found on the
     * robot 2026-10-29 (tests/usb-port-glitch): after a USB drop a camera can stay registered but
     * unusable ("can't set config #1, error -71"), and turning its "authorized" off and on (above)
     * fails the same way, every time. Switching its hub port's power off and on makes the kernel
     * enumerate it from scratch: a Thriftiest Cam on the usbrecov firmware streamed again 2 s later,
     * with the other cameras on the hub untouched. (The carrier board's USB 5 V stays on, so a camera
     * on the stock firmware stays wedged; nothing in software helps that one.)
     *
     * <ul>
     *   <li>The first reset since the last frame is the gentle one, if the device is there.
     *   <li>Later ones, a failed one, or a camera gone from USB: the port's power, off and on.
     *   <li>Then cscore reopens the device: after the camera came back, cscore once kept failing on
     *       the old one ("could not queue buffer 0" every 2 s) and never opened the new one, until
     *       PhotonVision was restarted.
     * </ul>
     */
    private void recoverUsb(String why, boolean devicePresent) {
        String port = usbPort();
        if (port == null) {
            logger.error("Can't reset the camera (" + why + "): its USB port isn't known");
            return;
        }
        if (!portIsOurs(port)) {
            // photonvision-86: our camera has moved; another one is in the port we last saw it in.
            logger.warn("Not resetting USB port " + port + " (" + why + "): another camera is in it now");
            return;
        }
        boolean gentle = devicePresent && stuckWatchdog.usbResetsSinceFrame() <= 1;
        if (!gentle || !usbReset(why, true)) {
            portPowerCycle(port, gentle ? "the USB reset failed" : why);
        }
        reopenCamera();
    }

    // usbdevfs from Python: Java can't issue the ioctl, and python3 is always on the Jetson
    // (PhotonVision
    // runs as root). ClearPortFeature(PORT_POWER), 0.5 s, SetPortFeature(PORT_POWER) on the hub port.
    private static final String PORT_POWER_SCRIPT =
            String.join(
                    "\n",
                    "import fcntl, os, struct, sys, time",
                    "hub, port = sys.argv[1], int(sys.argv[2])",
                    "d = '/sys/bus/usb/devices/' + hub",
                    "bus, dev = (int(open(d + '/' + f).read()) for f in ('busnum', 'devnum'))",
                    "fd = os.open('/dev/bus/usb/%03d/%03d' % (bus, dev), os.O_RDWR)",
                    "def feature(req):",
                    "    ctl = struct.pack('=BBHHHI4xQ', 0x23, req, 8, port, 0, 1000, 0)",
                    "    fcntl.ioctl(fd, 0xC0185500, bytearray(ctl))  # USBDEVFS_CONTROL, 64-bit",
                    "try:",
                    "    feature(1)",
                    "    time.sleep(0.1)",
                    "    feature(3)",
                    "finally:",
                    "    os.close(fd)");

    /** photonvision-84: switches the camera's hub port's power off and on (port e.g. "1-2.3"). */
    private void portPowerCycle(String port, String why) {
        String hub;
        String num;
        int dot = port.lastIndexOf('.');
        if (dot >= 0) { // on a hub: 1-2.3 is port 3 of hub 1-2
            hub = port.substring(0, dot);
            num = port.substring(dot + 1);
        } else { // on the root hub: 1-2 is port 2 of usb1
            int dash = port.indexOf('-');
            hub = "usb" + port.substring(0, dash);
            num = port.substring(dash + 1);
        }
        logger.error("Switching USB port " + port + "'s power off and on: " + why);
        try {
            Process p =
                    new ProcessBuilder("python3", "-c", PORT_POWER_SCRIPT, hub, num)
                            .redirectErrorStream(true)
                            .start();
            if (!p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                p.destroyForcibly();
                logger.error("USB port power toggle timed out");
            } else if (p.exitValue() != 0) {
                logger.error(
                        "USB port power toggle failed: "
                                + new String(p.getInputStream().readAllBytes()).strip());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.error("USB port power toggle failed: " + e);
        }
    }

    /**
     * photonvision-84: once the camera's device node is back (up to 5 s) and it has had {@link
     * #SETTLE_MS} to start, makes cscore close whatever it holds and open it again (cscore's SetPath
     * does that even with the same path). Found on the robot 2026-10-29: the camera answers USB at
     * once after a power toggle, but its video controls fail (-71) for about a second; cscore,
     * reopened in that second, couldn't set the format and stayed stuck ("could not queue buffer 0")
     * through every later toggle. Reopening only if no frame had come 1.2 s later was tried: cscore
     * rarely came back by itself first, so it was 0.2-0.4 s slower.
     */
    private static final long SETTLE_MS = 300;

    private void reopenCamera() {
        String path = camera.getPath();
        long deadline = System.nanoTime() + 5_000_000_000L;
        try {
            while (!java.nio.file.Files.exists(java.nio.file.Path.of(path))
                    && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            Thread.sleep(SETTLE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        // UsbCamera has no setPath in this WPILib; this is the call it would make.
        org.wpilib.vision.camera.CameraServerJNI.setUsbCameraPath(camera.getHandle(), path);
    }

    private CapturedFrame grabInputMat() {
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
                if (ASYNC_DECODE) return takeDecoded(mode.width, mode.height);
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
                logGrabError(String.valueOf(error));
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
                logGrabError(String.valueOf(error));

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
                logGrabError(String.valueOf(sink.getError()));
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
                logGrabError(String.valueOf(sink.getError()));
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
        reconnectCamera(
                "The camera is sending frames that aren't "
                        + width
                        + "x"
                        + height
                        + ", the video mode PhotonVision set; applying the mode again (attempt "
                        + attempt
                        + ")");
    }

    /**
     * Closes and reopens the camera in cscore (photonvision-27, -29). Shared by the video-mode fix
     * and the stuck-camera watchdog, which share one throttle. SpectrumJetson (photonvision-43): it
     * really reopens the device. FORCE_CLOSE only stops and restarts streaming in cscore's Linux
     * camera: the device stays open, nothing is re-negotiated, and the format isn't sent again.
     */
    private void reconnectCamera(String why) {
        stuckWatchdog.noteReconnect();
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

    // Spectrum: decode in its own thread, overlapped with the pipeline. The camera loop was serial
    // (grab + decode 3.2 ms, then pipeline ~4.8 ms): ~8.3 ms a frame, the whole 120 fps period, so
    // a finished frame waited 2.4 ms on average (up to 7) before the loop came back for it, and
    // the decode sat in every frame's latency. Now a decoder thread grabs and decodes each frame
    // as it arrives into a one-frame slot (a newer frame replaces an unclaimed one), and the loop
    // takes the newest. SPECTRUM_ASYNC_DECODE=0 goes back to decoding in the loop.
    private static final boolean ASYNC_DECODE =
            !"0".equals(System.getenv("SPECTRUM_ASYNC_DECODE"));

    private final Object decodedLock = new Object();
    private CapturedFrame decoded = null; // the newest decoded frame not yet taken
    private Thread decoder = null;
    private volatile int decodeWidth, decodeHeight;
    private volatile long lastTakenNs = 0;
    private volatile boolean loggedAsync = false;

    private CapturedFrame takeDecoded(int width, int height) {
        decodeWidth = width;
        decodeHeight = height;
        lastTakenNs = System.nanoTime();
        synchronized (decodedLock) {
            if (decoder == null || !decoder.isAlive()) {
                decoder = new Thread(this::decodeLoop, "SpectrumDecoder - " + camera.getName());
                decoder.setDaemon(true);
                decoder.start();
            }
            long deadline = System.nanoTime() + (long) (CSCORE_DEFAULT_FRAME_TIMEOUT * 1e9);
            while (decoded == null) {
                long left = deadline - System.nanoTime();
                if (left <= 0) return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
                try {
                    decodedLock.wait(Math.max(1, left / 1_000_000));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return new CapturedFrame(new CVMat(), settables.getFrameStaticProperties(), 0);
                }
            }
            CapturedFrame f = decoded;
            decoded = null;
            return f;
        }
    }

    // Runs while the camera loop keeps taking frames on the direct-gray path; stops itself 1 s after
    // the loop stops asking (another pipeline type, a disabled camera, release()).
    private void decodeLoop() {
        if (!loggedAsync) {
            loggedAsync = true;
            logger.info("Decoding in its own thread, overlapped with the pipeline (SPECTRUM_ASYNC_DECODE)");
        }
        while (!Thread.currentThread().isInterrupted()
                && System.nanoTime() - lastTakenNs < 1_000_000_000L
                && graySink != null
                && useGray) {
            if (!waitForTakeWindow()) break;
            CapturedFrame f = grabMjpegAsGray(decodeWidth, decodeHeight, lastTime);
            if (f.captureTimestamp == 0 || f.colorImage.getMat().empty()) {
                f.colorImage.release();
                continue; // timed out or dropped; grabMjpegAsGray logged and counted it
            }
            synchronized (decodedLock) {
                if (decoded != null) decoded.colorImage.release(); // the loop didn't want it
                decoded = f;
                decodedLock.notifyAll();
            }
        }
        synchronized (decodedLock) {
            if (decoded != null) {
                decoded.colorImage.release();
                decoded = null;
            }
            decoder = null;
        }
    }

    // SpectrumJetson (photonvision-49, 57): while IdleMode limits the frame rate (a disabled robot,
    // the thermal limit), the camera loop takes a frame only every period; decoding every frame in
    // between would keep most of the decode's power and heat. So the decoder waits until a period
    // after the last take, less about a frame's grab and decode, and decodes only around the frames
    // the loop will take. False if interrupted.
    private static final long DECODE_LEAD_NS = 12_000_000L;

    private boolean waitForTakeWindow() {
        while (true) {
            long period = IdleMode.periodNanos();
            if (period == 0) return true;
            long waitNanos = lastTakenNs + period - DECODE_LEAD_NS - System.nanoTime();
            if (waitNanos <= 0) return true;
            try {
                Thread.sleep(Math.max(1, Math.min(5, waitNanos / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }

    @Override
    public void release() {
        synchronized (decodedLock) {
            if (decoder != null) decoder.interrupt();
        }
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
