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

package org.photonvision.vision.camera.USBCameras;

import java.util.Locale;
import org.photonvision.common.configuration.CameraConfiguration;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.jni.NapaDetectorJNI;
import org.photonvision.vision.camera.PVCameraInfo.PVUsbCameraInfo;
import org.photonvision.vision.camera.QuirkyCamera;
import org.photonvision.vision.frame.FrameProvider;
import org.photonvision.vision.frame.provider.NativeFrameProvider;
import org.photonvision.vision.processes.VisionSource;
import org.photonvision.vision.processes.VisionSourceSettables;
import org.wpilib.util.PixelFormat;

/**
 * Spectrum (sc/mjpeg-napa) native camera backend (SpectrumJetson
 * docs/SYSTEMCORE-NATIVE-BACKEND.md): a USB camera with no cscore UsbCamera. Chosen per camera by
 * {@link #createOrNull}: verified cameras (Thriftiest Cam 1bcf:28c5, Swyft 0bda:3035, ThriftyCam
 * 05c8:0a00 (USB 3, 1600x1304 at 60 fps, no serial: known by its USB port); more with
 * SPECTRUM_NATIVE_CAMERA_IDS) with MJPEG modes, when the library is there. SPECTRUM_NATIVE_CAMERA=0
 * turns it off. Anything else, or any failure here, and the camera is a normal USBCameraSource.
 */
public class NativeCameraSource extends VisionSource {
    private static final Logger staticLogger = new Logger(NativeCameraSource.class, LogGroup.Camera);

    private final Logger logger;
    private final String dev;
    private NativeCameraSettables settables;
    private NativeFrameProvider provider;

    /** "vid:pid" (hex) pairs the native backend takes. */
    private static final String IDS =
            System.getenv().getOrDefault("SPECTRUM_NATIVE_CAMERA_IDS", "1bcf:28c5,0bda:3035,05c8:0a00");

    static boolean verified(int vid, int pid) {
        String want = String.format(Locale.ROOT, "%04x:%04x", vid, pid);
        for (String s : IDS.split(",")) if (s.trim().equalsIgnoreCase(want)) return true;
        return false;
    }

    /** A native source for this camera, or null (it gets a USBCameraSource). */
    public static VisionSource createOrNull(CameraConfiguration config) {
        if ("0".equals(System.getenv("SPECTRUM_NATIVE_CAMERA"))) return null;
        if (!(config.matchedCameraInfo instanceof PVUsbCameraInfo usb)) return null;
        if (!verified(usb.vendorId, usb.productId)) return null;
        String dev = config.getDevicePath();
        try {
            if (!NapaDetectorJNI.isAvailable()) {
                staticLogger.warn(
                        "Native camera: library not loaded (" + NapaDetectorJNI.loadError() + "); cscore");
                return null;
            }
            var modes = NativeCameraSettables.readModes(dev);
            if (modes == null || modes.stream().noneMatch(m -> m.pixelFormat == PixelFormat.MJPEG)) {
                staticLogger.warn("Native camera: " + dev + " has no readable MJPEG modes; cscore");
                return null;
            }
            return new NativeCameraSource(config, dev);
        } catch (Throwable e) {
            staticLogger.error("Native camera: " + dev + " failed (" + e + "); cscore", e);
            return null;
        }
    }

    private NativeCameraSource(CameraConfiguration config, String dev) {
        super(config);
        this.dev = dev;
        this.logger = new Logger(NativeCameraSource.class, config.nickname, LogGroup.Camera);
        var usb = (PVUsbCameraInfo) config.matchedCameraInfo;
        if (getCameraConfiguration().cameraQuirks == null)
            getCameraConfiguration().cameraQuirks =
                    QuirkyCamera.getQuirkyCamera(
                            usb.vendorId, usb.productId, config.matchedCameraInfo.name());
        settables = new NativeCameraSettables(config, dev);
        provider = new NativeFrameProvider(dev, config.nickname, settables);
        settables.setModeListener(provider::onVideoModeChanged);
        logger.info("Native camera source for " + dev + " (no cscore UsbCamera)");
    }

    public String devicePath() {
        return dev;
    }

    @Override
    public FrameProvider getFrameProvider() {
        return provider;
    }

    @Override
    public VisionSourceSettables getSettables() {
        return settables;
    }

    @Override
    public boolean isVendorCamera() {
        return false;
    }

    @Override
    public boolean hasLEDs() {
        return false;
    }

    @Override
    public void remakeSettables() {
        var old = settables.getCurrentVideoMode();
        settables = new NativeCameraSettables(getCameraConfiguration(), dev);
        settables.setModeListener(provider::onVideoModeChanged);
        settables.setVideoMode(old);
        provider.updateSettables(settables);
    }

    @Override
    public void release() {
        if (provider != null) provider.release();
        provider = null;
    }
}
