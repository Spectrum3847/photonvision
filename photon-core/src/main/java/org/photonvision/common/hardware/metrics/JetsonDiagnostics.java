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

import io.avaje.json.node.JsonNode;
import io.avaje.json.node.JsonNodeMapper;
import io.avaje.json.node.JsonNumber;
import io.avaje.json.node.JsonObject;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.hardware.UsbEvents;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.RobotClockSync;
import org.photonvision.common.util.TimedTaskManager;
import org.wpilib.networktables.BooleanPublisher;
import org.wpilib.networktables.DoublePublisher;
import org.wpilib.networktables.IntegerPublisher;
import org.wpilib.networktables.NetworkTable;
import org.wpilib.networktables.NetworkTableEvent;
import org.wpilib.networktables.StringPublisher;

/**
 * SpectrumJetson (photonvision-82): what TXMAN1 showed we couldn't see, on NetworkTables under
 * /photonvision/jetson/, so it lands in the robot's log. The Jetson's own logs stop at quiet mode
 * and keep only a few boots; the robot's log keeps every match.
 *
 * <p>Once a second:
 *
 * <ul>
 *   <li>{@code usbEvents}, {@code lastUsbEvent}: kernel USB trouble since boot (disconnects, a hub
 *       disabling a port, enumeration failures, uvcvideo and xHCI errors), and the latest line
 *   <li>{@code ethInterface}, {@code ethLinkUp}, {@code ethSpeedMbps}, {@code ethCarrierChanges}:
 *       the wired link to the robot. A carrier change at an NT drop means the Jetson's own cable or
 *       the switch port lost link
 *   <li>{@code ntDisconnects}: NetworkTables disconnects this process has seen
 *   <li>{@code bootId}, {@code uptimeSeconds}: a reboot changes the boot ID; a reconnect doesn't
 *   <li>{@code clockSource} ("robot": set from or checked against the robot's clock, or this is the
 *       robot controller; "internet" or "unset": the date restored at boot), {@code
 *       clockLastJumpSeconds}
 *   <li>{@code cpuUtilPct}, {@code ramUsedPct}
 *   <li>{@code ssdTempC}; {@code fsErrors} (ext4 errors recorded on any mounted partition), {@code
 *       settingsReadOnly}, {@code scratchReadOnly} (the scratch partition is read-only on purpose
 *       in quiet mode; settings never should be)
 * </ul>
 *
 * Every 5 minutes, from {@code smartctl} (smartmontools, installed by install.sh): {@code
 * ssdMediaErrors}, {@code ssdUnsafeShutdowns}, {@code ssdPercentUsed}, {@code ssdCriticalWarning},
 * {@code ssdAvailableSparePct}. Anything a board doesn't provide isn't published.
 *
 * <p>Not a Jetson-only feature: on the SystemCore the Ethernet, NT, boot, CPU, RAM and filesystem
 * values still mean something; the SSD ones aren't published without an NVMe drive. Not yet in this
 * port: {@code usbBandwidthUsedBytes}/{@code usbBandwidthBudgetBytes} (they need UsbBandwidth,
 * camera reliability) and the input voltage and Tj in the summary line (JetsonTelemetry, Jetson
 * telemetry); add them back when those features land.
 */
public final class JetsonDiagnostics {
    private static final Logger logger = new Logger(JetsonDiagnostics.class, LogGroup.General);
    private static final JsonNodeMapper JSON = JsonNodeMapper.builder().build();
    private static JetsonDiagnostics instance;

    /** Starts the publishers (idempotent). */
    public static synchronized void start() {
        if (instance == null) instance = new JetsonDiagnostics();
    }

    static final long SMART_EVERY_S = 300;

    private final NetworkTable table;
    private final Map<String, DoublePublisher> doubles = new HashMap<>();
    private final Map<String, IntegerPublisher> ints = new HashMap<>();
    private final Map<String, StringPublisher> strings = new HashMap<>();
    private final Map<String, BooleanPublisher> booleans = new HashMap<>();
    private final AtomicLong ntDisconnects = new AtomicLong();
    private final String ethInterface;
    private final Path ssdTemp;
    private final CpuMeter cpuMeter = new CpuMeter();
    private long ticks = 0;
    private double cpuMax = 0, ramMax = 0;

    /** A summary line in PhotonVision's own log this often (it's only open while not quiet). */
    static final long SUMMARY_EVERY_S = 60;

    private JetsonDiagnostics() {
        var nt = NetworkTablesManager.getInstance().getNTInst();
        table = nt.getTable("photonvision").getSubTable("jetson");
        ethInterface = findEthernet();
        ssdTemp = findNvmeTemp();
        nt.addConnectionListener(
                false,
                (NetworkTableEvent e) -> {
                    if (e.is(NetworkTableEvent.Kind.DISCONNECTED)) ntDisconnects.incrementAndGet();
                });
        String bootId = read(Path.of("/proc/sys/kernel/random/boot_id"));
        if (bootId != null) str("bootId", bootId);
        logger.info(
                "Jetson diagnostics on /photonvision/jetson: Ethernet "
                        + ethInterface
                        + (ssdTemp != null ? ", SSD temperature" : "")
                        + (smartctlPath() != null ? ", SSD SMART (smartctl)" : ", no smartctl"));
        TimedTaskManager.getInstance().addTask("JetsonDiagnostics", this::tick, 1000, 1000);
        TimedTaskManager.getInstance()
                .addTask("JetsonDiagnosticsSmart", this::smart, 5000, SMART_EVERY_S * 1000);
    }

    private void tick() {
        try {
            integer("usbEvents", UsbEvents.events());
            String last = UsbEvents.lastEvent();
            if (last != null) str("lastUsbEvent", last);

            if (ethInterface != null) {
                Path n = Path.of("/sys/class/net", ethInterface);
                str("ethInterface", ethInterface);
                Long carrier = longOf(n.resolve("carrier"));
                if (carrier != null) bool("ethLinkUp", carrier == 1);
                Long speed = longOf(n.resolve("speed"));
                if (speed != null && speed > 0) integer("ethSpeedMbps", speed);
                Long changes = longOf(n.resolve("carrier_changes"));
                if (changes != null) integer("ethCarrierChanges", changes);
            }
            integer("ntDisconnects", ntDisconnects.get());

            String up = read(Path.of("/proc/uptime"));
            if (up != null) dbl("uptimeSeconds", Double.parseDouble(up.split("\\s+")[0]));
            str("clockSource", RobotClockSync.source());
            dbl("clockLastJumpSeconds", RobotClockSync.lastJumpSeconds());

            Double cpu = cpuMeter.next(read(Path.of("/proc/stat")));
            if (cpu != null) {
                dbl("cpuUtilPct", cpu);
                cpuMax = Math.max(cpuMax, cpu);
            }
            Double ram = ramUsedPct(read(Path.of("/proc/meminfo")));
            if (ram != null) {
                dbl("ramUsedPct", ram);
                ramMax = Math.max(ramMax, ram);
            }

            if (ssdTemp != null) {
                Long t = longOf(ssdTemp);
                if (t != null) dbl("ssdTempC", t / 1000.0);
            }
            publishFilesystems();

            if (ticks++ % SUMMARY_EVERY_S == 0) logSummary();
        } catch (RuntimeException e) {
            logger.error("Jetson diagnostics failed", e);
        }
    }

    /**
     * One line in PhotonVision's log: PhotonVision measures CPU and RAM but never logged them, so the
     * Jetson's own logs had no record of either at TXMAN1. Peaks are over the last minute.
     */
    private void logSummary() {
        logger.info(
                String.format(
                        "Jetson: CPU peak %.0f%%, RAM peak %.0f%%, SSD %s, USB events %d,"
                                + " NT disconnects %d, Ethernet carrier changes %s, clock %s",
                        cpuMax,
                        ramMax,
                        ssdTemp == null
                                ? "?"
                                : fmt(longOf(ssdTemp) == null ? null : longOf(ssdTemp) / 1000.0, "%.0f C"),
                        UsbEvents.events(),
                        ntDisconnects.get(),
                        ethInterface == null
                                ? "?"
                                : String.valueOf(
                                        longOf(Path.of("/sys/class/net", ethInterface, "carrier_changes"))),
                        RobotClockSync.source()));
        cpuMax = ramMax = 0;
    }

    private static String fmt(Double v, String format) {
        return v == null || v.isNaN() ? "?" : String.format(format, v);
    }

    private void publishFilesystems() {
        long errors = 0;
        try (DirectoryStream<Path> devs = Files.newDirectoryStream(Path.of("/sys/fs/ext4"))) {
            for (Path d : devs) {
                Long n = longOf(d.resolve("errors_count"));
                if (n != null) errors += n;
            }
        } catch (Exception e) {
            return; // no ext4 here
        }
        integer("fsErrors", errors);
        String mounts = read(Path.of("/proc/mounts"));
        if (mounts == null) return;
        Boolean settings = readOnly(mounts, "/data/settings"),
                scratch = readOnly(mounts, "/data/scratch");
        if (settings != null) bool("settingsReadOnly", settings);
        if (scratch != null) bool("scratchReadOnly", scratch);
    }

    /** SSD health from smartctl's JSON, every 5 minutes. */
    private void smart() {
        String smartctl = smartctlPath();
        if (smartctl == null) return;
        try {
            var p =
                    new ProcessBuilder(smartctl, "-j", "-H", "-A", "/dev/nvme0")
                            .redirectErrorStream(true)
                            .start();
            byte[] out = p.getInputStream().readAllBytes();
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return;
            }
            var log = parseSmart(new String(out));
            if (log == null) return;
            log.forEach(this::integer);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("smartctl failed: " + e);
        }
    }

    // ---- pure helpers (tested in JetsonDiagnosticsTest) ----

    /**
     * The NVMe health values from {@code smartctl -j -H -A}, as topic name to value; null if the
     * output isn't smartctl's NVMe JSON.
     */
    static Map<String, Long> parseSmart(String json) {
        try {
            JsonObject root = JSON.fromJsonObject(json);
            if (!(root.extractNode("nvme_smart_health_information_log", JsonObject.empty())
                            instanceof JsonObject log)
                    || log.isEmpty()) return null;
            var out = new java.util.LinkedHashMap<String, Long>();
            put(out, "ssdMediaErrors", log.extractNode("media_errors", JsonObject.empty()));
            put(out, "ssdUnsafeShutdowns", log.extractNode("unsafe_shutdowns", JsonObject.empty()));
            put(out, "ssdPercentUsed", log.extractNode("percentage_used", JsonObject.empty()));
            put(out, "ssdCriticalWarning", log.extractNode("critical_warning", JsonObject.empty()));
            put(out, "ssdAvailableSparePct", log.extractNode("available_spare", JsonObject.empty()));
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static void put(Map<String, Long> out, String key, JsonNode n) {
        if (n instanceof JsonNumber num) out.put(key, num.numberValue().longValue());
    }

    /** CPU use between successive readings of /proc/stat. */
    static final class CpuMeter {
        private long[] last = null;

        /** CPU use since the previous call, from /proc/stat's first line; null the first time. */
        Double next(String procStat) {
            if (procStat == null) return null;
            String[] f = procStat.lines().findFirst().orElse("").trim().split("\\s+");
            if (f.length < 5 || !f[0].equals("cpu")) return null;
            long total = 0;
            for (int i = 1; i < f.length; i++) total += Long.parseLong(f[i]);
            long idle = Long.parseLong(f[4]) + (f.length > 5 ? Long.parseLong(f[5]) : 0); // idle + iowait
            long[] prev = last;
            last = new long[] {total, idle};
            if (prev == null || total <= prev[0]) return null;
            return 100.0 * (1 - (double) (idle - prev[1]) / (total - prev[0]));
        }
    }

    /** RAM in use (total minus available), from /proc/meminfo. */
    static Double ramUsedPct(String meminfo) {
        if (meminfo == null) return null;
        Long total = null, avail = null;
        for (String line : meminfo.split("\n")) {
            String[] f = line.trim().split("\\s+");
            if (f.length < 2) continue;
            if (f[0].equals("MemTotal:")) total = Long.parseLong(f[1]);
            if (f[0].equals("MemAvailable:")) avail = Long.parseLong(f[1]);
        }
        return total == null || avail == null || total == 0 ? null : 100.0 * (total - avail) / total;
    }

    /** Whether {@code mountPoint} is mounted read-only in /proc/mounts' text; null if not mounted. */
    static Boolean readOnly(String procMounts, String mountPoint) {
        Boolean ro = null;
        for (String line : procMounts.split("\n")) {
            String[] f = line.split(" ");
            // The last entry for a mount point wins (a bind or remount stacks on top).
            if (f.length >= 4 && f[1].equals(mountPoint))
                ro = java.util.Arrays.asList(f[3].split(",")).contains("ro");
        }
        return ro;
    }

    /** The wired interface: a physical, non-wireless, non-USB network device (enP8p1s0 here). */
    private static String findEthernet() {
        try (DirectoryStream<Path> nets = Files.newDirectoryStream(Path.of("/sys/class/net"))) {
            for (Path n : nets) {
                String name = n.getFileName().toString();
                if (name.equals("lo") || name.startsWith("usb") || name.startsWith("l4tbr")) continue;
                if (!Files.exists(n.resolve("device")) || Files.exists(n.resolve("wireless"))) continue;
                if (!"1".equals(read(n.resolve("type")))) continue; // Ethernet (ARPHRD_ETHER)
                if (n.resolve("device").toRealPath().toString().contains("/usb")) continue;
                return name;
            }
        } catch (Exception e) {
            // no sysfs
        }
        return null;
    }

    private static Path findNvmeTemp() {
        try (DirectoryStream<Path> mons =
                Files.newDirectoryStream(Path.of("/sys/class/hwmon"), "hwmon*")) {
            for (Path h : mons) {
                if ("nvme".equals(read(h.resolve("name"))) && Files.isReadable(h.resolve("temp1_input"))) {
                    return h.resolve("temp1_input");
                }
            }
        } catch (Exception e) {
            // no hwmon
        }
        return null;
    }

    private static String smartctlPath() {
        for (String p : new String[] {"/usr/sbin/smartctl", "/usr/bin/smartctl"}) {
            if (Files.isExecutable(Path.of(p))) return p;
        }
        return null;
    }

    private void dbl(String key, double v) {
        doubles.computeIfAbsent(key, k -> table.getDoubleTopic(k).publish()).set(v);
    }

    private void integer(String key, long v) {
        ints.computeIfAbsent(key, k -> table.getIntegerTopic(k).publish()).set(v);
    }

    private void str(String key, String v) {
        strings.computeIfAbsent(key, k -> table.getStringTopic(k).publish()).set(v);
    }

    private void bool(String key, boolean v) {
        booleans.computeIfAbsent(key, k -> table.getBooleanTopic(k).publish()).set(v);
    }

    private static Long longOf(Path p) {
        String s = read(p);
        try {
            return s == null ? null : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String read(Path p) {
        try {
            return Files.readString(p).trim();
        } catch (Exception e) {
            return null;
        }
    }
}
