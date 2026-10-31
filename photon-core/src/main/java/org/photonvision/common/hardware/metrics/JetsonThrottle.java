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

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * SpectrumJetson: why the Jetson is slowing itself down, or has since boot, from files readable
 * without root (photonvision-20). The Jetson's version of the Raspberry Pi's "get_throttled":
 *
 * <ul>
 *   <li><b>OVER-CURRENT</b>: soctherm's over-current events ({@code soctherm_oc} {@code
 *       oc*_event_cnt}) went up in the last 10 s. The chip throttles itself when its supply current
 *       spikes, e.g. when the robot's battery sags. "Prev. over-current (N)" means N events since
 *       boot but none recently.
 *   <li><b>HIGH TEMP (cpu, gpu, ...)</b>: a thermal throttle alert is active (the {@code
 *       *-throttle-alert} and {@code hot-surface-alert} cooling devices).
 *   <li><b>CPU CLOCK CAPPED</b> / <b>GPU CLOCK CAPPED</b>: the thermal framework is holding the CPU
 *       or GPU clock below its maximum ({@code cpufreq-cpu*}, {@code devfreq-*.gpu} cooling
 *       devices).
 * </ul>
 *
 * "None" when none of these apply.
 *
 * <p>All of these sysfs names are from JetPack 6 (r36) and are not yet checked on JetPack 7 (r39,
 * kernel 6.8): the soctherm_oc hwmon and the cooling-device type names may differ there. Each source
 * is found by searching and fails soft: whatever isn't found is left out of the reason, and with
 * none found {@link #available()} is false and nothing is published.
 */
public final class JetsonThrottle {
    private JetsonThrottle() {}

    private static final long RECENT_NS = 10_000_000_000L;

    private static boolean discovered = false;
    private static final List<Path> ocCounters = new ArrayList<>();
    private static final List<String[]> alerts = new ArrayList<>(); // {name, cur_state path}
    private static final List<Path> cpuCaps = new ArrayList<>(), gpuCaps = new ArrayList<>();

    private static long lastOcTotal = -1, lastOcIncreaseNs = 0;

    /** Whether any throttle source was found (false off a Jetson). */
    public static synchronized boolean available() {
        discover();
        return !ocCounters.isEmpty() || !alerts.isEmpty() || !cpuCaps.isEmpty() || !gpuCaps.isEmpty();
    }

    /** The current throttle reason; see the class comment. */
    public static synchronized String reason() {
        discover();
        long now = System.nanoTime();
        long oc = overCurrentEventsLocked();
        if (lastOcTotal >= 0 && oc > lastOcTotal) lastOcIncreaseNs = now;
        lastOcTotal = oc;

        var parts = new ArrayList<String>();
        if (lastOcIncreaseNs != 0 && now - lastOcIncreaseNs < RECENT_NS) parts.add("OVER-CURRENT");
        var hot = new ArrayList<String>();
        for (String[] a : alerts) {
            if (state(Path.of(a[1])) > 0) hot.add(a[0]);
        }
        if (!hot.isEmpty()) parts.add("HIGH TEMP (" + String.join(", ", hot) + ")");
        if (anyAbove(cpuCaps)) parts.add("CPU CLOCK CAPPED");
        if (anyAbove(gpuCaps)) parts.add("GPU CLOCK CAPPED");

        if (!parts.isEmpty()) return String.join(", ", parts);
        return oc > 0 ? "Prev. over-current (" + oc + ")" : "None";
    }

    /** Over-current throttle events since boot, or -1 if the Jetson doesn't report them. */
    public static synchronized long overCurrentEvents() {
        discover();
        return ocCounters.isEmpty() ? -1 : overCurrentEventsLocked();
    }

    private static long overCurrentEventsLocked() {
        long total = 0;
        for (Path p : ocCounters) total += Math.max(0, state(p));
        return total;
    }

    private static void discover() {
        if (discovered) return;
        discovered = true;
        // r36: hwmon named "soctherm_oc" with oc1_event_cnt..oc4_event_cnt. r39: unchecked.
        for (Path h : JetsonSysfs.children(Path.of("/sys/class/hwmon"), "hwmon*")) {
            if (!"soctherm_oc".equals(JetsonSysfs.read(h.resolve("name")))) continue;
            ocCounters.addAll(JetsonSysfs.children(h, "oc*_event_cnt"));
        }
        // r36 cooling-device types: cpu-throttle-alert, gpu-throttle-alert, ..., hot-surface-alert,
        // cpufreq-cpu0.., devfreq-17000000.gpu. r39: unchecked; the GPU match is loosened to any
        // devfreq device naming the GPU.
        for (Path d : JetsonSysfs.children(Path.of("/sys/class/thermal"), "cooling_device*")) {
            String type = JetsonSysfs.read(d.resolve("type"));
            if (type == null) continue;
            Path cur = d.resolve("cur_state");
            if (type.endsWith("-throttle-alert")) {
                alerts.add(
                        new String[] {
                            type.substring(0, type.length() - "-throttle-alert".length()), cur.toString()
                        });
            } else if (type.equals("hot-surface-alert")) {
                alerts.add(new String[] {"surface", cur.toString()});
            } else if (type.startsWith("cpufreq-")) {
                cpuCaps.add(cur);
            } else if (type.startsWith("devfreq") && type.contains("gpu")) {
                gpuCaps.add(cur);
            }
        }
    }

    private static boolean anyAbove(List<Path> states) {
        for (Path p : states) {
            if (state(p) > 0) return true;
        }
        return false;
    }

    private static long state(Path p) {
        String s = JetsonSysfs.read(p);
        if (s == null) return -1;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
