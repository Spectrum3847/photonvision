#!/usr/bin/env python3
"""Minimal MCP (stdio) server wrapping PhotonVision's /api/agent HTTP API.

Standard library only. Point it at a coprocessor with PHOTONVISION_URL
(default http://photonvision.local:5800). Registered for Claude Code in /.mcp.json.
"""

import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

BASE = os.environ.get("PHOTONVISION_URL", "http://photonvision.local:5800").rstrip("/")
TIMEOUT = float(os.environ.get("PHOTONVISION_TIMEOUT", "10"))
PROTOCOL_VERSION = "2025-06-18"

CAM = {"type": "string", "description": "Camera uniqueName or nickname. Optional if only one camera."}


def _obj(props, required=()):
    return {"type": "object", "properties": props, "required": list(required)}


# name -> (description, input schema, method, path, query-param keys)
TOOLS = {
    "get_state": ("Full PhotonVision config: network/general settings, every camera, and every pipeline's settings.",
                  _obj({}), "GET", "/api/agent/state", ()),
    "get_schema": ("List the settings of a camera's ACTIVE pipeline (name, type, current value, enum options), "
                   "or the defaults for a pipeline type. Call before set_pipeline_settings.",
                   _obj({"camera": CAM, "type": {"type": "string", "description": "Pipeline type instead of camera, e.g. AprilTag"}}),
                   "GET", "/api/agent/schema", ("camera", "type")),
    "get_results": ("Latest vision results per camera: targets, fps, latency, multitag pose.",
                    _obj({"camera": CAM}), "GET", "/api/agent/results", ("camera",)),
    "get_logs": ("Recent PhotonVision log lines.",
                 _obj({"lines": {"type": "integer", "default": 200},
                       "contains": {"type": "string", "description": "Only lines containing this text"}}),
                 "GET", "/api/agent/logs", ("lines", "contains")),
    "set_pipeline_settings": ("Change settings on a camera's ACTIVE pipeline. Keys must come from get_schema. "
                              "Enums accept names or ordinals; couples are [low, high]. Applies on next frame.",
                              _obj({"camera": CAM, "settings": {"type": "object"},
                                    "pipelineIndex": {"type": "integer", "description": "Optional guard: fail if this isn't the active pipeline"}},
                                   ["settings"]),
                              "POST", "/api/agent/pipeline/settings", ()),
    "select_pipeline": ("Make pipeline <index> active on a camera.",
                        _obj({"camera": CAM, "index": {"type": "integer"}}, ["index"]),
                        "POST", "/api/agent/pipeline/select", ()),
    "add_pipeline": ("Create a pipeline.",
                     _obj({"camera": CAM, "name": {"type": "string"},
                           "type": {"type": "string", "enum": ["Reflective", "ColoredShape", "AprilTag", "Aruco", "ObjectDetection"]}},
                          ["type"]),
                     "POST", "/api/agent/pipeline/add", ()),
    "duplicate_pipeline": ("Duplicate pipeline <index>.",
                           _obj({"camera": CAM, "index": {"type": "integer"}}, ["index"]),
                           "POST", "/api/agent/pipeline/duplicate", ()),
    "delete_active_pipeline": ("Delete the camera's ACTIVE pipeline.",
                               _obj({"camera": CAM}), "POST", "/api/agent/pipeline/delete", ()),
    "rename_active_pipeline": ("Rename the camera's ACTIVE pipeline.",
                               _obj({"camera": CAM, "name": {"type": "string"}}, ["name"]),
                               "POST", "/api/agent/pipeline/rename", ()),
    "change_pipeline_type": ("Change the ACTIVE pipeline's type (resets its settings).",
                             _obj({"camera": CAM, "type": {"type": "string"}}, ["type"]),
                             "POST", "/api/agent/pipeline/type", ()),
    "set_driver_mode": ("Enable/disable driver mode on a camera.",
                        _obj({"camera": CAM, "enabled": {"type": "boolean"}}, ["enabled"]),
                        "POST", "/api/agent/drivermode", ()),
    "save_snapshot": ("Save an input or output frame; retrieve with /api/utils/getImageSnapshots.",
                      _obj({"camera": CAM, "kind": {"type": "string", "enum": ["input", "output"]}}),
                      "POST", "/api/agent/snapshot", ()),
}


def call_api(method, path, args, query_keys):
    url = BASE + path
    data = None
    if method == "GET":
        q = {k: args[k] for k in query_keys if args.get(k) is not None}
        if q:
            url += "?" + urllib.parse.urlencode(q)
    else:
        data = json.dumps(args).encode()
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            return r.read().decode(errors="replace"), False
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}: {e.read().decode(errors='replace')}", True
    except Exception as e:  # noqa: BLE001
        return f"Could not reach PhotonVision at {BASE}: {e}", True


def handle(msg):
    method, mid = msg.get("method"), msg.get("id")
    if method == "initialize":
        result = {"protocolVersion": msg.get("params", {}).get("protocolVersion", PROTOCOL_VERSION),
                  "capabilities": {"tools": {}},
                  "serverInfo": {"name": "photonvision", "version": "0.1.0"},
                  "instructions": "Control a PhotonVision coprocessor. Typical loop: get_state -> get_schema -> "
                                  "set_pipeline_settings -> get_results / get_logs to verify."}
    elif method == "tools/list":
        result = {"tools": [{"name": n, "description": d, "inputSchema": s} for n, (d, s, *_rest) in TOOLS.items()]}
    elif method == "tools/call":
        name = msg["params"]["name"]
        args = msg["params"].get("arguments") or {}
        if name not in TOOLS:
            result = {"content": [{"type": "text", "text": f"Unknown tool {name}"}], "isError": True}
        else:
            _, _, http_method, path, qk = TOOLS[name]
            text, err = call_api(http_method, path, args, qk)
            result = {"content": [{"type": "text", "text": text}], "isError": err}
    elif method == "ping":
        result = {}
    elif mid is None:
        return None  # notification
    else:
        return {"jsonrpc": "2.0", "id": mid, "error": {"code": -32601, "message": f"Method not found: {method}"}}
    return None if mid is None else {"jsonrpc": "2.0", "id": mid, "result": result}


def main():
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            resp = handle(json.loads(line))
        except Exception as e:  # noqa: BLE001
            resp = {"jsonrpc": "2.0", "id": None, "error": {"code": -32603, "message": str(e)}}
        if resp is not None:
            sys.stdout.write(json.dumps(resp) + "\n")
            sys.stdout.flush()


if __name__ == "__main__":
    main()
