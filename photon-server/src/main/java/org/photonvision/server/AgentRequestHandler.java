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

package org.photonvision.server;

import io.avaje.jsonb.Jsonb;
import io.javalin.http.Context;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.photonvision.common.configuration.ConfigProvider;
import org.photonvision.common.dataflow.DataChangeDestination;
import org.photonvision.common.dataflow.DataChangeService;
import org.photonvision.common.dataflow.events.IncomingWebSocketEvent;
import org.photonvision.common.dataflow.websocket.UIDataPublisher;
import org.photonvision.common.dataflow.websocket.UIPhotonConfiguration;
import org.photonvision.common.logging.LogGroup;
import org.photonvision.common.logging.Logger;
import org.photonvision.vision.pipeline.AprilTagPipelineSettings;
import org.photonvision.vision.pipeline.ArucoPipelineSettings;
import org.photonvision.vision.pipeline.CVPipelineSettings;
import org.photonvision.vision.pipeline.ColoredShapePipelineSettings;
import org.photonvision.vision.pipeline.DriverModePipelineSettings;
import org.photonvision.vision.pipeline.ObjectDetectionPipelineSettings;
import org.photonvision.vision.pipeline.PipelineType;
import org.photonvision.vision.pipeline.ReflectivePipelineSettings;
import org.photonvision.vision.processes.VisionModule;
import org.photonvision.vision.processes.VisionSourceManager;
import org.wpilib.util.Pair;

/**
 * Plain-JSON HTTP API intended for scripts and AI agents. Everything the web UI can read or change
 * over the msgpack websocket is reachable here with curl. Writes are routed through the same
 * DataChangeService events the UI uses, so the UI updates live and settings are persisted the same
 * way.
 */
public class AgentRequestHandler {
    private static final Logger logger = new Logger(AgentRequestHandler.class, LogGroup.WebServer);

    private static final String HELP =
            """
            PhotonVision agent API (all JSON unless noted). <camera> = uniqueName or nickname.
            GET  /api/agent                      this help
            GET  /api/agent/state                full program + camera config, every pipeline's settings
            GET  /api/agent/schema?camera=<c>    fields of the camera's ACTIVE pipeline: name, type, value, enum options
            GET  /api/agent/schema?type=<t>      same, for a pipeline type's defaults (no camera needed)
            GET  /api/agent/results[?camera=<c>] latest targets/fps/latency per camera (~10Hz snapshot)
            GET  /api/agent/logs?lines=200&contains=<text>   recent log lines (text/plain)
            POST /api/agent/pipeline/settings    {"camera":c, "settings":{"key":value,...}}  edits ACTIVE pipeline
            POST /api/agent/pipeline/select      {"camera":c, "index":n}
            POST /api/agent/pipeline/add         {"camera":c, "name":s, "type":"AprilTag|Aruco|Reflective|ColoredShape|ObjectDetection"}
            POST /api/agent/pipeline/duplicate   {"camera":c, "index":n}
            POST /api/agent/pipeline/delete      {"camera":c}   deletes ACTIVE pipeline
            POST /api/agent/pipeline/rename      {"camera":c, "name":s}   renames ACTIVE pipeline
            POST /api/agent/pipeline/type        {"camera":c, "type":"AprilTag|..."}  changes ACTIVE pipeline type
            POST /api/agent/drivermode           {"camera":c, "enabled":bool}
            POST /api/agent/snapshot             {"camera":c, "kind":"input|output"}  then GET /api/utils/getImageSnapshots
            Enum settings accept either the ordinal or the constant name. Changes apply asynchronously
            (next frame); re-read /api/agent/state or /schema to confirm.
            Live MJPEG streams: http://<host>:<inputStreamPort|outputStreamPort>/stream.mjpg (ports in /state).
            """;

    private AgentRequestHandler() {}

    // ---------------------------------------------------------------- reads

    public static void onHelp(Context ctx) {
        ctx.contentType("text/plain").result(HELP);
    }

    public static void onState(Context ctx) {
        var out = new LinkedHashMap<String, Object>();
        out.put(
                "config", UIPhotonConfiguration.programStateToUi(ConfigProvider.getInstance().getConfig()));
        var pipelines = new LinkedHashMap<String, Object>();
        for (var m : modules()) {
            pipelines.put(m.uniqueName(), m.getAllPipelineSettingsAsMaps());
        }
        out.put("allPipelineSettings", pipelines);
        out.put("pipelineTypes", pipelineTypeNames());
        json(ctx, out);
    }

    public static void onSchema(Context ctx) {
        var typeParam = ctx.queryParam("type");
        if (typeParam != null) {
            var type = parsePipelineType(typeParam);
            Class<? extends CVPipelineSettings> cls = type == null ? null : settingsClassFor(type);
            if (cls == null) {
                error(ctx, 400, "\"type\" must be one of " + pipelineTypeNames());
                return;
            }
            try {
                var defaults = cls.getDeclaredConstructor().newInstance();
                var out = describeSettings(defaults);
                out.put("note", "values shown are defaults for a new " + type.name() + " pipeline");
                json(ctx, out);
            } catch (ReflectiveOperationException e) {
                error(ctx, 500, "Could not instantiate " + cls.getSimpleName() + ": " + e);
            }
            return;
        }
        var module = findModule(ctx.queryParam("camera"));
        if (module.isEmpty()) {
            badCamera(ctx, ctx.queryParam("camera"));
            return;
        }
        var out = describeSettings(module.get().getCurrentPipelineSettings());
        out.put("camera", module.get().uniqueName());
        json(ctx, out);
    }

    private static LinkedHashMap<String, Object> describeSettings(Object settings) {
        var fields = new ArrayList<Map<String, Object>>();
        for (var f : settings.getClass().getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            var entry = new LinkedHashMap<String, Object>();
            entry.put("name", f.getName());
            entry.put("type", f.getType().getSimpleName());
            entry.put("declaredIn", f.getDeclaringClass().getSimpleName());
            try {
                entry.put("value", f.get(settings));
            } catch (IllegalAccessException e) {
                entry.put("value", null);
            }
            if (f.getType().isEnum()) {
                entry.put(
                        "options",
                        Arrays.stream(f.getType().getEnumConstants()).map(Object::toString).toList());
            } else if (f.getType().getSimpleName().endsWith("Couple")) {
                entry.put("format", "[low, high]");
            }
            if (f.getName().startsWith("camera")) {
                entry.put("note", "also applied to the physical camera");
            }
            fields.add(entry);
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("settingsClass", settings.getClass().getSimpleName());
        if (settings instanceof CVPipelineSettings cv) out.put("pipelineIndex", cv.pipelineIndex);
        out.put("fields", fields);
        return out;
    }

    private static Class<? extends CVPipelineSettings> settingsClassFor(PipelineType type) {
        return switch (type) {
            case Reflective -> ReflectivePipelineSettings.class;
            case ColoredShape -> ColoredShapePipelineSettings.class;
            case AprilTag -> AprilTagPipelineSettings.class;
            case Aruco -> ArucoPipelineSettings.class;
            case ObjectDetection -> ObjectDetectionPipelineSettings.class;
            case DriverMode -> DriverModePipelineSettings.class;
            default -> null;
        };
    }

    public static void onResults(Context ctx) {
        var all = UIDataPublisher.getLatestResults();
        var cam = ctx.queryParam("camera");
        if (cam != null) {
            var module = findModule(cam);
            if (module.isEmpty()) {
                badCamera(ctx, cam);
                return;
            }
            json(ctx, all.getOrDefault(module.get().uniqueName(), Map.of()));
            return;
        }
        json(ctx, all);
    }

    public static void onLogs(Context ctx) {
        int lines = 200;
        try {
            var q = ctx.queryParam("lines");
            if (q != null) lines = Integer.parseInt(q);
        } catch (NumberFormatException ignored) {
        }
        var logs = Logger.getRecentLogs(lines, ctx.queryParam("contains"));
        ctx.contentType("text/plain").result(String.join("\n", logs));
    }

    // ---------------------------------------------------------------- writes

    public static void onPipelineSettings(Context ctx) {
        withCamera(
                ctx,
                (body, module) -> {
                    if (!(body.get("settings") instanceof Map<?, ?> settingsMap) || settingsMap.isEmpty()) {
                        error(ctx, 400, "Body needs a non-empty \"settings\" object");
                        return;
                    }
                    var current = module.getCurrentPipelineSettings();
                    if (body.get("pipelineIndex") instanceof Number idx
                            && idx.intValue() != current.pipelineIndex) {
                        error(
                                ctx,
                                400,
                                "pipelineIndex "
                                        + idx
                                        + " is not active (active is "
                                        + current.pipelineIndex
                                        + "). POST /api/agent/pipeline/select first.");
                        return;
                    }
                    // Validate everything before applying anything
                    var converted = new LinkedHashMap<String, Object>();
                    for (var e : settingsMap.entrySet()) {
                        var key = String.valueOf(e.getKey());
                        java.lang.reflect.Field field;
                        try {
                            field = current.getClass().getField(key);
                        } catch (NoSuchFieldException ex) {
                            error(
                                    ctx,
                                    400,
                                    "Unknown setting '"
                                            + key
                                            + "' for "
                                            + current.getClass().getSimpleName()
                                            + ". See GET /api/agent/schema?camera="
                                            + module.uniqueName());
                            return;
                        }
                        try {
                            converted.put(key, convert(field.getType(), e.getValue()));
                        } catch (IllegalArgumentException ex) {
                            error(ctx, 400, "Bad value for '" + key + "': " + ex.getMessage());
                            return;
                        }
                    }
                    for (var e : converted.entrySet()) {
                        logger.info(
                                "Agent API: set " + module.uniqueName() + " " + e.getKey() + " = " + e.getValue());
                        publish(module, e.getKey(), e.getValue(), DataChangeDestination.DCD_ACTIVEPIPELINESETTINGS);
                    }
                    ok(ctx, Map.<String, Object>of("applied", converted.keySet()));
                });
    }

    public static void onPipelineSelect(Context ctx) {
        withCamera(
                ctx,
                (body, module) -> {
                    if (!(body.get("index") instanceof Number idx)) {
                        error(ctx, 400, "Body needs numeric \"index\"");
                        return;
                    }
                    publish(module, "changePipeline", idx.intValue(), DataChangeDestination.DCD_ACTIVEMODULE);
                    ok(ctx, Map.<String, Object>of("index", idx.intValue()));
                });
    }

    public static void onPipelineAdd(Context ctx) {
        withCamera(
                ctx,
                (body, module) -> {
                    var type = parsePipelineType(body.get("type"));
                    if (type == null || type.baseIndex < 0) {
                        error(ctx, 400, "\"type\" must be one of " + pipelineTypeNames());
                        return;
                    }
                    var name = body.get("name") instanceof String s ? s : type.name();
                    publish(
                            module,
                            "newPipelineInfo",
                            Pair.of(name, type),
                            DataChangeDestination.DCD_ACTIVEMODULE);
                    ok(ctx, Map.<String, Object>of("name", name, "type", type.name()));
                });
    }

    public static void onPipelineDuplicate(Context ctx) {
        withCamera(
                ctx,
                (body, module) -> {
                    if (!(body.get("index") instanceof Number idx)) {
                        error(ctx, 400, "Body needs numeric \"index\"");
                        return;
                    }
                    publish(
                            module, "duplicatePipeline", idx.intValue(), DataChangeDestination.DCD_ACTIVEMODULE);
                    ok(ctx, Map.<String, Object>of("duplicated", idx.intValue()));
                });
    }

    public static void onPipelineDelete(Context ctx) {
        withCamera(
                ctx,
                (body, module) -> {
                    publish(module, "deleteCurrPipeline", 0, DataChangeDestination.DCD_ACTIVEMODULE);
                    ok(ctx, Map.<String, Object>of("deleted", "active"));
                });
    }

    public static void onPipelineRename(Context ctx) {
        withCamera(
                ctx,
                (body, module) -> {
                    if (!(body.get("name") instanceof String name) || name.isBlank()) {
                        error(ctx, 400, "Body needs \"name\"");
                        return;
                    }
                    publish(module, "pipelineName", name, DataChangeDestination.DCD_ACTIVEMODULE);
                    ok(ctx, Map.<String, Object>of("name", name));
                });
    }

    public static void onPipelineType(Context ctx) {
        withCamera(
                ctx,
                (body, module) -> {
                    var type = parsePipelineType(body.get("type"));
                    if (type == null || type.baseIndex < 0) {
                        error(ctx, 400, "\"type\" must be one of " + pipelineTypeNames());
                        return;
                    }
                    publish(
                            module, "changePipelineType", type.baseIndex, DataChangeDestination.DCD_ACTIVEMODULE);
                    ok(ctx, Map.<String, Object>of("type", type.name()));
                });
    }

    public static void onDriverMode(Context ctx) {
        withCamera(
                ctx,
                (body, module) -> {
                    if (!(body.get("enabled") instanceof Boolean enabled)) {
                        error(ctx, 400, "Body needs boolean \"enabled\"");
                        return;
                    }
                    publish(module, "isDriverMode", enabled, DataChangeDestination.DCD_ACTIVEMODULE);
                    ok(ctx, Map.<String, Object>of("driverMode", enabled));
                });
    }

    public static void onSnapshot(Context ctx) {
        withCamera(
                ctx,
                (body, module) -> {
                    var kind = body.get("kind") instanceof String s ? s : "output";
                    String prop;
                    if (kind.equalsIgnoreCase("input")) prop = "saveInputSnapshot";
                    else if (kind.equalsIgnoreCase("output")) prop = "saveOutputSnapshot";
                    else {
                        error(ctx, 400, "\"kind\" must be input or output");
                        return;
                    }
                    publish(module, prop, 0, DataChangeDestination.DCD_ACTIVEMODULE);
                    ok(ctx, Map.<String, Object>of("requested", prop, "fetch", "/api/utils/getImageSnapshots"));
                });
    }

    // ---------------------------------------------------------------- helpers

    private interface CameraAction {
        void run(Map<String, Object> body, VisionModule module);
    }

    @SuppressWarnings("unchecked")
    private static void withCamera(Context ctx, CameraAction action) {
        Map<String, Object> body;
        try {
            var parsed = Jsonb.instance().type(Object.class).fromJson(ctx.body());
            if (!(parsed instanceof Map)) {
                error(ctx, 400, "Body must be a JSON object");
                return;
            }
            body = (Map<String, Object>) parsed;
        } catch (Exception e) {
            error(ctx, 400, "Invalid JSON: " + e.getMessage());
            return;
        }
        var cam = body.get("camera") instanceof String s ? s : null;
        var module = findModule(cam);
        if (module.isEmpty()) {
            badCamera(ctx, cam);
            return;
        }
        try {
            action.run(body, module.get());
        } catch (Exception e) {
            logger.error("Agent API request failed", e);
            error(ctx, 500, e.toString());
        }
    }

    private static <T> void publish(
            VisionModule module, String prop, T value, DataChangeDestination dest) {
        DataChangeService.getInstance()
                .publishEvent(new IncomingWebSocketEvent<>(dest, prop, value, module.uniqueName(), null));
    }

    /** Converts a JSON-decoded value into the shape VisionModuleChangeSubscriber.setProperty expects. */
    private static Object convert(Class<?> type, Object value) {
        if (value == null) throw new IllegalArgumentException("null not allowed");
        if (type.isEnum()) {
            var constants = type.getEnumConstants();
            if (value instanceof String s) {
                for (int i = 0; i < constants.length; i++) {
                    if (constants[i].toString().equalsIgnoreCase(s)
                            || ((Enum<?>) constants[i]).name().equalsIgnoreCase(s)) return i;
                }
                throw new IllegalArgumentException(
                        "expected one of " + Arrays.stream(constants).map(Object::toString).toList());
            }
            if (value instanceof Number n) {
                int i = n.intValue();
                if (i < 0 || i >= constants.length)
                    throw new IllegalArgumentException("ordinal out of range 0.." + (constants.length - 1));
                return i;
            }
            throw new IllegalArgumentException("expected enum name or ordinal");
        }
        if (type == int.class || type == Integer.class) {
            if (!(value instanceof Number n)) throw new IllegalArgumentException("expected integer");
            return n.intValue();
        }
        if (type == double.class || type == Double.class) {
            if (!(value instanceof Number n)) throw new IllegalArgumentException("expected number");
            return n.doubleValue();
        }
        if (type == boolean.class || type == Boolean.class) {
            if (value instanceof Boolean) return value;
            if (value instanceof Number n) return n.intValue() != 0;
            throw new IllegalArgumentException("expected boolean");
        }
        if (value instanceof List<?> l) {
            // DoubleCouple / IntegerCouple and similar expect an ArrayList<Number>
            return new ArrayList<>(l);
        }
        if (value instanceof Map<?, ?> m) {
            // ModelProperties etc. expect a LinkedHashMap
            return new LinkedHashMap<>(m);
        }
        return value;
    }

    private static PipelineType parsePipelineType(Object v) {
        if (v instanceof String s) {
            for (var t : PipelineType.values()) if (t.name().equalsIgnoreCase(s)) return t;
        } else if (v instanceof Number n) {
            for (var t : PipelineType.values()) if (t.baseIndex == n.intValue()) return t;
        }
        return null;
    }

    private static List<String> pipelineTypeNames() {
        return Arrays.stream(PipelineType.values())
                .filter(t -> t.baseIndex >= 0)
                .map(Enum::name)
                .toList();
    }

    private static List<VisionModule> modules() {
        return VisionSourceManager.getInstance().getVisionModules();
    }

    private static Optional<VisionModule> findModule(String cam) {
        var mods = modules();
        if (cam == null) {
            return mods.size() == 1 ? Optional.of(mods.get(0)) : Optional.empty();
        }
        return mods.stream()
                .filter(
                        m ->
                                m.uniqueName().equals(cam)
                                        || cam.equalsIgnoreCase(m.getCameraConfiguration().nickname))
                .findFirst();
    }

    private static void badCamera(Context ctx, String cam) {
        var names =
                modules().stream()
                        .map(m -> m.uniqueName() + " (" + m.getCameraConfiguration().nickname + ")")
                        .toList();
        error(
                ctx,
                404,
                (cam == null ? "\"camera\" is required" : "No camera '" + cam + "'")
                        + ". Cameras: "
                        + names);
    }

    private static void json(Context ctx, Object o) {
        ctx.contentType("application/json").result(Jsonb.instance().toJson(o));
    }

    private static void ok(Context ctx, Map<String, Object> extra) {
        var out = new LinkedHashMap<String, Object>();
        out.put("ok", true);
        out.putAll(extra);
        ctx.status(200);
        json(ctx, out);
    }

    private static void error(Context ctx, int status, String msg) {
        logger.warn("Agent API " + status + ": " + msg);
        ctx.status(status);
        json(ctx, Map.of("ok", false, "error", msg));
    }
}
