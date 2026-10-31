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

package org.photonvision.common.hardware;

import io.avaje.jsonb.Json;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.vision.processes.VisionModule;
import org.photonvision.vision.processes.VisionSourceManager;

/**
 * SpectrumJetson (photonvision-32): the USB bandwidth each camera reserves and really uses, and
 * changing what a camera may reserve, for the USB Bandwidth page.
 *
 * <p>A USB camera reserves isochronous bandwidth for its alternate setting's packet size, whatever
 * it actually sends. Every USB 2.0 port on the Jetson (the USB-A hub, USB-C, M.2) shares one
 * budget: 6720 bytes per 125 us microframe fit, 7400 didn't (measured 2026-09-24). Our camera
 * driver (kernel/uvcvideo-payload-cap.patch) takes a cap per camera model or per USB port
 * ("payload_cap", writable at run time) and counts each stream's bytes and largest frames (its
 * debugfs "stats"). PhotonVision runs as root, so it can read and write both.
 */
public final class UsbBandwidth {
    /** Bytes per microframe the Jetson's USB 2.0 side fit (7400 was refused). */
    public static final int BUDGET_BYTES = 6720;

    public static final int MICROFRAMES_PER_S = 8000;

    private static final Logger logger = new Logger(UsbBandwidth.class, LogGroup.Camera);
    private static final Path DEVICES = Path.of("/sys/bus/usb/devices");
    private static final Path PARAM = Path.of("/sys/module/uvcvideo/parameters/payload_cap");
    private static final Path CONF = Path.of("/etc/modprobe.d/90-spectrum-uvcvideo.conf");
    private static final Path STATS = Path.of("/sys/kernel/debug/usb/uvcvideo");
    private static final Pattern MODEL_CAP =
            Pattern.compile("^([0-9a-fA-F]{4}):([0-9a-fA-F]{4}):(\\d+)$");
    private static final Pattern PORT_CAP = Pattern.compile("^(\\d+-[\\d.]+):(\\d+)$");
    private static final Pattern STAT_LINE =
            Pattern.compile("(?m)^(frames|bytes):\\s*(\\d+)|^frame bytes: max (\\d+), recent max (\\d+)");

    /**
     * One camera. Bytes are per 125 us microframe unless named otherwise. {@code wantBytes}: what it
     * reserves when it streams, under its cap. {@code capFrom}: "port", "model" or null (uncapped).
     */
    @Json
    public record Camera(
            String port,
            String id,
            String product,
            String camera,
            String uniqueName,
            List<Integer> altBytes,
            int reservedBytes,
            boolean streaming,
            Integer capBytes,
            String capFrom,
            int wantBytes,
            Double fps,
            Double usedBytesPerSec,
            Integer recentMaxFrameBytes,
            String problem) {}

    /**
     * Every camera on the USB 2.0 side. {@code driver}: "capped" (our driver, allocations can be
     * changed), "capped-readonly" (an older build of it: reinstall with 11-uvcvideo-payload-cap.sh to
     * change allocations here) or "stock".
     */
    @Json
    public record Survey(
            int budgetBytes,
            int microframesPerSecond,
            int reservedBytes,
            int wantBytes,
            Double usedBytesPerSec,
            String driver,
            String payloadCap,
            List<Camera> cameras) {}

    private record Sample(long tNs, long frames, long bytes) {}

    private record Rates(double fps, double bytesPerSec) {}

    private static final Map<String, Sample> samples = new HashMap<>();
    private static final Map<String, Rates> rates = new HashMap<>();

    private UsbBandwidth() {}

    /** Bytes per microframe of a wMaxPacketSize: the size times the transactions per microframe. */
    static int packetBytes(int w) {
        return (w & 0x7ff) * (1 + ((w >> 11) & 3));
    }

    /** The alternate setting the driver picks under a cap: the smallest one at least that big. */
    public static int altFor(List<Integer> alts, Integer cap) {
        int largest = alts.isEmpty() ? 0 : alts.get(alts.size() - 1);
        if (cap == null || cap <= 0) return largest;
        for (int a : alts) if (a >= cap) return a;
        return largest;
    }

    /** {interface: {alt: bytes of its isochronous IN endpoint}}, from the raw descriptors. */
    private static Map<Integer, Map<Integer, Integer>> altSettings(Path dev) {
        Map<Integer, Map<Integer, Integer>> out = new HashMap<>();
        byte[] d;
        try {
            d = Files.readAllBytes(dev.resolve("descriptors"));
        } catch (IOException e) {
            return out;
        }
        int i = 0;
        Integer intf = null, alt = null;
        while (i + 2 <= d.length) {
            int n = d[i] & 0xff, t = d[i + 1] & 0xff;
            if (n < 2) break;
            if (t == 4 && n >= 9 && i + 3 < d.length) { // interface
                intf = d[i + 2] & 0xff;
                alt = d[i + 3] & 0xff;
                out.computeIfAbsent(intf, k -> new HashMap<>()).putIfAbsent(alt, 0);
            } else if (t == 5 && n >= 7 && intf != null && i + 5 < d.length) { // endpoint
                int attrs = d[i + 3] & 0xff;
                if ((attrs & 3) == 1 && (d[i + 2] & 0x80) != 0) { // isochronous IN
                    int w = (d[i + 4] & 0xff) | ((d[i + 5] & 0xff) << 8);
                    out.get(intf).merge(alt, packetBytes(w), Math::max);
                }
            }
            i += n;
        }
        return out;
    }

    private static String read(Path p) {
        try {
            return Files.readString(p).trim();
        } catch (IOException e) {
            return "";
        }
    }

    private static int speedMbps(Path dev) {
        try {
            return (int) Double.parseDouble(read(dev.resolve("speed")));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String param() {
        return read(PARAM).replace("(null)", "");
    }

    private static String driver() {
        if (!Files.exists(PARAM)) return "stock";
        try {
            return Files.getPosixFilePermissions(PARAM).contains(PosixFilePermission.OWNER_WRITE)
                    ? "capped"
                    : "capped-readonly";
        } catch (IOException e) {
            return "capped-readonly";
        }
    }

    /** Port -> the camera PhotonVision has on it. */
    private static Map<String, VisionModule> modulesByPort() {
        Map<String, VisionModule> out = new HashMap<>();
        for (var m : VisionSourceManager.getInstance().vmm.getModules()) {
            var usb = m.usbFrameProvider();
            String port = usb == null ? null : usb.getUsbPort();
            if (port != null) out.put(port, m);
        }
        return out;
    }

    /** The driver's stats for one stream: frames, bytes, recent max frame; null if unreadable. */
    private static long[] stats(String dir) {
        String s = read(STATS.resolve(dir).resolve("stats"));
        if (s.isEmpty()) return null;
        long frames = -1, bytes = -1, recentMax = -1;
        Matcher m = STAT_LINE.matcher(s);
        while (m.find()) {
            if ("frames".equals(m.group(1))) frames = Long.parseLong(m.group(2));
            else if ("bytes".equals(m.group(1))) bytes = Long.parseLong(m.group(2));
            else if (m.group(4) != null) recentMax = Long.parseLong(m.group(4));
        }
        return frames < 0 || bytes < 0 ? null : new long[] {frames, bytes, recentMax};
    }

    /** fps and bytes/s since the last look (at least 0.5 s ago), or the last answer. */
    private static Rates rates(String dir, long frames, long bytes) {
        long now = System.nanoTime();
        Sample prev = samples.get(dir);
        if (prev != null && now - prev.tNs() < 500_000_000L) return rates.get(dir);
        samples.put(dir, new Sample(now, frames, bytes));
        if (prev == null
                || frames < prev.frames()
                || bytes < prev.bytes()
                || now - prev.tNs() > 30_000_000_000L) {
            rates.remove(dir); // first look, or the stream restarted
            return null;
        }
        double dt = (now - prev.tNs()) / 1e9;
        var r = new Rates((frames - prev.frames()) / dt, (bytes - prev.bytes()) / dt);
        rates.put(dir, r);
        return r;
    }

    /** Every USB video camera: what it reserves, wants, and sends. */
    public static synchronized Survey survey() {
        Map<String, Integer> modelCaps = new HashMap<>(), portCaps = new HashMap<>();
        String cap = param();
        parseCaps(cap, modelCaps, portCaps);
        var modules = modulesByPort();
        List<Camera> cams = new ArrayList<>();
        int reservedTotal = 0, wantTotal = 0;
        double usedTotal = 0;
        boolean anyUsed = false;
        File[] devs = DEVICES.toFile().listFiles();
        if (devs == null) devs = new File[0];
        java.util.Arrays.sort(devs);
        for (var f : devs) {
            Path dev = f.toPath();
            String port = f.getName();
            if (port.contains(":") || !Files.exists(dev.resolve("idVendor"))) continue;
            var alts = altSettings(dev);
            Integer videoIntf = null;
            int reserved = 0;
            for (var intfDir : f.listFiles((x, name) -> name.startsWith(port + ":"))) {
                String cls = read(intfDir.toPath().resolve("bInterfaceClass"));
                String sub = read(intfDir.toPath().resolve("bInterfaceSubClass"));
                boolean video = cls.equals("0e") && sub.equals("02"),
                        audio = cls.equals("01") && sub.equals("02");
                if (!video && !audio) continue;
                int n = Integer.parseInt(read(intfDir.toPath().resolve("bInterfaceNumber")), 16);
                int cur = Integer.parseInt(read(intfDir.toPath().resolve("bAlternateSetting")));
                reserved += alts.getOrDefault(n, Map.of()).getOrDefault(cur, 0);
                if (video && videoIntf == null) videoIntf = n;
            }
            if (videoIntf == null) continue;
            if (speedMbps(dev) < 480) continue; // USB 2.0 high speed only: that's the shared budget
            var sizes = new TreeSet<Integer>();
            alts.getOrDefault(videoIntf, Map.of())
                    .forEach(
                            (a, b) -> {
                                if (a != 0 && b > 0) sizes.add(b);
                            });
            List<Integer> altBytes = new ArrayList<>(sizes);
            String id = read(dev.resolve("idVendor")) + ":" + read(dev.resolve("idProduct"));
            Integer capBytes = portCaps.containsKey(port) ? portCaps.get(port) : modelCaps.get(id);
            String capFrom =
                    portCaps.containsKey(port) ? "port" : modelCaps.containsKey(id) ? "model" : null;
            boolean streaming = reserved > 0;
            int want = streaming ? reserved : altFor(altBytes, capBytes);
            Double fps = null, used = null;
            Integer recentMax = null;
            if (streaming) {
                String dir =
                        read(dev.resolve("busnum")) + "-" + read(dev.resolve("devnum")) + "-" + videoIntf;
                long[] st = stats(dir);
                if (st != null) {
                    var r = rates(dir, st[0], st[1]);
                    if (r != null) {
                        fps = r.fps();
                        used = r.bytesPerSec();
                        usedTotal += used;
                        anyUsed = true;
                    }
                    if (st[2] >= 0) recentMax = (int) st[2];
                }
            }
            var m = modules.get(port);
            var usb = m == null ? null : m.usbFrameProvider();
            cams.add(
                    new Camera(
                            port,
                            id,
                            read(dev.resolve("product")),
                            m == null ? null : m.getCameraConfiguration().nickname,
                            m == null ? null : m.uniqueName(),
                            altBytes,
                            reserved,
                            streaming,
                            capBytes,
                            capFrom,
                            want,
                            fps,
                            used,
                            recentMax,
                            usb == null ? "" : usb.getProblem()));
            reservedTotal += reserved;
            wantTotal += want;
        }
        return new Survey(
                BUDGET_BYTES,
                MICROFRAMES_PER_S,
                reservedTotal,
                wantTotal,
                anyUsed ? usedTotal : null,
                driver(),
                cap,
                cams);
    }

    private static void parseCaps(
            String cap, Map<String, Integer> modelCaps, Map<String, Integer> portCaps) {
        for (String part : cap.split(",")) {
            part = part.trim();
            Matcher m = MODEL_CAP.matcher(part);
            if (m.matches()) {
                modelCaps.put(
                        m.group(1).toLowerCase() + ":" + m.group(2).toLowerCase(),
                        Integer.parseInt(m.group(3)));
                continue;
            }
            m = PORT_CAP.matcher(part);
            if (m.matches()) portCaps.put(m.group(1), Integer.parseInt(m.group(2)));
        }
    }

    /**
     * Sets the most the camera on {@code port} may reserve: one of its alternate settings' sizes, 0
     * for uncapped, or null for its model's cap. Saved for the next boot, and applied by resetting
     * the camera at the USB level (a second or two without frames). Refused if it wouldn't fit.
     */
    public static synchronized String setAllocation(String port, Integer bytes) throws IOException {
        if (!driver().equals("capped")) {
            throw new IllegalStateException(
                    "The camera driver can't change allocations while running: reinstall it with"
                            + " scripts/jetson/11-uvcvideo-payload-cap.sh --install");
        }
        var s = survey();
        Camera cam = s.cameras().stream().filter(c -> c.port().equals(port)).findFirst().orElse(null);
        if (cam == null) throw new IllegalArgumentException("No USB camera on port " + port);
        if (bytes != null && bytes != 0 && !cam.altBytes().contains(bytes)) {
            throw new IllegalArgumentException(
                    bytes + " bytes isn't one of this camera's settings " + cam.altBytes());
        }
        Map<String, Integer> modelCaps = new HashMap<>(), portCaps = new HashMap<>();
        parseCaps(s.payloadCap(), modelCaps, portCaps);
        int want = altFor(cam.altBytes(), bytes != null ? bytes : modelCaps.get(cam.id()));
        int total = s.wantBytes() - cam.wantBytes() + want;
        if (total > BUDGET_BYTES) {
            throw new IllegalArgumentException(
                    String.format(
                            "That needs %d of the %d bytes per microframe every USB 2.0 camera shares:"
                                    + " lower another camera's allocation first",
                            total, BUDGET_BYTES));
        }
        if (bytes == null) portCaps.remove(port);
        else portCaps.put(port, bytes);
        var entries = new LinkedHashMap<String, Integer>();
        new java.util.TreeMap<>(modelCaps).forEach(entries::put);
        new java.util.TreeMap<>(portCaps).forEach(entries::put);
        String cap =
                String.join(
                        ",", entries.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue()).toList());
        Files.writeString(PARAM, cap);
        Files.writeString(
                CONF,
                "# SpectrumJetson: cap USB bandwidth reservations"
                        + " (scripts/jetson/11-uvcvideo-payload-cap.sh; per-port entries set on the USB"
                        + " Bandwidth page)\n"
                        + "options uvcvideo payload_cap="
                        + cap
                        + "\n");
        String name = cam.camera() != null ? cam.camera() : cam.product() + " on " + port;
        logger.info(
                String.format(
                        "USB bandwidth: %s may now reserve %d bytes per microframe (payload_cap=%s)",
                        name, want, cap));
        var m = modulesByPort().get(port);
        var usb = m == null ? null : m.usbFrameProvider();
        if (usb != null && cam.streaming() && want != cam.reservedBytes()) {
            new Thread(() -> usb.applyUsbBandwidth(want), "UsbBandwidth-" + port).start();
            return String.format(
                    "%s: resetting it to apply %d bytes (%.1f MB/s)", name, want, want * 8e-3);
        }
        return String.format("%s: %d bytes (%.1f MB/s) from its next start", name, want, want * 8e-3);
    }

    /** What the camera on {@code port} reserves right now, or -1 if it isn't there. */
    public static synchronized int reservedNow(String port) {
        return survey().cameras().stream()
                .filter(c -> c.port().equals(port))
                .mapToInt(Camera::reservedBytes)
                .findFirst()
                .orElse(-1);
    }
}
