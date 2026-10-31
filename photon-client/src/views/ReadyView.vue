<script setup lang="ts">
// SpectrumJetson (photonvision-52): Match ready. One screen before a match: every camera live (from
// the results the dashboard already receives), the robot as PhotonVision sees it, and the same
// PASS / WARN / FAIL checks as scripts/jetson/health-check.sh, which this runs (/api/healthCheck).
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import axios from "axios";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import { useStateStore } from "@/stores/StateStore";
import { resolutionsAreEqual } from "@/lib/PhotonUtils";
import { PipelineType } from "@/types/PipelineTypes";
import { loadRobotState, robotState } from "@/lib/RobotState";
import { loadTagContrast, tagContrast } from "@/lib/TagContrast";

interface CheckItem {
  level: "PASS" | "WARN" | "FAIL" | "INFO";
  text: string;
}
interface HealthCheck {
  verdict: string;
  sections: { name: string; items: CheckItem[] }[];
  seconds: number;
  ranAt: number;
}

const health = ref<HealthCheck | null>(null);
const checking = ref(false);
let checkTimer: ReturnType<typeof setInterval> | undefined;
let robotTimer: ReturnType<typeof setInterval> | undefined;

const runChecks = async () => {
  if (checking.value) return;
  checking.value = true;
  try {
    health.value = (await axios.get<HealthCheck>("/healthCheck", { timeout: 70_000 })).data;
  } catch (e) {
    health.value = {
      verdict: `NOT READY: couldn't run the checks (${e instanceof Error ? e.message : String(e)})`,
      sections: [],
      seconds: 0,
      ranAt: Date.now()
    };
  } finally {
    checking.value = false;
  }
};

onMounted(() => {
  void runChecks();
  checkTimer = setInterval(() => void runChecks(), 30_000);
  void loadRobotState();
  void loadTagContrast();
  robotTimer = setInterval(() => {
    void loadRobotState();
    void loadTagContrast();
  }, 2000);
});
onBeforeUnmount(() => {
  clearInterval(checkTimer);
  clearInterval(robotTimer);
});

const verdictColor = computed(() => {
  const v = health.value?.verdict ?? "";
  if (v.startsWith("NOT READY")) return "error";
  if (/warning/.test(v)) return "warning";
  return v.startsWith("READY") ? "success" : "grey";
});
const attention = computed(() =>
  (health.value?.sections ?? []).flatMap((s) =>
    s.items.filter((i) => i.level === "FAIL" || i.level === "WARN").map((i) => ({ ...i, section: s.name }))
  )
);
const ranAgo = computed(() => {
  if (!health.value) return "";
  const s = Math.round((Date.now() - health.value.ranAt) / 1000);
  return s < 5 ? "just now" : `${s} s ago`;
});

const typeNames: Record<number, string> = {
  [PipelineType.Reflective]: "Reflective",
  [PipelineType.ColoredShape]: "Colored shape",
  [PipelineType.AprilTag]: "AprilTag",
  [PipelineType.Aruco]: "ArUco",
  [PipelineType.ObjectDetection]: "Object detection",
  [PipelineType.AprilTagCuda]: "AprilTag (GPU)",
  [PipelineType.DriverMode]: "Driver mode"
};

interface Tile {
  id: string;
  name: string;
  connected: boolean;
  pipeline: string;
  type: string;
  fps: number;
  targetFps: number;
  latency: number;
  targets: number;
  calibrated: boolean | null;
  problems: string[];
}
const tiles = computed<Tile[]>(() =>
  Object.entries(useCameraSettingsStore().cameras).map(([id, c]) => {
    const result = useStateStore().backendResults[id];
    const index = c.currentPipelineIndex;
    const driverMode = index === -1;
    const format = c.validVideoFormats?.[c.pipelineSettings?.cameraVideoModeIndex];
    const type = c.pipelineSettings?.pipelineType;
    const needs3d = type === PipelineType.AprilTag || type === PipelineType.AprilTagCuda || type === PipelineType.Aruco;
    const calibrated = needs3d
      ? (c.completeCalibrations ?? []).some((cal) => resolutionsAreEqual(cal.resolution, format?.resolution))
      : null;
    const fps = result?.fps ?? 0;
    const targetFps = format?.fps ?? 0;
    const problems: string[] = [];
    if (!c.isConnected) problems.push("not connected");
    else if (targetFps && fps < targetFps * 0.8 && !robotState.value?.idleNow) problems.push(`${Math.round(fps)} fps`);
    if (calibrated === false)
      problems.push(`not calibrated at ${format?.resolution.width}x${format?.resolution.height}`);
    if (driverMode) problems.push("driver mode");
    // photonvision-54: how its tags look, when one is close enough to measure.
    const tc = tagContrast.value[c.nickname];
    if (tc && tc.verdict !== "good" && tc.verdict !== "no tags") problems.push(`tags: ${tc.verdict}`);
    return {
      id,
      name: c.nickname,
      connected: c.isConnected,
      pipeline: driverMode ? "Driver mode" : `${index}: ${c.pipelineNicknames?.[index] ?? "?"}`,
      type: driverMode ? "Driver mode" : (typeNames[type as number] ?? "?"),
      fps,
      targetFps,
      latency: result?.latency ?? 0,
      targets: result?.targets?.length ?? 0,
      calibrated,
      problems
    };
  })
);

const robotLine = computed(() => {
  const r = robotState.value;
  if (!r) return "Robot state unknown";
  if (!r.robotConnected) return "No robot connected";
  const mode = r.enabled ? (r.autonomous ? "enabled (auto)" : "enabled") : "disabled";
  return `Robot ${mode}${r.fmsAttached ? ", field (FMS) connected" : ""}${r.idleNow ? `, cameras idling at ${r.idleFps} fps` : ""}`;
});

const levelIcon: Record<string, string> = {
  PASS: "mdi-check-circle",
  WARN: "mdi-alert",
  FAIL: "mdi-close-octagon",
  INFO: "mdi-information-outline"
};
const levelColor: Record<string, string> = { PASS: "success", WARN: "warning", FAIL: "error", INFO: "grey" };
const openSections = ref<number[]>([]);
</script>

<template>
  <div class="pa-3">
    <v-card class="mb-3 rounded-12" color="surface">
      <v-card-text class="d-flex align-center flex-wrap ga-3">
        <v-chip
          data-testid="ready-verdict"
          :color="verdictColor"
          size="x-large"
          label
          variant="flat"
          class="font-weight-bold"
        >
          {{ health ? health.verdict : "Checking..." }}
        </v-chip>
        <span class="text-body-2">
          {{ robotLine }}
        </span>
        <v-spacer />
        <span v-if="health" class="text-caption">Checked {{ ranAgo }} ({{ health.seconds.toFixed(1) }} s)</span>
        <v-btn :loading="checking" color="buttonActive" variant="tonal" data-testid="ready-recheck" @click="runChecks">
          Check again
        </v-btn>
      </v-card-text>
      <v-card-text v-if="attention.length" class="pt-0" data-testid="ready-attention">
        <div v-for="(a, i) in attention" :key="i" class="d-flex align-start py-1">
          <v-icon :color="levelColor[a.level]" size="small" class="mr-2 mt-1">{{ levelIcon[a.level] }}</v-icon>
          <span
            ><b>{{ a.section }}:</b> {{ a.text }}</span
          >
        </div>
      </v-card-text>
    </v-card>

    <v-row dense class="mb-3">
      <v-col v-for="t in tiles" :key="t.id" cols="12" sm="6" md="4" lg="3">
        <v-card class="rounded-12 h-100" color="surface" :data-testid="`ready-camera-${t.name}`">
          <v-card-title class="d-flex align-center pb-0">
            <v-icon :color="!t.connected ? 'error' : t.problems.length ? 'warning' : 'success'" class="mr-2">
              {{ t.connected ? "mdi-camera" : "mdi-camera-off" }}
            </v-icon>
            {{ t.name }}
          </v-card-title>
          <v-card-text class="pt-1">
            <div>
              {{ t.pipeline }} <span class="text-caption">({{ t.type }})</span>
            </div>
            <div>
              <b>{{ Math.round(t.fps) }}</b> fps<span v-if="t.targetFps"> of {{ t.targetFps }}</span
              >, {{ Math.round(t.latency) }} ms latency
            </div>
            <div>
              {{ t.targets }} target{{ t.targets === 1 ? "" : "s" }} in view<span v-if="t.calibrated !== null"
                >, {{ t.calibrated ? "calibrated" : "not calibrated" }}</span
              >
            </div>
            <div v-if="tagContrast[t.name] && tagContrast[t.name].verdict !== 'no tags'" class="text-caption">
              Tag contrast {{ tagContrast[t.name].contrast }} (white {{ tagContrast[t.name].white }}, black
              {{ tagContrast[t.name].black }}, {{ tagContrast[t.name].clipped }}% clipped)
            </div>
            <div v-if="t.problems.length" class="text-warning pt-1">{{ t.problems.join(", ") }}</div>
          </v-card-text>
        </v-card>
      </v-col>
    </v-row>

    <v-card v-if="health?.sections.length" class="rounded-12" color="surface">
      <v-card-title>All checks</v-card-title>
      <v-expansion-panels v-model="openSections" multiple variant="accordion">
        <v-expansion-panel v-for="(s, i) in health.sections" :key="s.name" :value="i">
          <v-expansion-panel-title>
            <v-icon
              class="mr-2"
              :color="
                s.items.some((x) => x.level === 'FAIL')
                  ? 'error'
                  : s.items.some((x) => x.level === 'WARN')
                    ? 'warning'
                    : 'success'
              "
            >
              {{
                s.items.some((x) => x.level === "FAIL")
                  ? levelIcon.FAIL
                  : s.items.some((x) => x.level === "WARN")
                    ? levelIcon.WARN
                    : levelIcon.PASS
              }}
            </v-icon>
            {{ s.name }}
            <span class="text-caption ml-2"
              >{{ s.items.filter((x) => x.level === "PASS").length }} pass<template
                v-if="s.items.some((x) => x.level === 'WARN' || x.level === 'FAIL')"
                >, {{ s.items.filter((x) => x.level === "WARN" || x.level === "FAIL").length }} to look at</template
              ></span
            >
          </v-expansion-panel-title>
          <v-expansion-panel-text>
            <div v-for="(item, j) in s.items" :key="j" class="d-flex align-start py-1">
              <v-icon :color="levelColor[item.level]" size="small" class="mr-2 mt-1">{{
                levelIcon[item.level]
              }}</v-icon>
              <span :class="item.level === 'INFO' ? 'text-caption' : ''">{{ item.text }}</span>
            </div>
          </v-expansion-panel-text>
        </v-expansion-panel>
      </v-expansion-panels>
    </v-card>
  </div>
</template>
