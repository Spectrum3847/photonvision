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

import java.util.ArrayList;
import java.util.List;
import org.opencv.core.Size;
import org.photonvision.common.configuration.CameraConfiguration;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.vision.calibration.CameraCalibrationCoefficients;
import org.photonvision.vision.frame.FrameStaticProperties;
import org.wpilib.vision.camera.VideoMode;

public abstract class VisionSourceSettables {
    protected Logger logger;

    private final CameraConfiguration configuration;

    protected VisionSourceSettables(CameraConfiguration configuration) {
        this.configuration = configuration;
        this.logger =
                new Logger(VisionSourceSettables.class, configuration.nickname, LogGroup.VisionModule);
    }

    protected FrameStaticProperties frameStaticProperties = null;
    protected List<VideoMode> videoModes = new ArrayList<>();

    public CameraConfiguration getConfiguration() {
        return configuration;
    }

    // If the device has been connected at least once, and we cached properties
    protected boolean cameraPropertiesCached = false;

    /**
     * Runs exactly once the first time that the underlying device goes from disconnected to connected
     */
    public void onCameraConnected() {
        cameraPropertiesCached = true;
    }

    public abstract void setExposureRaw(double exposureRaw);

    /**
     * SpectrumJetson (photonvision-13): the camera's current exposure time in microseconds, or -1
     * if unknown (auto exposure, or a camera type that doesn't report it). Used to move frame
     * timestamps to mid-exposure.
     */
    public double getExposureTimeUs() {
        return -1;
    }

    public abstract double getMinExposureRaw();

    public abstract double getMaxExposureRaw();

    public abstract void setAutoExposure(boolean cameraAutoExposure);

    public abstract void setWhiteBalanceTemp(double temp);

    public abstract void setAutoWhiteBalance(boolean autowb);

    public abstract void setBrightness(int brightness);

    public abstract void setGain(int gain);

    /**
     * SpectrumJetson (photonvision-54): whether the camera has this control. True when it can't
     * tell (not connected yet, or not a USB camera), so callers keep what the camera's quirks say.
     */
    public boolean hasControl(String name) {
        return true;
    }

    // Pretty uncommon so instead of abstract this is just a no-op by default
    // Overridden by cameras with AWB gain support
    public void setRedGain(int red) {}

    public void setBlueGain(int blue) {}

    public abstract VideoMode getCurrentVideoMode();

    public void setVideoModeInternal(int index) {
        if (!getAllVideoModes().isEmpty()) setVideoMode(getAllVideoModes().get(index));
    }

    public void setVideoMode(VideoMode mode) {
        logger.info(
                "Setting video mode to "
                        + "FPS: "
                        + mode.fps
                        + " Width: "
                        + mode.width
                        + " Height: "
                        + mode.height
                        + " Pixel Format: "
                        + mode.pixelFormat);
        setVideoModeInternal(mode);
        calculateFrameStaticProps();
    }

    protected abstract void setVideoModeInternal(VideoMode videoMode);

    public void setVideoModeIndex(int index) {
        setVideoMode(videoModes.get(index));
    }

    public abstract List<VideoMode> getAllVideoModes();

    /**
     * SpectrumJetson (photonvision-24): the camera's controls as actually set (raw values), for the
     * settings snapshot in the robot log. Empty for cameras that don't have them.
     */
    public java.util.Map<String, Long> getRawControls() {
        return java.util.Map.of();
    }

    // SpectrumJetson (photonvision-28): camera controls the stock UI doesn't show. A value below 0
    // restores the camera's default. No-ops for cameras without them.
    public void setContrast(int value) {}

    public void setGamma(int value) {}

    public void setSharpness(int value) {}

    public void setBacklightCompensation(int value) {}

    /**
     * SpectrumJetson (photonvision-28): the extra controls this camera has, for the Input tab: each
     * {key (the pipeline setting), label, min, max, step, default, value}.
     */
    public List<java.util.Map<String, Object>> getExtraControls() {
        return List.of();
    }

    public double getFOV() {
        return configuration.FOV;
    }

    public void setFOV(double fov) {
        logger.info("Setting FOV to " + fov);
        configuration.FOV = fov;
        calculateFrameStaticProps();
    }

    public void addCalibration(CameraCalibrationCoefficients calibrationCoefficients) {
        configuration.addCalibration(calibrationCoefficients);
        calculateFrameStaticProps();
    }

    public void removeCalibration(Size resolution) {
        configuration.removeCalibration(resolution);
        calculateFrameStaticProps();
    }

    protected void calculateFrameStaticProps() {
        var videoMode = getCurrentVideoMode();
        this.frameStaticProperties =
                new FrameStaticProperties(
                        videoMode,
                        getFOV(),
                        configuration.calibrations.stream()
                                .filter(
                                        it ->
                                                it.resolution.width == videoMode.width
                                                        && it.resolution.height == videoMode.height)
                                .findFirst()
                                .orElse(null));
    }

    public FrameStaticProperties getFrameStaticProperties() {
        return frameStaticProperties;
    }

    public abstract double getMinWhiteBalanceTemp();

    public abstract double getMaxWhiteBalanceTemp();
}
