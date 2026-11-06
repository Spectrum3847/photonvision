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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.photonvision.common.configuration.CameraConfiguration;
import org.photonvision.jni.NapaDetectorJNI;
import org.photonvision.vision.camera.CameraQuirk;
import org.photonvision.vision.processes.VisionSourceSettables;
import org.wpilib.util.PixelFormat;
import org.wpilib.vision.camera.VideoMode;

/**
 * Spectrum (sc/mjpeg-napa) native camera backend (SpectrumJetson
 * docs/SYSTEMCORE-NATIVE-BACKEND.md): GenericUSBCameraSettables' behaviour for a camera that has no
 * cscore UsbCamera. Controls and video modes come from the driver through NapaDetectorJNI.device*
 * (V4L2 on a control-only fd, so it works whether the native capture thread or, during calibration,
 * a temporary UsbCamera streams). Names, units and the video-mode list (and so the saved video-mode
 * indices) are cscore's: controls named as cscore normalizes V4L2 names, cscore's 0-100 percentage
 * properties for brightness/contrast/saturation/hue/sharpness/gain/exposure with the camera's own
 * values as raw_*, modes in the driver's order then PhotonVision's sort. Every write is read first
 * and skipped when equal (the Swyft stores controls in flash).
 */
public class NativeCameraSettables extends VisionSourceSettables {
    /** One V4L2 control, as cscore names it. */
    static final class Ctl {
        final int id, type, min, max, step, def;
        final String name;
        volatile int value;

        Ctl(String row) {
            String[] f = row.split(",", 9);
            id = (int) Long.parseLong(f[0]);
            type = Integer.parseInt(f[1]);
            min = Integer.parseInt(f[2]);
            max = Integer.parseInt(f[3]);
            step = Integer.parseInt(f[4]);
            def = Integer.parseInt(f[5]);
            value = Integer.parseInt(f[6]);
            name = normalize(f[8]);
        }
    }

    /** cscore's UsbCameraProperty name normalization: lower case, words joined by '_'. */
    static String normalize(String v4l2Name) {
        var b = new StringBuilder();
        boolean newWord = false;
        for (char c : v4l2Name.toCharArray()) {
            if (Character.isLetterOrDigit(c) && c < 128) {
                if (newWord) b.append('_');
                newWord = false;
                b.append(Character.toLowerCase(c));
            } else if (b.length() > 0) {
                newWord = true;
            }
        }
        return b.toString();
    }

    private static boolean isPercentage(String name) {
        return switch (name) {
            case "brightness",
                    "contrast",
                    "saturation",
                    "hue",
                    "sharpness",
                    "gain",
                    "exposure_absolute",
                    "exposure_time_absolute" ->
                    true;
            default -> false;
        };
    }

    /** A cscore-style property: raw (the control itself) or cscore's 0-100 percentage of it. */
    final class Prop {
        final Ctl ctl;
        final boolean percent;
        final String name;

        Prop(Ctl ctl, boolean percent, String name) {
            this.ctl = ctl;
            this.percent = percent;
            this.name = name;
        }

        int getMin() {
            return percent ? 0 : ctl.min;
        }

        int getMax() {
            return percent ? 100 : ctl.max;
        }

        int getStep() {
            return percent ? 1 : ctl.step;
        }

        int getDefault() {
            return percent ? toPercent(ctl.def) : ctl.def;
        }

        int get() {
            int v = NapaDetectorJNI.deviceGetControl(dev, ctl.id);
            if (v != Integer.MIN_VALUE) ctl.value = v;
            return percent ? toPercent(ctl.value) : ctl.value;
        }

        /** Read first, written only when different; logs a value that doesn't read back. */
        void set(int v) {
            int raw = percent ? toRaw(v) : v;
            int r = NapaDetectorJNI.deviceSetControl(dev, ctl.id, raw);
            if (r < 0) {
                logger.warn("Camera control " + name + " on " + dev + ": set " + raw + " failed");
                return;
            }
            int back = NapaDetectorJNI.deviceGetControl(dev, ctl.id);
            if (back != Integer.MIN_VALUE) ctl.value = back;
            if (r == 1 && back != raw)
                logger.warn("Camera control " + name + " on " + dev + ": set " + raw + ", reads " + back);
        }

        // cscore's conversions (UsbCameraProperty)
        private int toPercent(int raw) {
            return ctl.max == ctl.min ? 0 : (int) (100.0 * (raw - ctl.min) / (ctl.max - ctl.min));
        }

        private int toRaw(int pct) {
            return (int) (ctl.min + (ctl.max - ctl.min) * (pct / 100.0));
        }
    }

    protected static final int PROP_AUTO_EXPOSURE_ENABLED = 3;
    protected static final int PROP_AUTO_EXPOSURE_DISABLED = 1;

    private final String dev;
    private final CameraConfiguration configuration;
    private final Map<String, Ctl> controls = new LinkedHashMap<>();
    private VideoMode currentMode;
    // the frame provider follows video-mode changes (reopens the camera in the new mode)
    private volatile Consumer<VideoMode> modeListener = m -> {};

    protected double lastExposureRaw = -1;
    protected volatile boolean autoExposureOn = false;
    protected double minExposure = 1, maxExposure = 80000;
    protected double minWhiteBalanceTemp = 1, maxWhiteBalanceTemp = 4000;
    protected int lastWhiteBalanceTemp = 4000;
    private Prop exposureAbsProp, autoExposureProp, wbTempProp;

    /** The device's modes, cscore's list order, or null when it can't be read. */
    static List<VideoMode> readModes(String dev) {
        int[] m = NapaDetectorJNI.deviceModes(dev);
        if (m == null) return null;
        var out = new ArrayList<VideoMode>();
        for (int i = 0; i + 3 < m.length; i += 4)
            out.add(new VideoMode(pixelFormat(m[i]), m[i + 1], m[i + 2], m[i + 3]));
        return out;
    }

    /** V4L2 fourcc -> cscore's pixel format (UsbCameraImpl's ToPixelFormat). */
    static PixelFormat pixelFormat(int fourcc) {
        String s =
                new String(
                        new char[] {
                            (char) (fourcc & 0xff),
                            (char) ((fourcc >> 8) & 0xff),
                            (char) ((fourcc >> 16) & 0xff),
                            (char) ((fourcc >> 24) & 0xff)
                        });
        return switch (s) {
            case "MJPG" -> PixelFormat.MJPEG;
            case "YUYV" -> PixelFormat.YUYV;
            case "RGBP" -> PixelFormat.RGB565;
            case "BGR3" -> PixelFormat.BGR;
            case "GREY" -> PixelFormat.GRAY;
            case "Y16 " -> PixelFormat.Y16;
            case "UYVY" -> PixelFormat.UYVY;
            default -> PixelFormat.UNKNOWN;
        };
    }

    public NativeCameraSettables(CameraConfiguration configuration, String dev) {
        super(configuration);
        this.configuration = configuration;
        this.dev = dev;
        onCameraConnected(); // the device is there (NativeCameraSource checked): modes and controls now
        if (!configuration.cameraQuirks.hasQuirk(CameraQuirk.StickyFPS) && !videoModes.isEmpty())
            setVideoMode(videoModes.get(0));
    }

    public String devicePath() {
        return dev;
    }

    void setModeListener(Consumer<VideoMode> l) {
        modeListener = l;
    }

    private Prop findProp(String name) {
        boolean raw = name.startsWith("raw_");
        String base = raw ? name.substring(4) : name;
        Ctl c = controls.get(base);
        if (c == null) return null;
        if (raw) return isPercentage(base) ? new Prop(c, false, name) : null;
        return new Prop(c, isPercentage(base), name);
    }

    private Prop findProperty(String... options) {
        for (String o : options) {
            Prop p = findProp(o);
            if (p != null) return p;
        }
        logger.warn("Expected at least one of " + String.join(", ", options));
        return null;
    }

    private Prop rawProperty(String name) {
        Prop p = findProp("raw_" + name);
        return p != null ? p : findProp(name);
    }

    protected void softSet(String property, int value) {
        Prop p = findProp(property);
        if (p == null) {
            logger.debug("No property " + property + " for " + dev + ", skipping.");
            return;
        }
        p.set(value);
    }

    private void readControls() {
        String[] rows = NapaDetectorJNI.deviceControls(dev);
        if (rows == null) return;
        controls.clear();
        for (String r : rows) {
            try {
                Ctl c = new Ctl(r);
                controls.putIfAbsent(c.name, c);
            } catch (RuntimeException e) {
                logger.debug("Unreadable control " + r);
            }
        }
        var dump = new StringBuilder("Native camera controls:\n");
        for (Ctl c : controls.values())
            dump.append(
                    String.format(
                            "  %s id 0x%08x type %d min %d max %d step %d default %d value %d%n",
                            c.name, c.id, c.type, c.min, c.max, c.step, c.def, c.value));
        logger.debug(dump.toString());
    }

    private void cacheVideoModes() {
        List<VideoMode> list = readModes(dev);
        if (list == null) list = List.of();
        var filtered = new ArrayList<VideoMode>();
        for (VideoMode m : list) {
            if (m.pixelFormat == PixelFormat.UNKNOWN) continue;
            if (configuration.cameraQuirks.hasQuirk(CameraQuirk.FPSCap100) && m.fps > 100) continue;
            if (configuration.cameraQuirks.hasQuirk(CameraQuirk.MJPEGOnly)
                    && m.pixelFormat != PixelFormat.MJPEG) continue;
            filtered.add(m);
        }
        // exactly GenericUSBCameraSettables' order, so saved video-mode indices mean the same mode
        var sorted =
                filtered.stream()
                        .distinct()
                        .sorted(((a, b) -> (b.width + b.height) - (a.width + a.height)))
                        .collect(Collectors.toList());
        Collections.reverse(sorted);
        videoModes = sorted;
        if (videoModes.isEmpty())
            logger.info("Camera " + dev + " has no video modes PhotonVision supports");
    }

    @Override
    public void onCameraConnected() {
        super.onCameraConnected();
        readControls();
        cacheVideoModes();
        // GenericUSBCameraSettables.setUpExposureProperties / setUpWhiteBalanceProperties
        exposureAbsProp =
                findProperty(
                        "raw_exposure_absolute", "raw_exposure_time_absolute", "exposure", "raw_Exposure");
        if (exposureAbsProp != null) {
            minExposure = exposureAbsProp.getMin();
            maxExposure = exposureAbsProp.getMax();
        }
        if (configuration.cameraQuirks.hasQuirk(CameraQuirk.ThriftyOV9281Controls)) {
            minExposure = 1; // ThriftyOV9281CameraSettables: 0.1-240 ms
            maxExposure = 2400;
        }
        autoExposureProp = findProp("exposure_auto");
        if (autoExposureProp == null) autoExposureProp = findProp("auto_exposure");
        wbTempProp = findProp("white_balance_temperature");
        if (wbTempProp != null) {
            minWhiteBalanceTemp = wbTempProp.getMin();
            maxWhiteBalanceTemp = wbTempProp.getMax();
        }
        setAllCamDefaults();
        if (currentMode != null) calculateFrameStaticProps();
    }

    public void setAllCamDefaults() {
        if (configuration.cameraQuirks.hasQuirk(CameraQuirk.ThriftyOV9281Controls))
            softSet("focus_automatic_continuous", 0);
        softSet("image_stabilization", 0);
        softSet("power_line_frequency", 2);
        softSet("scene_mode", 0);
        softSet("exposure_metering_mode", 0);
        softSet("exposure_dynamic_framerate", 0);
        softSet("focus_auto", 0);
        softSet("focus_absolute", 0);
    }

    @Override
    public void setWhiteBalanceTemp(double tempNumber) {
        if (wbTempProp == null) return;
        int temp = (int) Math.round(tempNumber);
        softSet("white_balance_automatic", 0);
        wbTempProp.set((int) Math.clamp(temp, minWhiteBalanceTemp, maxWhiteBalanceTemp));
        lastWhiteBalanceTemp = temp;
    }

    @Override
    public void setAutoWhiteBalance(boolean autoWB) {
        if (autoWB) {
            softSet("white_balance_automatic", 1);
        } else {
            softSet("white_balance_automatic", 0);
            if (wbTempProp != null) wbTempProp.set(lastWhiteBalanceTemp);
        }
    }

    @Override
    public void setAutoExposure(boolean cameraAutoExposure) {
        autoExposureOn = cameraAutoExposure;
        if (!cameraAutoExposure) {
            softSet("auto_exposure_bias", 0);
            softSet("iso_sensitivity_auto", 0);
            softSet("iso_sensitivity", 0);
            if (autoExposureProp != null) autoExposureProp.set(PROP_AUTO_EXPOSURE_DISABLED);
            setExposureRaw(lastExposureRaw);
        } else {
            softSet("auto_exposure_bias", 12);
            softSet("iso_sensitivity_auto", 1);
            softSet("iso_sensitivity", 1);
            if (autoExposureProp != null) autoExposureProp.set(PROP_AUTO_EXPOSURE_ENABLED);
        }
    }

    /** V4L2 exposure_time_absolute is in 100 us units; unknown under auto exposure. */
    @Override
    public double getExposureTimeUs() {
        if (autoExposureOn || lastExposureRaw <= 0) return -1;
        return Math.clamp(lastExposureRaw, minExposure, maxExposure) * 100.0;
    }

    @Override
    public double getMinExposureRaw() {
        return minExposure;
    }

    @Override
    public double getMaxExposureRaw() {
        return maxExposure;
    }

    @Override
    public void setExposureRaw(double exposureRaw) {
        if (exposureRaw < 0.0 || exposureAbsProp == null) return;
        if (autoExposureProp != null) autoExposureProp.set(PROP_AUTO_EXPOSURE_DISABLED);
        exposureAbsProp.set((int) Math.clamp(exposureRaw, minExposure, maxExposure));
        lastExposureRaw = exposureRaw;
    }

    @Override
    public void setBrightness(int brightness) {
        softSet("brightness", brightness);
    }

    @Override
    public void setGain(int gain) {
        softSet("gain_automatic", 0);
        softSet("gain", gain);
    }

    @Override
    public VideoMode getCurrentVideoMode() {
        return currentMode != null
                ? currentMode
                : videoModes.isEmpty()
                        ? new VideoMode(PixelFormat.MJPEG, 1280, 800, 120)
                        : videoModes.get(0);
    }

    @Override
    protected void setVideoModeInternal(VideoMode videoMode) {
        if (videoMode == null) {
            logger.error("Got a null video mode! Doing nothing...");
            return;
        }
        boolean changed = !videoMode.equals(currentMode);
        currentMode = videoMode;
        if (changed) modeListener.accept(videoMode);
    }

    @Override
    public List<VideoMode> getAllVideoModes() {
        return videoModes;
    }

    // photonvision-28's extra controls
    private static final String[][] EXTRA_CONTROLS = {
        {"cameraContrast", "contrast", "Contrast"},
        {"cameraGamma", "gamma", "Gamma"},
        {"cameraSharpness", "sharpness", "Sharpness"},
        {"cameraBacklightCompensation", "backlight_compensation", "Backlight Compensation"}
    };

    private void setRawControl(String name, int value) {
        Prop p = rawProperty(name);
        if (p == null) return;
        p.set(value < 0 ? p.getDefault() : Math.clamp(value, p.getMin(), p.getMax()));
    }

    @Override
    public void setContrast(int value) {
        setRawControl("contrast", value);
    }

    @Override
    public void setGamma(int value) {
        setRawControl("gamma", value);
    }

    @Override
    public void setSharpness(int value) {
        setRawControl("sharpness", value);
    }

    @Override
    public void setBacklightCompensation(int value) {
        setRawControl("backlight_compensation", value);
    }

    @Override
    public List<Map<String, Object>> getExtraControls() {
        var out = new ArrayList<Map<String, Object>>();
        for (String[] c : EXTRA_CONTROLS) {
            Prop p = rawProperty(c[1]);
            if (p == null) continue;
            var m = new LinkedHashMap<String, Object>();
            m.put("key", c[0]);
            m.put("label", c[2]);
            m.put("min", p.getMin());
            m.put("max", p.getMax());
            m.put("step", Math.max(1, p.getStep()));
            m.put("default", p.getDefault());
            m.put("value", p.get());
            out.add(m);
        }
        return out;
    }

    private static final String[] LOGGED_CONTROLS = {
        "exposure_time_absolute",
        "exposure_absolute",
        "auto_exposure",
        "brightness",
        "contrast",
        "gamma",
        "sharpness",
        "saturation",
        "gain",
        "white_balance_temperature",
        "white_balance_automatic",
        "backlight_compensation",
        "power_line_frequency",
        "focus_automatic_continuous"
    };

    @Override
    public Map<String, Long> getRawControls() {
        var m = new LinkedHashMap<String, Long>();
        for (String name : LOGGED_CONTROLS) {
            Prop p = rawProperty(name);
            if (p != null) m.put(name, (long) p.get());
        }
        return m;
    }

    @Override
    public boolean hasControl(String name) {
        return findProp(name) != null || findProp("raw_" + name) != null;
    }

    @Override
    public double getMaxWhiteBalanceTemp() {
        return maxWhiteBalanceTemp;
    }

    @Override
    public double getMinWhiteBalanceTemp() {
        return minWhiteBalanceTemp;
    }
}
