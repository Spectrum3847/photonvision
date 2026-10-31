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

import io.avaje.json.node.JsonDouble;
import io.avaje.json.node.JsonNodeMapper;
import io.avaje.json.node.JsonObject;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.photonvision.common.configuration.PathManager;
import org.photonvision.common.configuration.SettingsSnapshots;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;

/**
 * SpectrumJetson (photonvision-49): while connected to a robot that is disabled, each camera
 * processes at most idleFps frames a second (default 30, or SPECTRUM_IDLE_FPS; switched off or
 * changed from Settings > Robot state, photonvision-50). The frames in between are never grabbed
 * (and, with the decoder thread, never decoded), so neither the JPEG decode nor the detector runs
 * for them: less power and heat between matches, and 30 results a second is still plenty to seed
 * the pose before auto. Full rate again within 5 ms of enable. Off the robot (no NetworkTables
 * connection) nothing changes, so bench tests and tuning see every frame.
 *
 * <p>Also runs a full garbage collection 0.5 s after each enabled-to-disabled transition, so the
 * heap is clean going into the next match instead of collecting during it.
 *
 * <p>The rest of the robot state lives here too, on the same Settings > Robot state card: the event
 * profile (photonvision-51), quiet mode's settings (photonvision-56, {@link QuietMode}), the
 * thermal limit (photonvision-57), the cooling (photonvision-71, {@link FanMode}) and the far-tag
 * search's switch (photonvision-55, kept in {@link FarSearch}).
 */
public final class IdleMode {
    private static final Logger logger = new Logger(IdleMode.class, LogGroup.VisionModule);
    private static volatile boolean listening = false;

    /**
     * Saved in spectrum-robot-state.json (photonvision-50), set from Settings > Robot state or the
     * dashboard's "Full speed" button. SPECTRUM_IDLE_FPS is only the default. A copy: changed through
     * {@link #update}.
     */
    public static final class Settings {
        public boolean idleWhileDisabled = true;
        public double idleFps = readFps();

        /** photonvision-51: switch every camera to eventPipeline when the FMS connects. */
        public boolean eventProfileOnFms = false;

        public int eventPipeline = 0;

        /** photonvision-56: stop writing to the SSD once a match ends... */
        public boolean quietAfterMatch = true;

        /** ...or after this long disabled (0 = never). */
        public int quietAfterDisabledSeconds = 60;

        /**
         * photonvision-90: lower the CPU/GPU clocks after this long disabled or with no robot (0 =
         * never; {@link ClockThrottle}).
         */
        public int throttleClocksAfterDisabledSeconds = 20;
    }

    // On a Jetson, /opt/photonvision/spectrum-robot-state.json is bound from the settings partition
    // (SpectrumJetson 10-data-partition.sh), as in 2026. Elsewhere, PhotonVision's config folder.
    // The 2026 file's farSearch fields are left alone: the far search keeps its own file
    // (FarSearch), off by default.
    private static final Path JETSON_SETTINGS_FILE =
            Path.of("/opt/photonvision/spectrum-robot-state.json");

    private static final JsonNodeMapper json = JsonNodeMapper.builder().build();

    private static Path settingsFile() {
        if (Files.isDirectory(JETSON_SETTINGS_FILE.getParent())) return JETSON_SETTINGS_FILE;
        return PathManager.getInstance().getConfigDir().resolve("spectrum-robot-state.json");
    }

    private static volatile Settings settings = load();
    private static volatile long idlePeriodNanos = period(settings);

    private IdleMode() {}

    private static double readFps() {
        String env = System.getenv("SPECTRUM_IDLE_FPS");
        if (env == null || env.isBlank()) return 30;
        try {
            return Math.max(0, Double.parseDouble(env.trim()));
        } catch (NumberFormatException e) {
            return 30;
        }
    }

    private static Settings load() {
        var s = new Settings();
        Path file = settingsFile();
        if (!Files.exists(file)) return s;
        try {
            JsonObject j = json.fromJsonObject(Files.readString(file));
            s.idleWhileDisabled = j.extract("idleWhileDisabled", s.idleWhileDisabled);
            s.idleFps = j.extract("idleFps", s.idleFps);
            s.eventProfileOnFms = j.extract("eventProfileOnFms", s.eventProfileOnFms);
            s.eventPipeline = j.extract("eventPipeline", s.eventPipeline);
            s.quietAfterMatch = j.extract("quietAfterMatch", s.quietAfterMatch);
            s.quietAfterDisabledSeconds =
                    j.extract("quietAfterDisabledSeconds", s.quietAfterDisabledSeconds);
            s.throttleClocksAfterDisabledSeconds =
                    j.extract("throttleClocksAfterDisabledSeconds", s.throttleClocksAfterDisabledSeconds);
        } catch (IOException | RuntimeException e) {
            logger.error("Couldn't read " + file + "; using the defaults", e);
            return new Settings();
        }
        return s;
    }

    private static void save(Settings s) {
        Path file = settingsFile();
        // The 2026 file may hold fields this one doesn't (farSearch): kept as they were.
        JsonObject j = JsonObject.create();
        try {
            // a copy: parsed avaje nodes are read-only (add() would throw)
            if (Files.exists(file))
                json.fromJsonObject(Files.readString(file)).elements().forEach(j::add);
        } catch (IOException | RuntimeException e) {
            j = JsonObject.create();
        }
        j.add("idleWhileDisabled", s.idleWhileDisabled)
                .add("idleFps", JsonDouble.of(s.idleFps))
                .add("eventProfileOnFms", s.eventProfileOnFms)
                .add("eventPipeline", s.eventPipeline)
                .add("quietAfterMatch", s.quietAfterMatch)
                .add("quietAfterDisabledSeconds", s.quietAfterDisabledSeconds)
                .add("throttleClocksAfterDisabledSeconds", s.throttleClocksAfterDisabledSeconds);
        try {
            Files.writeString(file, json.toJson(j));
        } catch (IOException e) {
            logger.error("Couldn't save " + file, e);
        }
    }

    private static long period(Settings s) {
        return s.idleWhileDisabled && s.idleFps > 0 ? (long) (1e9 / s.idleFps) : 0;
    }

    public static Settings getSettings() {
        return settings;
    }

    /** Changes and saves the settings; null leaves a field as it is. */
    public static synchronized Settings update(
            Boolean idleWhileDisabled,
            Double idleFps,
            Boolean eventProfileOnFms,
            Integer eventPipeline,
            Boolean quietAfterMatch,
            Integer quietAfterDisabledSeconds,
            Integer throttleClocksAfterDisabledSeconds) {
        var old = settings;
        var s = new Settings();
        s.idleWhileDisabled = idleWhileDisabled != null ? idleWhileDisabled : old.idleWhileDisabled;
        s.idleFps = idleFps != null ? Math.max(1, Math.min(120, idleFps)) : old.idleFps;
        s.eventProfileOnFms = eventProfileOnFms != null ? eventProfileOnFms : old.eventProfileOnFms;
        s.eventPipeline = eventPipeline != null ? Math.max(0, eventPipeline) : old.eventPipeline;
        s.quietAfterMatch = quietAfterMatch != null ? quietAfterMatch : old.quietAfterMatch;
        s.quietAfterDisabledSeconds =
                quietAfterDisabledSeconds != null
                        ? Math.max(0, Math.min(3600, quietAfterDisabledSeconds))
                        : old.quietAfterDisabledSeconds;
        // At least 10 s: never in the ~3 s between auto and teleop.
        s.throttleClocksAfterDisabledSeconds =
                throttleClocksAfterDisabledSeconds != null
                        ? (throttleClocksAfterDisabledSeconds <= 0
                                ? 0
                                : Math.max(10, Math.min(3600, throttleClocksAfterDisabledSeconds)))
                        : old.throttleClocksAfterDisabledSeconds;
        settings = s;
        idlePeriodNanos = period(s);
        save(s);
        ClockThrottle.wake(); // photonvision-90: a changed setting applies now
        logger.info(
                s.idleWhileDisabled
                        ? "Idle mode on: " + s.idleFps + " fps per camera while the robot is disabled"
                        : "Idle mode off: full speed while the robot is disabled");
        logger.info(
                s.eventProfileOnFms
                        ? "Event profile: every camera to pipeline "
                                + s.eventPipeline
                                + " when the FMS connects"
                        : "Event profile off");
        return s;
    }

    /** True while frames are limited: idle (a disabled robot), or the thermal limit. */
    public static boolean active() {
        return idleNow() || thermalLimitNow() || ClockThrottle.deepPeriodNanos() > 0;
    }

    /** Idle: a robot is connected and disabled (and idle mode is on). */
    static boolean idleNow() {
        if (idlePeriodNanos == 0) return false;
        var ntm = NetworkTablesManager.getInstance();
        if (!ntm.getNTInst().isConnected()) return false;
        var ds = ntm.getDriverStation();
        // photonvision-62: only on a control word we've actually received. Without one (robot code
        // whose format we can't read) every camera would sit at the idle rate through the match.
        return ds != null && ds.hasControlWord() && !ds.current().m_enabled();
    }

    /**
     * photonvision-57: the thermal limit. On a fanless heatsink (Cooling: No fan, photonvision-71),
     * spectrum-fan-guard creates /run/spectrum-thermal-limit when the chip reaches 95 C and removes
     * it below 88 C. While it's there every camera is capped at THERMAL_FPS, enabled or not: far
     * better than the chip throttling itself at 99 C or shutting down at 104 C. Checked twice a
     * second. Without the file (the SystemCore, a Jetson with a fan) nothing changes.
     */
    private static final Path THERMAL_FLAG = Path.of("/run/spectrum-thermal-limit");

    public static final double THERMAL_FPS = 60;
    private static final long THERMAL_PERIOD_NANOS = (long) (1e9 / THERMAL_FPS);
    private static volatile boolean thermal = false;
    private static volatile long thermalCheckedNanos = System.nanoTime() - 1_000_000_000L;

    public static boolean thermalLimitNow() {
        // photonvision-65: a monotonic clock; the date can jump (RobotClockSync).
        long now = System.nanoTime();
        if (now - thermalCheckedNanos > 500_000_000L) {
            thermalCheckedNanos = now;
            boolean was = thermal;
            thermal = Files.exists(THERMAL_FLAG);
            if (thermal != was) {
                if (thermal) {
                    logger.warn(
                            "Thermal limit: every camera capped at " + THERMAL_FPS + " fps until the chip cools");
                } else {
                    logger.info("Thermal limit lifted: full frame rate");
                }
            }
        }
        return thermal;
    }

    /**
     * The frame period now: the longest of idle's and the thermal limit's that apply, else 0 (no
     * limit).
     */
    public static long periodNanos() {
        long p = idleNow() ? idlePeriodNanos : 0;
        if (thermalLimitNow()) p = Math.max(p, THERMAL_PERIOD_NANOS);
        return Math.max(p, ClockThrottle.deepPeriodNanos()); // photonvision-99: deep idle
    }

    /**
     * Sleeps until {@code lastGrabNanos + period} while limited; returns early (within 5 ms) if the
     * limit ends (the robot is enabled). Returns false if interrupted.
     */
    public static boolean waitForNextFrame(long lastGrabNanos) {
        while (true) {
            long period = periodNanos();
            if (period == 0) break;
            long waitNanos = lastGrabNanos + period - System.nanoTime();
            if (waitNanos <= 0) break;
            try {
                Thread.sleep(Math.max(1, Math.min(5, waitNanos / 1_000_000)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** For Settings > Robot state and the dashboard (photonvision-50). */
    public static Map<String, Object> status() {
        var ntm = NetworkTablesManager.getInstance();
        var ds = ntm.getDriverStation();
        var word = ds == null ? null : ds.current();
        boolean connected = ntm.getNTInst().isConnected();
        var s = settings;
        var m = new LinkedHashMap<String, Object>();
        m.put("idleWhileDisabled", s.idleWhileDisabled);
        m.put("idleFps", s.idleFps);
        m.put("robotConnected", connected);
        m.put("controlWordSeen", ds != null && ds.hasControlWord()); // photonvision-62
        m.put("enabled", connected && word != null && word.m_enabled());
        m.put("autonomous", connected && word != null && word.m_autonomous());
        m.put("fmsAttached", connected && word != null && word.m_fmsAttached());
        m.put("idleNow", idleNow());
        m.put("thermalLimitNow", thermalLimitNow()); // photonvision-57
        m.put("thermalLimitFps", THERMAL_FPS);
        m.put("eventProfileOnFms", s.eventProfileOnFms);
        m.put("eventPipeline", s.eventPipeline);
        m.put("lastEventSwitch", lastEventSwitch);
        m.put("quietAfterMatch", s.quietAfterMatch);
        m.put("quietAfterDisabledSeconds", s.quietAfterDisabledSeconds);
        m.putAll(QuietMode.status());
        m.put("throttleClocksAfterDisabledSeconds", s.throttleClocksAfterDisabledSeconds);
        m.putAll(ClockThrottle.status()); // photonvision-90
        // photonvision-71: the cooling mode.
        m.put("fanAvailable", FanMode.available());
        m.put("fanMode", FanMode.mode());
        m.put("fanError", FanMode.error());
        // photonvision-55: the far-tag search, folded into this card as in 2026.
        m.putAll(FarSearch.toMap());
        return m;
    }

    /**
     * Once NetworkTables is up (Main): the robot-state listeners (the event profile, the GC on
     * disable) and quiet mode.
     */
    public static synchronized void start() {
        if (listening) return;
        var ds = NetworkTablesManager.getInstance().getDriverStation();
        if (ds == null) return;
        listening = true;
        QuietMode.start(); // photonvision-56
        ClockThrottle.start(); // photonvision-90
        logger.info(
                idlePeriodNanos > 0
                        ? "Idle mode: " + settings.idleFps + " fps per camera while the robot is disabled"
                        : "Idle mode off: full speed while the robot is disabled");
        ds.addTransitionListener(
                (old, now) -> {
                    // photonvision-51: once, as the FMS connects; anything after that (the
                    // dashboard, robot code) is left alone.
                    if (!old.m_fmsAttached() && now.m_fmsAttached() && settings.eventProfileOnFms) {
                        switchAllTo(settings.eventPipeline, "the field (FMS) connected");
                    }
                    // photonvision-53: a snapshot of the settings the first time the field connects
                    // each day, off this (NetworkTables) thread.
                    if (!old.m_fmsAttached() && now.m_fmsAttached()) {
                        new Thread(
                                        () -> {
                                            try {
                                                if (!SettingsSnapshots.fieldSnapshotToday()) {
                                                    SettingsSnapshots.create(
                                                            "Field connected " + java.time.LocalDate.now(), "field connected");
                                                }
                                            } catch (Exception e) {
                                                logger.error("Couldn't take the field-connected snapshot", e);
                                            }
                                        },
                                        "SpectrumFieldSnapshot")
                                .start();
                    }
                    if (old.m_enabled() && !now.m_enabled()) {
                        var gc =
                                new Thread(
                                        () -> {
                                            try {
                                                Thread.sleep(500);
                                            } catch (InterruptedException e) {
                                                return;
                                            }
                                            collect();
                                        },
                                        "SpectrumGcWhileDisabled");
                        gc.setDaemon(true);
                        gc.start();
                    }
                });
    }

    private static volatile String lastEventSwitch = "";

    /**
     * photonvision-51: every camera that has pipeline {@code index} to it, skipping cameras in driver
     * mode, calibrating or focusing (as the dashboard's Switch all does). Returns and remembers a
     * one-line summary.
     */
    public static synchronized String switchAllTo(int index, String why) {
        var switched = new ArrayList<String>();
        var skipped = new ArrayList<String>();
        var modules = VisionSourceManager.getInstance().vmm.getModules();
        // The name most cameras have at this number; a camera with another name there is named
        // in the summary, as Switch all on the dashboard does (a profile at mismatched numbers).
        var nameCounts = new HashMap<String, Integer>();
        for (var module : modules) {
            var names = module.pipelineManager.getPipelineNicknames();
            if (names.size() > index) nameCounts.merge(names.get(index), 1, Integer::sum);
        }
        String usual =
                nameCounts.entrySet().stream()
                        .max(Map.Entry.comparingByValue())
                        .map(Map.Entry::getKey)
                        .orElse("");
        for (var module : modules) {
            String name = module.visionSource.getSettables().getConfiguration().nickname;
            int current = module.pipelineManager.getRequestedIndex();
            if (current == -1) {
                skipped.add(name + " (driver mode)");
            } else if (current < -1) {
                skipped.add(name + " (calibrating or focusing)");
            } else if (module.pipelineManager.getPipelineNicknames().size() <= index) {
                skipped.add(name + " (no pipeline " + index + ")");
            } else {
                if (current != index) {
                    module.setPipeline(index);
                    module.saveAndBroadcastAll();
                }
                String theirs = module.pipelineManager.getPipelineNicknames().get(index);
                switched.add(
                        theirs.equals(usual) ? name : name + " (its " + index + " is '" + theirs + "')");
            }
        }
        String summary =
                LocalTime.now().withNano(0)
                        + ": "
                        + why
                        + ", pipeline "
                        + index
                        + ": "
                        + (switched.isEmpty()
                                ? "no camera switched"
                                : "switched " + String.join(", ", switched))
                        + (skipped.isEmpty() ? "" : "; not switched: " + String.join(", ", skipped));
        lastEventSwitch = summary;
        logger.info("Event profile: " + summary);
        return summary;
    }

    private static void collect() {
        var mem = ManagementFactory.getMemoryMXBean();
        long before = mem.getHeapMemoryUsage().getUsed();
        long start = System.nanoTime();
        System.gc();
        long ms = (System.nanoTime() - start) / 1_000_000;
        long after = mem.getHeapMemoryUsage().getUsed();
        logger.info(
                String.format(
                        "Robot disabled: garbage collected in %d ms, heap %d -> %d MB",
                        ms, before >> 20, after >> 20));
    }
}
