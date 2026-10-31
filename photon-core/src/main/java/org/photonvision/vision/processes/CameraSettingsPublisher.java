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

import io.avaje.jsonb.JsonType;
import io.avaje.jsonb.Jsonb;
import io.avaje.jsonb.Types;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.photonvision.common.dataflow.networktables.NetworkTablesManager;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.common.util.TimedTaskManager;
import org.photonvision.vision.camera.CameraQuirk;
import org.photonvision.vision.pipeline.CVPipelineSettings;
import org.wpilib.networktables.StringPublisher;

/**
 * SpectrumJetson (photonvision-24): this camera's settings on NetworkTables as one JSON string,
 * /photonvision/<camera>/settingsJson, rebuilt every 5 s, for the robot log. NetworkTables sends it
 * only when it changes, so it costs nothing between changes, and AdvantageKit records it at the
 * start of every log. So each match's log says what the cameras were set to.
 *
 * <p>It holds:
 *
 * <ul>
 *   <li>the current pipeline's full settings, as PhotonVision saves them (exposure, brightness,
 *       gain, decision margin, 3D, multi-tag, ...);
 *   <li>the video mode, whether the camera is enabled, and its FPS limit;
 *   <li>which lens calibration is in use (resolution, fx/fy/cx/cy, snapshot count);
 *   <li>the camera's quirks;
 *   <li>the camera's controls as actually set (raw UVC values, including ones the UI doesn't show,
 *       like contrast, gamma and sharpness).
 * </ul>
 *
 * The latest snapshot of every camera also goes into each Rewind recording's session.json.
 *
 * <p>2027: built as plain maps and lists (avaje) instead of Jackson nodes; the pipeline is
 * PhotonVision's own avaje JSON of it (one object with a {@code type} field, where 2026 saved
 * {@code [className, {...}]}).
 */
public class CameraSettingsPublisher {
    private static final Logger logger = new Logger(CameraSettingsPublisher.class, LogGroup.General);
    private static final JsonType<Map<String, Object>> mapJson =
            Jsonb.instance().type(Types.mapOf(Object.class));
    private static final JsonType<CVPipelineSettings> pipelineJson =
            Jsonb.instance().type(CVPipelineSettings.class);
    private static final long PERIOD_MS = 5000;
    private static final Map<String, Map<String, Object>> latest = new ConcurrentHashMap<>();

    /** The latest snapshot of every camera, by camera name (for Rewind's session.json). */
    public static Map<String, Map<String, Object>> snapshots() {
        return new LinkedHashMap<>(latest);
    }

    private final VisionModule module;
    private String publishedName = null;
    private StringPublisher publisher = null;
    private boolean loggedFailure = false;

    private final String taskName;

    public CameraSettingsPublisher(VisionModule module) {
        this.module = module;
        taskName = "CameraSettings-" + module.getCameraConfiguration().uniqueName;
        TimedTaskManager.getInstance().addTask(taskName, this::publish, 2000, PERIOD_MS);
    }

    /**
     * photonvision-63: called when the camera's module closes. Without it the old module kept
     * publishing its (stale) settings after a camera was deactivated, and, since addTask ignores a
     * second task with the same name, a reactivated camera's new module never published at all.
     */
    public void stop() {
        TimedTaskManager.getInstance().cancelTask(taskName);
        synchronized (this) {
            if (publisher != null) publisher.close();
            if (publishedName != null) latest.remove(publishedName);
            publisher = null;
            publishedName = null;
        }
    }

    private synchronized void publish() {
        try {
            var config = module.getCameraConfiguration();
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("camera", config.nickname);
            root.put("pipelineIndex", module.pipelineManager.getCurrentPipelineIndex());
            root.put("enabled", module.getEnabled());
            root.put("fpsLimit", module.getFPSLimit());

            var settables = module.visionSource.getSettables();
            var mode = settables.getCurrentVideoMode();
            if (mode != null) {
                root.put(
                        "videoMode",
                        mode.width + "x" + mode.height + " " + mode.pixelFormat + " " + mode.fps + " fps");
            }

            List<String> quirks = new ArrayList<>();
            if (config.cameraQuirks != null) {
                for (var q : CameraQuirk.values()) {
                    if (config.cameraQuirks.hasQuirk(q)) quirks.add(q.name());
                }
            }
            root.put("quirks", quirks);

            var props = settables.getFrameStaticProperties();
            var cal = props != null ? props.cameraCalibration : null;
            if (cal != null && cal.cameraIntrinsics != null && cal.cameraIntrinsics.data.length >= 9) {
                Map<String, Object> c = new LinkedHashMap<>();
                var k = cal.cameraIntrinsics.data;
                c.put("resolution", (int) cal.resolution.width + "x" + (int) cal.resolution.height);
                c.put("fx", k[0]);
                c.put("fy", k[4]);
                c.put("cx", k[2]);
                c.put("cy", k[5]);
                List<Double> d = new ArrayList<>();
                if (cal.distCoeffs != null) for (double v : cal.distCoeffs.data) d.add(v);
                c.put("distCoeffs", d);
                c.put("snapshots", cal.observations != null ? cal.observations.size() : 0);
                if (cal.lensmodel != null) c.put("lensModel", cal.lensmodel.name());
                root.put("calibration", c);
            } else {
                root.put("calibration", null);
            }

            root.put("controls", new LinkedHashMap<>(settables.getRawControls()));

            var pipeline = module.pipelineManager.getCurrentPipelineSettings();
            if (pipeline != null) {
                root.put("pipeline", mapJson.fromJson(pipelineJson.toJson(pipeline)));
            }

            latest.put(config.nickname, root);
            ensurePublisher(config.nickname);
            publisher.set(mapJson.toJson(root));
            loggedFailure = false;
        } catch (Exception e) {
            // Settings can change while being read; the next tick tries again.
            if (!loggedFailure) logger.warn("Could not publish camera settings: " + e);
            loggedFailure = true;
        }
    }

    /** (Re)creates the topic when the camera is first seen or renamed. */
    private void ensurePublisher(String name) {
        if (name.equals(publishedName)) return;
        if (publisher != null) {
            publisher.close();
            if (publishedName != null) latest.remove(publishedName);
        }
        publisher =
                NetworkTablesManager.getInstance()
                        .getNTInst()
                        .getTable("photonvision")
                        .getSubTable(name)
                        .getStringTopic("settingsJson")
                        .publish(); // sent only when it changes (duplicates dropped)
        publishedName = name;
    }
}
