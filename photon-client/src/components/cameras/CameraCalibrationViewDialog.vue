<script setup lang="ts">
// Spectrum (photonvision-91): a full-screen calibration view, opened from the calibration card while
// calibrating. The camera's own image at the calibration resolution (GET /api/calibration/frame),
// with the board in view, a 10x8 coverage grid, and a ghost board showing where to put it next.
// Auto-capture takes a snapshot when the board is still and adds coverage. Solve grades the result
// (rms, coverage, fx spread, outliers, lens-model fold) against the gate before saving.
// Targets: SpectrumJetson tests/calibration-analysis RESULTS.md, "Recalibrate".
import axios from "axios";
import { computed, onBeforeUnmount, ref, useTemplateRef, watch } from "vue";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import { useStateStore } from "@/stores/StateStore";

interface CoverageSummary {
  cols: number;
  rows: number;
  counts: number[];
  snapshots: number[];
  blocks: number[];
  borderPx: number[];
  cellsMet: number;
  emptyCells: number;
  fraction: number;
  blocksMet: boolean;
  borderMet: boolean;
  complete: boolean;
  nextCell: number;
  snapshotCount: number;
  cellCorners: number;
  cellSnapshots: number;
  blockCorners: number;
  borderTargetPx: number;
}
interface SnapshotStats {
  corners: number;
  distanceM: number;
  tiltDeg: number;
  centreX: number;
  centreY: number;
  gain: number;
  auto: boolean;
}
interface Guidance {
  cell: number;
  x: number;
  y: number;
  w: number;
  h: number;
  tiltAxis: string;
  tiltDeg: number;
  distanceM: number;
  text: string;
}
interface Live {
  width: number;
  height: number;
  corners: number[];
  motionPx: number;
  still: boolean;
  gain: number;
  distanceM: number;
  tiltDeg: number;
  autoCalibrate: boolean;
  calibrating: boolean;
  ageMs: number;
  session: {
    coverage: CoverageSummary;
    snapshots: SnapshotStats[];
    far: number;
    farTarget: number;
    tilted: number;
    tiltedTarget: number;
    minDistanceM: number;
    maxDistanceM: number;
    guidance: Guidance;
  };
}
interface Check {
  name: string;
  pass: boolean;
  value: string;
  hint: string;
}
interface Quality {
  pass: boolean;
  checks: Check[];
  rms: number;
  inliers: number;
  outliers: number;
  outlierPositions: { snapshot: number; x: number; y: number; error: number }[];
  snapshotRms: number[];
  fx: number;
  fy: number;
  cx: number;
  cy: number;
  fxSigma: number;
  distortion: number[];
  fold: { folds: boolean; cornerRadiusPx: number; maxRadiusPx: number };
  coverage: CoverageSummary;
  solver: string;
  uncertainty: { x: number; y: number; z: number }[] | null;
}

const open = defineModel<boolean>({ default: false });
const camera = computed(() => useCameraSettingsStore().currentCameraSettings.uniqueName);
const live = ref<Live | null>(null);
const quality = ref<Quality | null>(null);
const solving = ref(false);
const saving = ref(false);
const saveAnyway = ref(false);
const showNumbers = ref(false);
const showOutliers = ref(true);
const showUncertainty = ref(false);
const error = ref("");
const canvas = useTemplateRef<HTMLCanvasElement>("canvas");
let image: ImageBitmap | null = null;
let running = false;

const session = computed(() => live.value?.session ?? null);
const coverage = computed(() => session.value?.coverage ?? null);
const snapshots = computed(() => session.value?.snapshots ?? []);
// The grade belongs to the snapshots it was solved from
const qualityStale = computed(
  () => quality.value !== null && quality.value.snapshotRms.length !== snapshots.value.length
);

const autoCalibrate = computed<boolean>({
  get: () => live.value?.autoCalibrate ?? useStateStore().calibrationData.autoCalibrate,
  set: (v) => {
    useCameraSettingsStore().changeCurrentPipelineSetting({ autoCalibrate: v }, false);
    useStateStore().calibrationData.autoCalibrate = v;
    if (live.value) live.value.autoCalibrate = v;
  }
});
const keepEdgeCorners = ref(false); // off by default (not yet validated on the bench)
watch(keepEdgeCorners, (v) => useCameraSettingsStore().changeCurrentPipelineSetting({ keepEdgeCorners: v }, false));

// Cell tint: red when empty, amber while short, green at target.
const cellShare = (c: CoverageSummary, i: number) =>
  Math.min(1, c.counts[i] / c.cellCorners, c.snapshots[i] / c.cellSnapshots);
const cellColour = (share: number, count: number) => {
  if (count === 0) return "rgba(244, 67, 54, 0.30)";
  if (share >= 1) return "rgba(76, 175, 80, 0.12)";
  return `rgba(255, 193, 7, ${0.32 - 0.2 * share})`;
};
const uncertaintyColour = (u: number) =>
  u <= 0.3 ? "rgba(76, 175, 80, 0.35)" : u <= 0.6 ? "rgba(255, 193, 7, 0.45)" : "rgba(244, 67, 54, 0.5)";

const draw = () => {
  const c = canvas.value;
  const l = live.value;
  if (!c) return;
  const rect = c.getBoundingClientRect();
  c.width = Math.round(rect.width * devicePixelRatio);
  c.height = Math.round(rect.height * devicePixelRatio);
  const ctx = c.getContext("2d")!;
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, c.width, c.height);
  const iw = l?.width ?? image?.width ?? 0;
  const ih = l?.height ?? image?.height ?? 0;
  if (!iw || !ih) return;
  const s = Math.min(c.width / iw, c.height / ih);
  const w = iw * s,
    h = ih * s;
  const x0 = (c.width - w) / 2,
    y0 = (c.height - h) / 2;
  const X = (x: number) => x0 + x * s;
  const Y = (y: number) => y0 + y * s;
  if (image) ctx.drawImage(image, x0, y0, w, h);
  const line = Math.max(1, h / 500);

  // Uncertainty heatmap (mrcal's grid after a solve)
  const unc = quality.value?.uncertainty;
  if (showUncertainty.value && unc && unc.length) {
    const xs = [...new Set(unc.map((p) => p.x))].sort((a, b) => a - b);
    const ys = [...new Set(unc.map((p) => p.y))].sort((a, b) => a - b);
    const dx = xs.length > 1 ? xs[1] - xs[0] : iw;
    const dy = ys.length > 1 ? ys[1] - ys[0] : ih;
    unc.forEach((p) => {
      ctx.fillStyle = uncertaintyColour(p.z);
      ctx.fillRect(X(p.x - dx / 2), Y(p.y - dy / 2), dx * s + 1, dy * s + 1);
    });
  }

  // Coverage grid
  const cov = coverage.value;
  if (cov) {
    const cw = w / cov.cols,
      ch = h / cov.rows;
    ctx.font = `600 ${Math.round(ch / 4)}px sans-serif`;
    ctx.textAlign = "center";
    ctx.textBaseline = "middle";
    for (let i = 0; i < cov.cols * cov.rows; i++) {
      const cx = x0 + (i % cov.cols) * cw,
        cy = y0 + Math.floor(i / cov.cols) * ch;
      if (!showUncertainty.value) {
        ctx.fillStyle = cellColour(cellShare(cov, i), cov.counts[i]);
        ctx.fillRect(cx, cy, cw, ch);
      }
      ctx.strokeStyle = "rgba(255, 255, 255, 0.18)";
      ctx.lineWidth = line;
      ctx.strokeRect(cx, cy, cw, ch);
      if (showNumbers.value) {
        ctx.fillStyle = "rgba(255, 255, 255, 0.85)";
        ctx.fillText(`${cov.counts[i]}/${cov.snapshots[i]}`, cx + cw / 2, cy + ch / 2);
      }
    }
    // The outer ring, and the four 2x2 corner blocks (green once each has its corners)
    ctx.lineWidth = 3 * line;
    ctx.strokeStyle = "rgba(255, 255, 255, 0.55)";
    ctx.strokeRect(x0 + cw, y0 + ch, w - 2 * cw, h - 2 * ch);
    [
      [0, 0],
      [cov.cols - 2, 0],
      [0, cov.rows - 2],
      [cov.cols - 2, cov.rows - 2]
    ].forEach(([bc, br], b) => {
      ctx.strokeStyle = cov.blocks[b] >= cov.blockCorners ? "rgb(76, 175, 80)" : "rgb(255, 152, 0)";
      ctx.strokeRect(x0 + bc * cw, y0 + br * ch, 2 * cw, 2 * ch);
    });
  }

  // Where to put the board next: a dashed ghost, foreshortened on the tilt axis
  const g = session.value?.guidance;
  if (g && g.cell >= 0) {
    const gx = X(g.x),
      gy = Y(g.y),
      gw = (g.w * s) / 2,
      gh = (g.h * s) / 2;
    const k = g.tiltAxis ? Math.cos((g.tiltDeg * Math.PI) / 180) : 1;
    // x axis: top edge away (narrower, shorter); y axis: right side away
    let pts: [number, number][];
    if (g.tiltAxis === "x") {
      pts = [
        [gx - gw * (0.5 + 0.5 * k), gy - gh * k],
        [gx + gw * (0.5 + 0.5 * k), gy - gh * k],
        [gx + gw, gy + gh * k],
        [gx - gw, gy + gh * k]
      ];
    } else if (g.tiltAxis === "y") {
      pts = [
        [gx - gw * k, gy - gh],
        [gx + gw * k, gy - gh * (0.5 + 0.5 * k)],
        [gx + gw * k, gy + gh * (0.5 + 0.5 * k)],
        [gx - gw * k, gy + gh]
      ];
    } else {
      pts = [
        [gx - gw, gy - gh],
        [gx + gw, gy - gh],
        [gx + gw, gy + gh],
        [gx - gw, gy + gh]
      ];
    }
    ctx.save();
    ctx.beginPath();
    ctx.rect(x0, y0, w, h);
    ctx.clip();
    ctx.setLineDash([12 * line, 8 * line]);
    ctx.lineWidth = 4 * line;
    ctx.strokeStyle = "rgba(0, 229, 255, 0.95)";
    ctx.fillStyle = "rgba(0, 229, 255, 0.10)";
    ctx.beginPath();
    pts.forEach(([px, py], i) => (i ? ctx.lineTo(px, py) : ctx.moveTo(px, py)));
    ctx.closePath();
    ctx.fill();
    ctx.stroke();
    ctx.restore();
    if (cov) {
      const cw = w / cov.cols,
        ch = h / cov.rows;
      ctx.lineWidth = 4 * line;
      ctx.setLineDash([]);
      ctx.strokeStyle = "rgb(0, 229, 255)";
      ctx.strokeRect(x0 + (g.cell % cov.cols) * cw, y0 + Math.floor(g.cell / cov.cols) * ch, cw, ch);
    }
  }

  // Outliers from the last solve
  if (showOutliers.value && quality.value && !qualityStale.value) {
    ctx.strokeStyle = "rgb(255, 23, 68)";
    ctx.lineWidth = 2 * line;
    const r = 5 * line;
    quality.value.outlierPositions.forEach((o) => {
      ctx.beginPath();
      ctx.moveTo(X(o.x) - r, Y(o.y) - r);
      ctx.lineTo(X(o.x) + r, Y(o.y) + r);
      ctx.moveTo(X(o.x) + r, Y(o.y) - r);
      ctx.lineTo(X(o.x) - r, Y(o.y) + r);
      ctx.stroke();
    });
  }

  // The board in view: green when still, yellow while moving
  if (l && l.corners.length) {
    ctx.fillStyle = l.still ? "rgb(118, 255, 3)" : "rgb(255, 234, 0)";
    const r = Math.max(2, 3 * line);
    for (let i = 0; i + 1 < l.corners.length; i += 2) {
      ctx.beginPath();
      ctx.arc(X(l.corners[i]), Y(l.corners[i + 1]), r, 0, 2 * Math.PI);
      ctx.fill();
    }
  }
};

const errText = (e: unknown, fallback: string): string =>
  axios.isAxiosError(e) && typeof e.response?.data === "string" && e.response.data ? e.response.data : fallback;

const fetchQuality = async () => {
  try {
    const r = await axios.get(`/calibration/quality?camera=${encodeURIComponent(camera.value)}`);
    quality.value = r.status === 200 ? r.data : null;
  } catch {
    // none yet
  }
};

const tick = async () => {
  if (!running) return;
  const cam = encodeURIComponent(camera.value);
  try {
    const [r, f] = await Promise.all([
      axios.get(`/calibration/live?camera=${cam}`),
      axios.get(`/calibration/frame?camera=${cam}`, { responseType: "blob" })
    ]);
    if (r.status === 200) live.value = r.data;
    if (f.status === 200) {
      const next = await createImageBitmap(f.data as Blob);
      image?.close();
      image = next;
    }
    error.value = "";
    draw();
  } catch (e) {
    error.value = errText(e, "No connection to the camera");
  }
  if (running) setTimeout(() => void tick(), 150);
};

const takeSnapshot = () => useCameraSettingsStore().takeCalibrationSnapshot();

const solve = async () => {
  solving.value = true;
  try {
    const r = await axios.post("/calibration/solve", { cameraUniqueName: camera.value });
    quality.value = r.data;
    saveAnyway.value = false;
  } catch (e) {
    useStateStore().showSnackbarMessage({ color: "error", message: errText(e, "The solve failed") });
  } finally {
    solving.value = false;
    draw();
  }
};

const removeSnapshot = async (index: number) => {
  try {
    await axios.post("/calibration/removeSnapshot", { cameraUniqueName: camera.value, index });
  } catch (e) {
    useStateStore().showSnackbarMessage({ color: "error", message: errText(e, "Couldn't delete it") });
  }
};

const canSave = computed(
  () => !!quality.value && !qualityStale.value && (quality.value.pass || saveAnyway.value) && !saving.value
);
const save = async () => {
  saving.value = true;
  try {
    await useCameraSettingsStore().commitCalibration(camera.value);
    useStateStore().showSnackbarMessage({ color: "success", message: "Calibration saved" });
    open.value = false;
  } catch (e) {
    useStateStore().showSnackbarMessage({ color: "error", message: errText(e, "Saving failed") });
  } finally {
    saving.value = false;
  }
};

// The pending (last solved) calibration with every observation: per-corner residuals line up with
// the corners, outliers flagged, so tests/calibration-analysis can be re-run on it.
const exportJson = async () => {
  const l = live.value;
  if (!l) return;
  try {
    const r = await axios.get("/settings/camera/getCalibration", {
      params: { cameraUniqueName: camera.value, width: l.width, height: l.height }
    });
    const blob = new Blob([JSON.stringify(r.data, null, 1)], { type: "application/json" });
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = `calibration-${camera.value}-${l.width}x${l.height}.json`;
    a.click();
    URL.revokeObjectURL(a.href);
  } catch {
    useStateStore().showSnackbarMessage({ color: "error", message: "Solve first: there's no calibration to export" });
  }
};

const fmt = (v: number, d = 2) => (v < 0 ? "–" : v.toFixed(d));
const borderText = computed(() => {
  const b = coverage.value?.borderPx;
  if (!b) return "";
  return ["L", "T", "R", "B"].map((side, i) => `${side} ${b[i] < 0 ? "–" : Math.round(b[i])}`).join("  ");
});

watch(open, (v) => {
  running = v;
  if (v) {
    void fetchQuality();
    void tick();
  } else {
    image?.close();
    image = null;
    live.value = null;
  }
});
watch([showNumbers, showOutliers, showUncertainty], draw);
const onKey = (e: KeyboardEvent) => {
  if (!open.value) return;
  if (e.key === " ") {
    e.preventDefault();
    takeSnapshot();
  }
};
window.addEventListener("keydown", onKey);
window.addEventListener("resize", draw);
onBeforeUnmount(() => {
  running = false;
  window.removeEventListener("keydown", onKey);
  window.removeEventListener("resize", draw);
  image?.close();
});
</script>

<template>
  <v-dialog v-model="open" fullscreen>
    <v-card color="black" class="d-flex flex-column" style="height: 100%">
      <div class="d-flex align-center flex-wrap px-4 py-2 bar">
        <div class="mr-6">
          <div class="label">Snapshots</div>
          <div class="mid">{{ snapshots.length }}</div>
        </div>
        <div class="mr-6">
          <div class="label">Cells at target</div>
          <div class="mid">
            {{ coverage ? Math.round(100 * coverage.fraction) : 0 }}%
            <span class="small">({{ coverage?.emptyCells ?? 80 }} empty)</span>
          </div>
        </div>
        <div class="mr-6">
          <div class="label">Far ≥1.2 m / tilted 20-45°</div>
          <div class="mid">
            <span :class="session && session.far >= session.farTarget ? 'ok' : 'short'"
              >{{ session?.far ?? 0 }}/{{ session?.farTarget ?? 5 }}</span
            >
            ·
            <span :class="session && session.tilted >= session.tiltedTarget ? 'ok' : 'short'"
              >{{ session?.tilted ?? 0 }}/{{ session?.tiltedTarget ?? 8 }}</span
            >
          </div>
        </div>
        <div class="mr-6">
          <div class="label">Board now</div>
          <div class="small">
            <span :class="live?.still ? 'ok' : 'short'">{{
              !live || !live.corners.length ? "not seen" : live.still ? "still" : "moving"
            }}</span>
            <template v-if="live && live.corners.length">
              · {{ live.corners.length / 2 }} corners · {{ fmt(live.distanceM) }} m · {{ fmt(live.tiltDeg, 0) }}° · +{{
                live.gain
              }}
            </template>
          </div>
        </div>
        <v-spacer />
        <v-switch v-model="autoCalibrate" class="mr-4" color="primary" hide-details density="compact" label="Auto" />
        <v-btn class="mr-2" size="large" variant="tonal" @click="takeSnapshot">Snapshot (Space)</v-btn>
        <v-btn class="mr-2" size="large" variant="tonal" :loading="solving" :disabled="!snapshots.length" @click="solve"
          >Solve</v-btn
        >
        <v-btn size="large" variant="tonal" @click="open = false">Close (Esc)</v-btn>
      </div>
      <div class="guide px-4 py-1">
        <template v-if="error">{{ error }}</template>
        <template v-else>{{ session?.guidance.text ?? "Waiting for the camera…" }}</template>
      </div>
      <div class="d-flex flex-grow-1" style="min-height: 0">
        <canvas ref="canvas" class="flex-grow-1" style="width: 100%; min-width: 0"></canvas>
        <div class="side pa-3">
          <div class="d-flex flex-wrap">
            <v-checkbox v-model="showNumbers" hide-details density="compact" label="Counts" class="mr-2" />
            <v-checkbox v-model="showOutliers" hide-details density="compact" label="Outliers" class="mr-2" />
            <v-checkbox
              v-model="showUncertainty"
              hide-details
              density="compact"
              label="Uncertainty"
              :disabled="!quality?.uncertainty"
            />
          </div>
          <div class="small mb-2">Nearest corner to each edge (px, target ≤ 10): {{ borderText }}</div>
          <v-switch
            v-model="keepEdgeCorners"
            color="primary"
            hide-details
            density="compact"
            label="Keep edge corners mrcal rejects"
          />

          <div v-if="quality" class="mt-2">
            <v-alert v-if="quality.fold.folds" color="error" density="compact" variant="flat" class="mb-2">
              The lens model folds inside the image ({{ Math.round(quality.fold.maxRadiusPx) }} px from the centre; the
              corner is at {{ Math.round(quality.fold.cornerRadiusPx) }} px). Tags near the edge will be wrong.
            </v-alert>
            <v-alert v-if="qualityStale" color="warning" density="compact" variant="tonal" class="mb-2">
              Snapshots changed since this solve: solve again.
            </v-alert>
            <div class="small">{{ quality.solver }}</div>
            <table class="q">
              <tbody>
                <tr>
                  <td>rms (inliers)</td>
                  <td>{{ fmt(quality.rms, 3) }} px</td>
                </tr>
                <tr>
                  <td>fx / fy</td>
                  <td>
                    {{ quality.fx.toFixed(2) }} ± {{ quality.fxSigma < 0 ? "?" : (2 * quality.fxSigma).toFixed(2) }} /
                    {{ quality.fy.toFixed(2) }}
                  </td>
                </tr>
                <tr>
                  <td>cx / cy</td>
                  <td>{{ quality.cx.toFixed(1) }} / {{ quality.cy.toFixed(1) }}</td>
                </tr>
                <tr>
                  <td>corners</td>
                  <td>{{ quality.inliers }} used, {{ quality.outliers }} outliers</td>
                </tr>
                <tr>
                  <td>coverage</td>
                  <td>{{ Math.round(100 * quality.coverage.fraction) }}% of cells at target</td>
                </tr>
              </tbody>
            </table>
            <div v-for="c in quality.checks" :key="c.name" class="check" :class="c.pass ? 'pass' : 'fail'">
              <div>
                <v-icon size="small">{{ c.pass ? "mdi-check-circle" : "mdi-close-circle" }}</v-icon>
                {{ c.name }}: {{ c.value }}
              </div>
              <div v-if="!c.pass" class="hint">{{ c.hint }}</div>
            </div>
            <v-checkbox
              v-if="!quality.pass"
              v-model="saveAnyway"
              color="error"
              hide-details
              density="compact"
              label="Save anyway (fails the checks)"
            />
            <div class="d-flex mt-1">
              <v-btn
                class="mr-2 flex-grow-1"
                :color="quality.pass ? 'success' : 'error'"
                :disabled="!canSave"
                :loading="saving"
                @click="save"
                >Save</v-btn
              >
              <v-btn class="flex-grow-1" variant="tonal" @click="exportJson">Export JSON</v-btn>
            </div>
          </div>
          <div v-else class="small mt-2">Solve to see the quality and save.</div>

          <div class="label mt-4">Snapshots</div>
          <table class="snaps">
            <thead>
              <tr>
                <th>#</th>
                <th>corners</th>
                <th>m</th>
                <th>tilt</th>
                <th>added</th>
                <th>rms</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="(sn, i) in snapshots" :key="i">
                <td>{{ i + 1 }}{{ sn.auto ? "" : "*" }}</td>
                <td>{{ sn.corners }}</td>
                <td>{{ fmt(sn.distanceM) }}</td>
                <td>{{ fmt(sn.tiltDeg, 0) }}</td>
                <td>{{ sn.gain }}</td>
                <td
                  :class="{
                    short: !qualityStale && quality && quality.snapshotRms[i] > 0.25
                  }"
                >
                  {{ quality && !qualityStale ? fmt(quality.snapshotRms[i], 2) : "" }}
                </td>
                <td>
                  <v-btn icon="mdi-delete" size="x-small" variant="text" @click="removeSnapshot(i)" />
                </td>
              </tr>
            </tbody>
          </table>
          <div class="small">* taken by hand. "added": coverage it added when taken.</div>
        </div>
      </div>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.bar,
.side,
.guide {
  background: #151219;
  color: #fff;
}
.guide {
  font-size: 20px;
  font-weight: 600;
  color: #00e5ff;
}
.side {
  width: 380px;
  flex-shrink: 0;
  overflow-y: auto;
}
.label {
  font-size: 14px;
  opacity: 0.75;
}
.mid {
  font-size: 28px;
  line-height: 1.1;
}
.small {
  font-size: 13px;
  opacity: 0.85;
}
.ok {
  color: #4caf50;
}
.short {
  color: #ff9800;
}
.q,
.snaps {
  width: 100%;
  font-size: 13px;
  border-collapse: collapse;
}
.q td:first-child {
  opacity: 0.75;
}
.snaps th {
  text-align: left;
  opacity: 0.75;
  font-weight: 400;
}
.check {
  font-size: 13px;
  margin-top: 4px;
}
.check.pass {
  color: #4caf50;
}
.check.fail {
  color: #f44336;
}
.check .hint {
  color: #ddd;
  margin-left: 22px;
}
</style>
