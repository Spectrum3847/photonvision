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

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import io.avaje.jsonb.Types;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.photonvision.PhotonVersion;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.TimedTaskManager;
import org.photonvision.fieldmap.FieldMapHash;
import org.photonvision.vision.target.ExcludedTags;
import org.wpilib.networktables.StringPublisher;
import org.wpilib.vision.camera.CameraServerJNI;

/**
 * SpectrumJetson (photonvision-24): the Jetson-wide settings on NetworkTables as one JSON string,
 * /photonvision/jetson/settingsJson, rebuilt every 5 s, for the robot log. It's sent only when it
 * changes. Holds:
 *
 * <ul>
 *   <li>the PhotonVision build;
 *   <li>every SPECTRUM_* setting in PhotonVision's environment (detector threshold, JPEG decoder,
 *       CUDA wait, game-piece fps cap, stream fps, camera delay), plus the /tmp runtime overrides
 *       for the JPEG decoder and CUDA wait;
 *   <li>the tags left out of multi-tag (photonvision-22);
 *   <li>the field layout: tag count, size and its hash, so logs show which layout was loaded;
 *   <li>the USB camera driver's bandwidth cap.
 * </ul>
 *
 * <p>2027: plain maps (avaje) instead of Jackson nodes, and the field layout's fingerprint is the
 * field map hash robot code uses (FieldMapHash, photonvision-74) instead of 2026's first 12 hex
 * characters of a SHA-256 of the Jackson tag list, so a log's hash can be compared with the
 * robot's.
 */
public final class JetsonSettingsPublisher {
    private static final Logger logger = new Logger(JetsonSettingsPublisher.class, LogGroup.General);
    private static final JsonType<Map<String, Object>> mapJson =
            Jsonb.instance().type(Types.mapOf(Object.class));
    private static JetsonSettingsPublisher instance;
    private static volatile Map<String, Object> latest = null;

    public static synchronized void start() {
        if (instance == null) instance = new JetsonSettingsPublisher();
    }

    /** The latest snapshot, or null before the first one (for Rewind's session.json). */
    public static Map<String, Object> snapshot() {
        return latest;
    }

    private final StringPublisher publisher;
    private boolean loggedFailure = false;

    private JetsonSettingsPublisher() {
        publisher =
                NetworkTablesManager.getInstance()
                        .getNTInst()
                        .getTable("photonvision")
                        .getSubTable("jetson")
                        .getStringTopic("settingsJson")
                        .publish(); // sent only when it changes (duplicates dropped)
        TimedTaskManager.getInstance().addTask("JetsonSettings", this::publish, 2000, 5000);
    }

    private void publish() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("photonVersion", PhotonVersion.versionString);
            root.put("buildDate", PhotonVersion.buildDate);
            root.put("hostname", CameraServerJNI.getHostname());

            Map<String, Object> env = new LinkedHashMap<>();
            new TreeMap<>(System.getenv())
                    .forEach(
                            (k, v) -> {
                                if (k.startsWith("SPECTRUM_")) env.put(k, v);
                            });
            root.put("environment", env);
            Map<String, Object> overrides = new LinkedHashMap<>();
            for (String f :
                    new String[] {
                        "/tmp/spectrum-jpeg-decoder",
                        "/tmp/spectrum-detector-cuda-sync",
                        "/tmp/spectrum-971-cuda-sync" // the name before 2026-10-31
                    }) {
                String v = read(Path.of(f));
                if (v != null) overrides.put(f, v);
            }
            root.put("runtimeOverrides", overrides);

            // photonvision-22's part: the tags left out of multi-tag, saved and from the robot.
            root.put(
                    "excludedTags",
                    new java.util.ArrayList<>(new TreeSet<>(ExcludedTags.getInstance().active())));

            var layout = ConfigProvider.getInstance().getConfig().getFieldLayout();
            if (layout != null && layout.getTags() != null) {
                Map<String, Object> l = new LinkedHashMap<>();
                l.put("tags", layout.getTags().size());
                l.put("lengthM", layout.getFieldLength());
                l.put("widthM", layout.getFieldWidth());
                l.put("hash", FieldMapHash.hash(layout));
                root.put("fieldLayout", l);
            }

            String cap = read(Path.of("/sys/module/uvcvideo/parameters/payload_cap"));
            if (cap != null) root.put("uvcPayloadCap", cap);

            latest = root;
            publisher.set(mapJson.toJson(root));
            loggedFailure = false;
        } catch (Exception e) {
            if (!loggedFailure) logger.warn("Could not publish Jetson settings: " + e);
            loggedFailure = true;
        }
    }

    private static String read(Path p) {
        try {
            return Files.exists(p) ? Files.readString(p).strip() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
