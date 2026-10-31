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

import io.avaje.json.node.JsonArray;
import io.avaje.json.node.JsonNodeMapper;
import io.avaje.json.node.JsonObject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.vision.processes.VisionSourceManager;

/**
 * SpectrumJetson: Swyft USB Camera firmware from Settings > Swyft camera firmware, like {@link
 * ThriftiestFirmware}.
 *
 * <ul>
 *   <li>The cameras: {@code swyft-fw.py list --json} (USB port, serial, firmware revision, or
 *       stuck in the boot loader). It only reads USB descriptors, so it's safe while streaming.
 *   <li>Firmware files (.rfw, Swyft's download) are uploaded once and kept on the settings
 *       partition ({@link #store()}), checked with {@code swyft-fw.py check-image}.
 *   <li>A flash runs {@code swyft-flash-job.sh} as a transient systemd unit: it stops PhotonVision
 *       (whose camera watchdog would reset the camera mid-write), flashes with a backup of the
 *       camera's flash into {@link #store()}/backups, and starts PhotonVision again. Its progress is
 *       in {@link #JOB_STATUS}. After a flash the camera's video stays off until the robot is power
 *       cycled (a Jetson reboot isn't enough: docs/SWYFT-CAMERA-FIRMWARE.md).
 * </ul>
 *
 * The scripts are the repo's own, found through /opt/photonvision/health-check.sh, the link
 * 06-install-fork-jar.sh makes to the repo's scripts/jetson.
 */
public final class SwyftFirmware {
    private static final Logger logger = new Logger(SwyftFirmware.class, LogGroup.General);
    private static final JsonNodeMapper json = JsonNodeMapper.builder().build();
    private static final Path JOB_STATUS = Path.of("/run/spectrum/swyft-flash.json");
    private static final long MAX_IMAGE_BYTES = 128 * 1024;

    private record CheckedImage(long size, long mtime, JsonObject check) {}

    private static final Map<String, CheckedImage> checked = new HashMap<>();

    private SwyftFirmware() {}

    private static Path scripts() throws IOException {
        return Path.of("/opt/photonvision/health-check.sh").toRealPath().getParent();
    }

    private static Path store() {
        return ConfigProvider.getInstance().getLogsDir().getParent().resolve("swyft-firmware");
    }

    /** Runs swyft-fw.py with these arguments and returns its JSON output. */
    private static String tool(String... args) throws IOException {
        var cmd = new ArrayList<String>(List.of("python3", scripts().resolve("swyft-fw.py").toString()));
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("swyft-fw.py " + args[0] + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (out.isBlank()) throw new IOException("swyft-fw.py " + args[0] + " gave no answer");
        return out;
    }

    /** USB port ("2.4") -> PhotonVision camera nickname, for the cameras PhotonVision runs. */
    private static Map<String, String> nicknames() {
        var out = new HashMap<String, String>();
        for (var m : VisionSourceManager.getInstance().vmm.getModules()) {
            try {
                var video = Path.of(m.getCameraConfiguration().getDevicePath()).toRealPath().getFileName();
                var usb =
                        Path.of("/sys/class/video4linux", video.toString(), "device").toRealPath().getParent();
                String port = usb.getFileName().toString(); // 1-2.4
                out.put(port.substring(port.indexOf('-') + 1), m.getCameraConfiguration().nickname);
            } catch (Exception e) {
                // not on USB right now
            }
        }
        return out;
    }

    private static boolean robotEnabled() {
        return NetworkTablesManager.getInstance().robotEnabled();
    }

    private static JsonObject job() {
        try {
            return Files.exists(JOB_STATUS) ? json.fromJsonObject(Files.readString(JOB_STATUS)) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean jobRunning() {
        var j = job();
        return j != null
                && "running".equals(j.extract("state", ""))
                && System.currentTimeMillis() / 1000 - j.extract("updated", 0L) < 120;
    }

    /** Cameras, the stored firmware files, the flash job, and the robot's state. */
    public static synchronized JsonObject status() {
        var out = JsonObject.create();
        var names = nicknames();
        try {
            // Parsed avaje nodes are read-only (add() throws, with no message): copy each one.
            var cams = JsonArray.create();
            for (var c : json.fromJsonArray(tool("list", "--json")).elements()) {
                var cam = JsonObject.create();
                ((JsonObject) c).elements().forEach(cam::add);
                cam.add("camera", names.getOrDefault(cam.extract("port", ""), ""));
                cams.add(cam);
            }
            out.add("cameras", cams);
        } catch (IOException | RuntimeException e) {
            out.add("cameras", JsonArray.create());
            out.add("error", "Couldn't list the Swyft cameras: " + e.getMessage());
        }
        var images = JsonArray.create();
        try (var files = Files.list(store())) {
            for (var f : files.filter(f -> f.toString().endsWith(".rfw")).sorted().toList()) {
                long size = Files.size(f), mtime = Files.getLastModifiedTime(f).toMillis();
                var c = checked.get(f.getFileName().toString());
                if (c == null || c.size() != size || c.mtime() != mtime) {
                    c =
                            new CheckedImage(
                                    size, mtime, json.fromJsonObject(tool("check-image", "--json", f.toString())));
                    checked.put(f.getFileName().toString(), c);
                }
                images.add(c.check());
            }
        } catch (IOException | RuntimeException e) {
            // no store yet
        }
        out.add("images", images);
        var job = job();
        if (job != null) out.add("job", job);
        out.add("robotEnabled", robotEnabled());
        return out;
    }

    /** Stores an uploaded firmware file, after checking it's a Swyft camera image. */
    public static synchronized JsonObject upload(String name, InputStream content)
            throws IOException {
        name = Path.of(name).getFileName().toString();
        if (!name.matches("[A-Za-z0-9._+-]{1,100}\\.rfw")) {
            throw new IOException("Firmware files are .rfw, named with letters, digits and . _ + - only");
        }
        Files.createDirectories(store());
        var tmp = store().resolve(name + ".upload");
        try {
            byte[] bytes = content.readNBytes((int) MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES)
                throw new IOException("Too big for the camera's 128 KiB flash");
            Files.write(tmp, bytes);
            var check = JsonObject.create(); // a copy: parsed avaje nodes are read-only
            json.fromJsonObject(tool("check-image", "--json", tmp.toString())).elements().forEach(check::add);
            if (!check.extract("error", "").isEmpty()) throw new IOException(check.extract("error", ""));
            Files.move(tmp, store().resolve(name), StandardCopyOption.REPLACE_EXISTING);
            check.add("file", name); // not the temporary name
            logger.info("Stored Swyft camera firmware " + name + " (" + check.extract("label", "") + ")");
            return check;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static synchronized void delete(String name) throws IOException {
        var f = store().resolve(Path.of(name).getFileName().toString());
        if (!f.toString().endsWith(".rfw") || !Files.deleteIfExists(f))
            throw new IOException("No firmware file " + name);
        checked.remove(f.getFileName().toString());
    }

    /**
     * Starts flashing a stored firmware file onto the Swyft in a USB port, with a new serial number
     * if one's given (null or blank: the image's). PhotonVision is stopped for under a minute (every
     * camera) and started again by the job; the camera's video needs a robot power cycle after.
     */
    public static synchronized void flash(String port, String image, String serial)
            throws IOException {
        if (robotEnabled()) throw new IOException("The robot is enabled: disable it first");
        if (jobRunning()) throw new IOException("A camera is already being flashed");
        serial = serial == null ? "" : serial.strip();
        if (!serial.isEmpty() && !serial.matches("[A-Za-z0-9._-]{1,30}"))
            throw new IOException("Serial numbers are letters, digits and . _ - only (e.g. SJ-SW01)");
        boolean known = false;
        for (var c : json.fromJsonArray(tool("list", "--json")).elements()) {
            known |= c.extract("port", "").equals(port);
        }
        if (!known) throw new IOException("No Swyft camera on USB port " + port);
        var file = store().resolve(Path.of(image).getFileName().toString());
        if (!file.toString().endsWith(".rfw") || !Files.exists(file))
            throw new IOException("No firmware file " + image);
        Files.createDirectories(JOB_STATUS.getParent());
        Files.writeString(
                JOB_STATUS,
                json.toJson(
                        JsonObject.create()
                                .add("state", "running")
                                .add("message", "Starting")
                                .add("port", port)
                                .add("image", file.getFileName().toString())
                                .add("serial", serial)
                                .add("started", System.currentTimeMillis() / 1000)
                                .add("updated", System.currentTimeMillis() / 1000)
                                .add("log", JsonArray.create())));
        logger.warn(
                "Flashing "
                        + file.getFileName()
                        + " onto the Swyft camera on USB port "
                        + port
                        + (serial.isEmpty() ? "" : " (serial " + serial + ")")
                        + ": PhotonVision stops for under a minute; power cycle the robot after");
        var cmd =
                new ArrayList<String>(
                        List.of(
                                "systemd-run",
                                "--unit=spectrum-swyft-flash",
                                "--collect",
                                "/bin/bash",
                                scripts().resolve("swyft-flash-job.sh").toString(),
                                port,
                                file.toString(),
                                store().resolve("backups").toString()));
        if (!serial.isEmpty()) cmd.add(serial);
        new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
