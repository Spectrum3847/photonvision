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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.photonvision.common.hardware.UsbEvents;

/** SpectrumJetson (photonvision-82): the parsing behind /photonvision/jetson's diagnostics. */
public class JetsonDiagnosticsTest {
    @Test
    public void smartctlNvmeJson() {
        // The fields smartctl 7.2 (Ubuntu 22.04) prints for an NVMe drive with -j -H -A.
        String json =
                "{\"smart_status\":{\"passed\":true,\"nvme\":{\"value\":0}},"
                        + "\"nvme_smart_health_information_log\":{\"critical_warning\":0,\"temperature\":44,"
                        + "\"available_spare\":100,\"available_spare_threshold\":10,\"percentage_used\":1,"
                        + "\"data_units_read\":1234,\"unsafe_shutdowns\":187,\"media_errors\":0}}";
        assertEquals(
                Map.of(
                        "ssdMediaErrors", 0L,
                        "ssdUnsafeShutdowns", 187L,
                        "ssdPercentUsed", 1L,
                        "ssdCriticalWarning", 0L,
                        "ssdAvailableSparePct", 100L),
                JetsonDiagnostics.parseSmart(json));
        assertNull(JetsonDiagnostics.parseSmart("{\"smartctl\":{\"exit_status\":2}}"), "no NVMe log");
        assertNull(JetsonDiagnostics.parseSmart("smartctl: not found"));
    }

    @Test
    public void ramFromMeminfo() {
        String meminfo =
                "MemTotal:        7802716 kB\nMemFree:          912345 kB\nMemAvailable:    5852037 kB\n";
        assertEquals(25.0, JetsonDiagnostics.ramUsedPct(meminfo), 0.01);
        assertNull(JetsonDiagnostics.ramUsedPct("MemTotal: 1 kB\n"));
    }

    @Test
    public void readOnlyMounts() {
        String mounts =
                "/dev/nvme0n1p1 / ext4 ro,noatime 0 0\n"
                        + "/dev/nvme0n1p16 /data/settings ext4 rw,noatime 0 0\n"
                        + "/dev/nvme0n1p17 /data/scratch ext4 rw,noatime 0 0\n"
                        + "/dev/nvme0n1p17 /data/scratch ext4 ro,noatime 0 0\n"; // quiet mode remount
        assertFalse(JetsonDiagnostics.readOnly(mounts, "/data/settings"));
        assertTrue(JetsonDiagnostics.readOnly(mounts, "/data/scratch"), "the last entry wins");
        assertNull(JetsonDiagnostics.readOnly(mounts, "/data/other"));
    }

    @Test
    public void cpuFromTwoReadsOfProcStat() {
        // Between the two readings 1000 jiffies passed, 250 of them idle or iowait.
        var m = new JetsonDiagnostics.CpuMeter();
        assertNull(
                m.next("cpu  100 0 100 700 100 0 0 0 0 0\ncpu0 1 2 3"),
                "the first reading has no interval");
        assertEquals(75.0, m.next("cpu  500 0 450 900 150 0 0 0 0 0\n"), 1e-9);
        assertNull(m.next("intr 1 2 3"));
    }

    @Test
    public void usbEventsFromTxman1Q13() {
        long before = UsbEvents.events();
        String[] q13 = {
            "Oct 15 15:27:11 photonvision kernel: [  224.500901] usb 1-2-port1: disabled by hub (EMI?),"
                    + " re-enabling...",
            "Oct 15 15:27:11 photonvision kernel: [  224.501603] usb 1-2.1: USB disconnect, device number"
                    + " 4",
            "Oct 15 15:27:13 photonvision kernel: [  225.876372] usb 1-2.1: device descriptor read/64,"
                    + " error -110",
            "Oct 15 15:27:21 photonvision kernel: [  233.792729] usb 1-2-port1: attempt power cycle",
            "Oct 15 15:27:31 photonvision kernel: [  244.368770] usb 1-2-port1: unable to enumerate USB"
                    + " device",
        };
        for (String l : q13) UsbEvents.noteKernelLine(l);
        // Not trouble: a camera arriving, the driver's own setup lines, unrelated kernel lines.
        String[] fine = {
            "Oct 15 15:23:06 photonvision kernel: [    1.0] usb 1-2.1: new high-speed USB device number 4"
                    + " using tegra-xusb",
            "Oct 15 15:23:08 photonvision kernel: [    3.1] usb 1-2.1: Found UVC 1.00 device Thrifty"
                    + " (1bcf:28c5)",
            "Oct 15 15:26:34 photonvision kernel: [  187.27] EXT4-fs (nvme0n1p17): re-mounted. Opts:"
                    + " (null).",
        };
        for (String l : fine) UsbEvents.noteKernelLine(l);
        assertEquals(before + q13.length, UsbEvents.events());
        assertEquals("usb 1-2-port1: unable to enumerate USB device", UsbEvents.lastEvent());
        assertTrue(UsbEvents.isEvent("uvcvideo 1-2.3:1.1: Failed to resubmit video URB (-1)."));
        assertTrue(
                UsbEvents.isEvent(
                        "xhci-hcd 3610000.usb: xHCI host controller not responding, assume dead"));
        assertTrue(UsbEvents.isEvent("tegra-xusb 3610000.usb: HC died; cleaning up"));
    }
}
