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
 * SpectrumJetson (photonvision-87): Thriftiest Cam firmware from Settings > Thriftiest Cam
 * firmware.
 *
 * <ul>
 *   <li>Which firmware each camera runs: {@code thriftiest-fw.py variant}, which reads a few flash
 *       bytes through the video driver while the camera streams (every variant reports 5.13).
 *   <li>Firmware files are uploaded once and kept on the settings partition ({@link #store()}),
 *       checked with {@code thriftiest-fw.py check-image}. None ship with PhotonVision.
 *   <li>A flash runs {@code thriftiest-flash-job.sh} as a transient systemd unit: it stops
 *       PhotonVision (whose camera watchdog would reset the camera mid-write), flashes with a
 *       backup and every check, and starts PhotonVision again. Its progress is in {@link
 *       #JOB_STATUS}.
 * </ul>
 *
 * The scripts are the repo's own, found through /opt/photonvision/health-check.sh, the link
 * 06-install-fork-jar.sh makes to the repo's scripts/jetson.
 */
public final class ThriftiestFirmware {
    private static final Logger logger = new Logger(ThriftiestFirmware.class, LogGroup.General);
    private static final JsonNodeMapper json = JsonNodeMapper.builder().build();
    private static final Path JOB_STATUS = Path.of("/run/spectrum/thriftiest-flash.json");
    private static final long MAX_IMAGE_BYTES = 512 * 1024;

    private record CheckedImage(long size, long mtime, JsonObject check) {}

    private static final Map<String, CheckedImage> checked = new HashMap<>();

    private ThriftiestFirmware() {}

    private static Path scripts() throws IOException {
        return Path.of("/opt/photonvision/health-check.sh").toRealPath().getParent();
    }

    private static Path store() {
        return ConfigProvider.getInstance().getLogsDir().getParent().resolve("thriftiest-firmware");
    }

    /** Runs thriftiest-fw.py with these arguments and returns its JSON output. */
    private static String tool(String... args) throws IOException {
        var cmd =
                new ArrayList<String>(List.of("python3", scripts().resolve("thriftiest-fw.py").toString()));
        cmd.addAll(List.of(args));
        var p = new ProcessBuilder(cmd).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        try {
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("thriftiest-fw.py " + args[0] + " timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (out.isBlank()) throw new IOException("thriftiest-fw.py " + args[0] + " gave no answer");
        return out;
    }

    /** USB port ("2.1") -> PhotonVision camera nickname, for the cameras PhotonVision runs. */
    private static Map<String, String> nicknames() {
        var out = new HashMap<String, String>();
        for (var m : VisionSourceManager.getInstance().vmm.getModules()) {
            try {
                var video = Path.of(m.getCameraConfiguration().getDevicePath()).toRealPath().getFileName();
                var usb =
                        Path.of("/sys/class/video4linux", video.toString(), "device").toRealPath().getParent();
                String port = usb.getFileName().toString(); // 1-2.3
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

    /**
     * Cameras with their firmware, the stored firmware files, the flash job, and the robot's state.
     */
    public static synchronized JsonObject status() {
        var out = JsonObject.create();
        var names = nicknames();
        try {
            // Parsed avaje nodes are read-only (add() throws, with no message): copy each one.
            var cams = JsonArray.create();
            var seen = new java.util.HashSet<String>();
            for (var c : json.fromJsonArray(tool("variant", "--json")).elements()) {
                // thriftiest-fw.py can list a camera once per video node: one row per USB port
                if (!seen.add(((JsonObject) c).extract("port", ""))) continue;
                var cam = JsonObject.create();
                ((JsonObject) c).elements().forEach(cam::add);
                cam.add("camera", names.getOrDefault(cam.extract("port", ""), ""));
                cams.add(cam);
            }
            out.add("cameras", cams);
        } catch (IOException | RuntimeException e) {
            out.add("cameras", JsonArray.create());
            out.add("error", "Couldn't read the cameras' firmware: " + e.getMessage());
        }
        var images = JsonArray.create();
        try (var files = Files.list(store())) {
            for (var f : files.filter(f -> f.toString().endsWith(".bin")).sorted().toList()) {
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

    /** Stores an uploaded firmware file, after checking it's a Thriftiest Cam image. */
    public static synchronized JsonObject upload(String name, InputStream content)
            throws IOException {
        name = Path.of(name).getFileName().toString();
        if (!name.matches("[A-Za-z0-9._+-]{1,100}\\.bin")) {
            throw new IOException("Firmware files are .bin, named with letters, digits and . _ + - only");
        }
        Files.createDirectories(store());
        var tmp = store().resolve(name + ".upload");
        try {
            byte[] bytes = content.readNBytes((int) MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES)
                throw new IOException("Too big for the camera's 512 KiB flash");
            Files.write(tmp, bytes);
            var check = JsonObject.create(); // a copy: parsed avaje nodes are read-only
            json.fromJsonObject(tool("check-image", "--json", tmp.toString())).elements().forEach(check::add);
            if (!check.extract("error", "").isEmpty()) throw new IOException(check.extract("error", ""));
            Files.move(tmp, store().resolve(name), StandardCopyOption.REPLACE_EXISTING);
            check.add("file", name); // not the temporary name
            logger.info(
                    "Stored Thriftiest Cam firmware " + name + " (" + check.extract("label", "") + ")");
            return check;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public static synchronized void delete(String name) throws IOException {
        var f = store().resolve(Path.of(name).getFileName().toString());
        if (!f.toString().endsWith(".bin") || !Files.deleteIfExists(f))
            throw new IOException("No firmware file " + name);
        checked.remove(f.getFileName().toString());
    }

    /**
     * Starts flashing a stored firmware file onto the camera in a USB port. PhotonVision is stopped
     * for about a minute (every camera) and started again by the job.
     */
    public static synchronized void flash(String port, String image) throws IOException {
        if (robotEnabled()) throw new IOException("The robot is enabled: disable it first");
        if (jobRunning()) throw new IOException("A camera is already being flashed");
        boolean known = false;
        for (var c : json.fromJsonArray(tool("variant", "--json")).elements()) {
            known |= c.extract("port", "").equals(port);
        }
        if (!known) throw new IOException("No Thriftiest Cam on USB port " + port);
        var file = store().resolve(Path.of(image).getFileName().toString());
        if (!Files.exists(file)) throw new IOException("No firmware file " + image);
        Files.createDirectories(JOB_STATUS.getParent());
        Files.writeString(
                JOB_STATUS,
                json.toJson(
                        JsonObject.create()
                                .add("state", "running")
                                .add("message", "Starting")
                                .add("port", port)
                                .add("image", file.getFileName().toString())
                                .add("started", System.currentTimeMillis() / 1000)
                                .add("updated", System.currentTimeMillis() / 1000)
                                .add("log", JsonArray.create())));
        logger.warn(
                "Flashing "
                        + file.getFileName()
                        + " onto the Thriftiest Cam on USB port "
                        + port
                        + ": PhotonVision stops for about a minute");
        new ProcessBuilder(
                        "systemd-run",
                        "--unit=spectrum-thriftiest-flash",
                        "--collect",
                        "/bin/bash",
                        scripts().resolve("thriftiest-flash-job.sh").toString(),
                        port,
                        file.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
    }
}
