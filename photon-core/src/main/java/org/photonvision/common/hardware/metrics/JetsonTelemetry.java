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

package org.photonvision.common.hardware.metrics;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.TimedTaskManager;
import org.photonvision.jni.JetsonStatusJNI;
import org.wpilib.networktables.BooleanPublisher;
import org.wpilib.networktables.DoublePublisher;
import org.wpilib.networktables.IntegerPublisher;
import org.wpilib.networktables.NetworkTable;
import org.wpilib.networktables.StringPublisher;

/**
 * SpectrumJetson (photonvision-16): the Jetson's own health on NetworkTables, once a second, for
 * robot code to log and raise alerts on. PhotonVision's built-in metrics (/photonvision//metrics/
 * <host>) already have CPU temperature and use, RAM, disk and uptime. This adds what the Jetson
 * exposes beyond that, under /photonvision/jetson/:
 *
 * <ul>
 *   <li>{@code gpuLoadPct}; {@code cpuTempC}, {@code gpuTempC}, {@code socTempC} (hottest of the
 *       three SoC sensors), {@code tjTempC} (hottest on the chip); {@code fanRpm} where there's a
 *       tachometer, {@code fanPwmPct} (the fan's drive, 0-100)
 *   <li>{@code powerW} (the board's input), {@code cpuGpuPowerW}, {@code socPowerW}
 *   <li>{@code jpegDecoder} ("nvjpg" or "libjpeg-turbo"), {@code jpegHardwareOff} (the hardware
 *       decoder was switched off after a failed check), {@code jpegChecksOk}, {@code
 *       jpegChecksDiffer} (counts since PhotonVision started)
 *   <li>{@code throttle}: why the Jetson is slowing itself down, e.g. "None", "OVER-CURRENT",
 *       "HIGH TEMP (cpu)" (JetsonThrottle, photonvision-20); {@code overCurrentEvents} since boot
 *       where the Jetson counts them
 *   <li>{@code heartbeat}: goes up by one every publish
 * </ul>
 *
 * Anything the hardware (or the detector library) doesn't provide isn't published. Paths: see
 * {@link JetsonSysfs} and {@link #discover()} for which are verified on JetPack 7 (r39).
 */
public final class JetsonTelemetry {
    private static final Logger logger = new Logger(JetsonTelemetry.class, LogGroup.General);
    private static JetsonTelemetry instance;

    /** Starts the once-a-second publisher (idempotent). */
    public static synchronized void start() {
        if (instance == null) instance = new JetsonTelemetry();
    }

    private final Path gpuLoad = JetsonSysfs.gpuLoad();
    private final NetworkTable table;
    private final Map<String, DoublePublisher> doubles = new HashMap<>();
    // thermal zone name without "-thermal" -> temp file
    private final Map<String, Path> temps = new LinkedHashMap<>();
    private final Map<String, Path[]> rails = new LinkedHashMap<>(); // label -> {mV, mA} files
    private Path fanRpm = null, fanPwm = null;
    private IntegerPublisher heartbeat, jpegChecksOk, jpegChecksDiffer, overCurrentEvents;
    private StringPublisher jpegDecoder, throttle;
    private BooleanPublisher jpegHardwareOff;
    private boolean jpegStatusAvailable = true;
    private long beats = 0;

    private JetsonTelemetry() {
        table =
                NetworkTablesManager.getInstance()
                        .getNTInst()
                        .getTable("photonvision")
                        .getSubTable("jetson");
        discover();
        heartbeat = table.getIntegerTopic("heartbeat").publish();
        logger.info(
                "Jetson telemetry on /photonvision/jetson: temperatures "
                        + temps.keySet()
                        + ", power rails "
                        + rails.keySet()
                        + (fanRpm != null ? ", fan rpm" : "")
                        + (fanPwm != null ? ", fan pwm" : "")
                        + (gpuLoad != null ? ", GPU load (" + gpuLoad + ")" : ", no GPU load"));
        TimedTaskManager.getInstance().addTask("JetsonTelemetry", this::tick, 1000, 1000);
    }

    /**
     * Finds the sensors.
     *
     * <ul>
     *   <li>Thermal zones (/sys/class/thermal/thermal_zone*): r39-verified types cpu-thermal,
     *       gpu-thermal, tj-thermal, plus soc0..2 and cv0..2 (matched with or without "-thermal").
     *   <li>Fan tachometer: hwmon "pwm_tach" {@code rpm} (r36), else any hwmon {@code fan1_input}
     *       (guessed for r39). Fan PWM: r39-verified pwm-fan hwmon {@code pwm1}.
     *   <li>Power: hwmon "ina3221" {@code in<N>_label}/{@code in<N>_input}/{@code curr<N>_input} with
     *       labels VDD_IN, VDD_CPU_GPU_CV, VDD_SOC (r36; not yet checked on r39).
     * </ul>
     */
    private void discover() {
        for (Path z : JetsonSysfs.children(Path.of("/sys/class/thermal"), "thermal_zone*")) {
            String type = JetsonSysfs.read(z.resolve("type"));
            // Zones with no sensor behind them (cv0..cv2 on r36) fail to read; skip those.
            if (type != null && JetsonSysfs.read(z.resolve("temp")) != null) {
                temps.putIfAbsent(JetsonSysfs.zoneName(type), z.resolve("temp"));
            }
        }
        for (Path h : JetsonSysfs.children(Path.of("/sys/class/hwmon"), "hwmon*")) {
            String name = JetsonSysfs.read(h.resolve("name"));
            if ("pwm_tach".equals(name) && Files.isReadable(h.resolve("rpm"))) fanRpm = h.resolve("rpm");
            if (fanRpm == null && Files.isReadable(h.resolve("fan1_input"))) {
                fanRpm = h.resolve("fan1_input");
            }
            if ("ina3221".equals(name)) {
                for (int i = 1; i <= 3; i++) {
                    String label = JetsonSysfs.read(h.resolve("in" + i + "_label"));
                    Path mv = h.resolve("in" + i + "_input"), ma = h.resolve("curr" + i + "_input");
                    if (label != null && Files.isReadable(mv) && Files.isReadable(ma)) {
                        rails.put(label, new Path[] {mv, ma});
                    }
                }
            }
        }
        fanPwm = JetsonSysfs.fanPwm();
    }

    private void tick() {
        try {
            Double gpu = JetsonSysfs.number(gpuLoad);
            if (gpu != null) put("gpuLoadPct", gpu / 10.0); // per mille

            put("cpuTempC", celsius("cpu"));
            put("gpuTempC", celsius("gpu"));
            put("tjTempC", celsius("tj"));
            Double soc = null;
            for (String z : new String[] {"soc0", "soc1", "soc2"}) {
                Double t = celsius(z);
                if (t != null && (soc == null || t > soc)) soc = t;
            }
            put("socTempC", soc);

            if (fanRpm != null) put("fanRpm", JetsonSysfs.number(fanRpm));
            Double pwm = JetsonSysfs.number(fanPwm);
            if (pwm != null) put("fanPwmPct", pwm * 100.0 / 255.0);

            put("powerW", railW("VDD_IN"));
            put("cpuGpuPowerW", railW("VDD_CPU_GPU_CV"));
            put("socPowerW", railW("VDD_SOC"));

            publishJpegStatus();
            publishThrottle();
            heartbeat.set(++beats);
        } catch (RuntimeException e) {
            logger.error("Jetson telemetry failed", e);
        }
    }

    private void publishJpegStatus() {
        if (!jpegStatusAvailable) return;
        long[] s = JetsonStatusJNI.jpegStatus();
        if (s == null || s.length < 4) {
            jpegStatusAvailable = false; // no detector library, or one without jpegStatus
            return;
        }
        if (jpegDecoder == null) {
            jpegDecoder = table.getStringTopic("jpegDecoder").publish();
            jpegHardwareOff = table.getBooleanTopic("jpegHardwareOff").publish();
            jpegChecksOk = table.getIntegerTopic("jpegChecksOk").publish();
            jpegChecksDiffer = table.getIntegerTopic("jpegChecksDiffer").publish();
        }
        jpegDecoder.set(s[0] == 1 ? "nvjpg" : "libjpeg-turbo");
        jpegHardwareOff.set(s[1] != 0);
        jpegChecksOk.set(s[2]);
        jpegChecksDiffer.set(s[3]);
    }

    // SpectrumJetson (photonvision-20)
    private void publishThrottle() {
        if (!JetsonThrottle.available()) return; // not a Jetson, or none of its throttle files
        if (throttle == null) throttle = table.getStringTopic("throttle").publish();
        throttle.set(JetsonThrottle.reason());
        long oc = JetsonThrottle.overCurrentEvents();
        if (oc < 0) return; // no over-current counters (r39: check soctherm_oc)
        if (overCurrentEvents == null) {
            overCurrentEvents = table.getIntegerTopic("overCurrentEvents").publish();
        }
        overCurrentEvents.set(oc);
    }

    private void put(String key, Double value) {
        if (value == null) return;
        doubles.computeIfAbsent(key, k -> table.getDoubleTopic(k).publish()).set(value);
    }

    private Double celsius(String zone) {
        Double v = JetsonSysfs.number(temps.get(zone));
        return v == null ? null : v / 1000.0;
    }

    private Double railW(String label) {
        Path[] f = rails.get(label);
        if (f == null) return null;
        Double mv = JetsonSysfs.number(f[0]), ma = JetsonSysfs.number(f[1]);
        return mv == null || ma == null ? null : mv * ma / 1e6;
    }
}
