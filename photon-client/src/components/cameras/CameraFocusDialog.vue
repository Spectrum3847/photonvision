<script setup lang="ts">
// Spectrum: a full-screen focus view, opened from the Focus card. The camera's own image at full size
// (GET /api/focus/frame: the dashboard stream is shrunk, which hides fine focus), with the focus grid
// tinted over it, the centre magnified 4x, and big numbers readable from a step back while turning
// the lens. Detection keeps running. The Jetson only makes these images while this is open.
import axios from "axios";
import { computed, onBeforeUnmount, ref, useTemplateRef, watch } from "vue";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";

interface Reading {
  grid: number[]; // row-major, cell 4 is the centre
  centre: number;
  best: number;
  width: number;
  height: number;
  ageMs: number;
}

const open = defineModel<boolean>({ default: false });
const camera = computed(() => useCameraSettingsStore().currentCameraSettings.uniqueName);
const reading = ref<Reading | null>(null);
const cellBest = ref<number[]>(Array(9).fill(0));
const canvas = useTemplateRef<HTMLCanvasElement>("canvas");
let image: ImageBitmap | null = null;
let running = false;

// Stops: turning the lens puts a hand in front of the camera, so what matters is each settled
// reading against the one before, not the motion. A stop is when the centre holds within 3% for
// 0.6 s and isn't far below the best stop (a hand in the way). The best and each cell's best come
// from stops only, so a hand or a lucky moment can't set them.
interface Stop {
  centre: number;
  grid: number[];
}
const recent = ref<{ centre: number; grid: number[] }[]>([]);
const stops = ref<Stop[]>([]);
let inStop = false;
const STOPS_SHOWN = 10;
const bestStop = computed(() => Math.max(0, ...stops.value.map((st) => st.centre)));
const inStopRef = ref(false);
const settled = computed(() => inStopRef.value);

const addReading = (r: Reading) => {
  recent.value = [...recent.value, { centre: r.centre, grid: r.grid }].slice(-6);
  const w = recent.value;
  const mean = w.reduce((a, b) => a + b.centre, 0) / w.length;
  const spread = Math.max(...w.map((x) => x.centre)) - Math.min(...w.map((x) => x.centre));
  const stable = w.length >= 6 && mean > 0 && spread <= 0.03 * mean && mean >= 0.5 * bestStop.value;
  if (!stable) {
    inStop = false;
  } else {
    const stop = { centre: mean, grid: r.grid.slice() };
    if (inStop) stops.value = [...stops.value.slice(0, -1), stop];
    else stops.value = [...stops.value, stop];
    inStop = true;
    cellBest.value = cellBest.value.map((b, i) => Math.max(b, stop.grid[i]));
  }
  inStopRef.value = inStop;
};

const share = (v: number, best: number) => (best > 0 ? Math.min(1, v / best) : 0);
const centrePct = computed(() => (reading.value ? 100 * share(reading.value.centre, bestStop.value) : 0));
const cells = computed(() =>
  (reading.value?.grid ?? Array(9).fill(0)).map((v, i) => Math.round(100 * share(v, cellBest.value[i])))
);
// The latest stop against the one before it.
const verdict = computed<{ text: string; tone: "up" | "down" | "same" | "none" }>(() => {
  const n = stops.value.length;
  if (n === 0) return { text: "Let go of the lens to take a reading", tone: "none" };
  if (n === 1) return { text: "First stop. Turn a little either way, then let go", tone: "none" };
  const last = stops.value[n - 1].centre,
    prev = stops.value[n - 2].centre;
  const pct = (100 * (last - prev)) / prev;
  const sign = pct >= 0 ? "+" : "";
  if (pct > 2) return { text: `${sign}${pct.toFixed(0)}% vs last stop: keep turning the same way`, tone: "up" };
  if (pct < -2)
    return { text: `${pct.toFixed(0)}% vs last stop: you passed it. Turn back, smaller steps`, tone: "down" };
  return { text: `About the same as the last stop (${sign}${pct.toFixed(1)}%): try a smaller turn`, tone: "same" };
});
const atBest = computed(
  () => stops.value.length > 1 && settled.value && stops.value[stops.value.length - 1].centre >= 0.98 * bestStop.value
);
// A cell with much less detail than the most detailed one measures mostly noise.
const noDetail = computed(() => {
  const g = reading.value?.grid ?? [];
  const most = Math.max(...g, 0);
  return g.map((v) => most > 0 && v < 0.15 * most);
});

const tint = (pct: number) => (pct >= 97 ? "76, 175, 80" : pct >= 85 ? "255, 193, 7" : "244, 67, 54");

const draw = () => {
  const c = canvas.value;
  if (!c || !image) return;
  const rect = c.getBoundingClientRect();
  c.width = Math.round(rect.width * devicePixelRatio);
  c.height = Math.round(rect.height * devicePixelRatio);
  const ctx = c.getContext("2d")!;
  ctx.fillStyle = "#000";
  ctx.fillRect(0, 0, c.width, c.height);
  // The image, fitted.
  const s = Math.min(c.width / image.width, c.height / image.height);
  const w = image.width * s,
    h = image.height * s;
  const x0 = (c.width - w) / 2,
    y0 = (c.height - h) / 2;
  ctx.imageSmoothingEnabled = true;
  ctx.drawImage(image, x0, y0, w, h);
  // The 3x3 grid, each cell tinted by its share of its own best.
  const font = Math.round(h / 14);
  ctx.font = `600 ${font}px sans-serif`;
  ctx.textAlign = "center";
  ctx.textBaseline = "middle";
  cells.value.forEach((pct, i) => {
    const cx = x0 + ((i % 3) * w) / 3,
      cy = y0 + (Math.floor(i / 3) * h) / 3;
    if (noDetail.value[i]) {
      ctx.strokeStyle = "rgba(255, 255, 255, 0.25)";
      ctx.lineWidth = Math.max(1, h / 400);
      ctx.strokeRect(cx, cy, w / 3, h / 3);
    } else {
      ctx.fillStyle = `rgba(${tint(pct)}, 0.12)`;
      ctx.fillRect(cx, cy, w / 3, h / 3);
      ctx.strokeStyle = `rgba(${tint(pct)}, 0.9)`;
      ctx.lineWidth = Math.max(2, h / 300);
      ctx.strokeRect(cx, cy, w / 3, h / 3);
    }
    if (i === 4) return; // the loupe and the big number cover the centre
    ctx.fillStyle = "rgba(0, 0, 0, 0.55)";
    ctx.fillRect(cx + w / 6 - font * 1.6, cy + h / 6 - font * 0.65, font * 3.2, font * 1.3);
    if (noDetail.value[i]) {
      ctx.fillStyle = "#bbb";
      ctx.font = `600 ${Math.round(font * 0.45)}px sans-serif`;
      ctx.fillText("no detail", cx + w / 6, cy + h / 6);
      ctx.font = `600 ${font}px sans-serif`;
      return;
    }
    ctx.fillStyle = `rgb(${tint(pct)})`;
    ctx.fillText(`${pct}%`, cx + w / 6, cy + h / 6);
  });
  // The centre, 4x: an eighth of the image's width, drawn a quarter as wide as the view, pixels
  // not smoothed so the focus is judged on the camera's own pixels.
  const lw = image.width / 8,
    lh = image.height / 8;
  const dw = w / 2,
    dh = h / 2;
  const dx = x0 + (w - dw) / 2,
    dy = y0 + (h - dh) / 2;
  ctx.imageSmoothingEnabled = false;
  ctx.drawImage(image, (image.width - lw) / 2, (image.height - lh) / 2, lw, lh, dx, dy, dw, dh);
  ctx.strokeStyle = `rgb(${tint(Math.round(centrePct.value))})`;
  ctx.lineWidth = Math.max(3, h / 200);
  ctx.strokeRect(dx, dy, dw, dh);
  ctx.font = `600 ${Math.round(font * 0.6)}px sans-serif`;
  ctx.textAlign = "left";
  ctx.textBaseline = "top";
  ctx.fillStyle = "rgba(0, 0, 0, 0.6)";
  ctx.fillRect(dx, dy, font * 3.2, font * 0.9);
  ctx.fillStyle = "#fff";
  ctx.fillText("centre 4x", dx + font * 0.2, dy + font * 0.15);
};

const tick = async () => {
  if (!running) return;
  const cam = encodeURIComponent(camera.value);
  try {
    const [r, f] = await Promise.all([
      axios.get(`/focus?camera=${cam}`),
      axios.get(`/focus/frame?camera=${cam}`, { responseType: "blob" })
    ]);
    reading.value = r.status === 200 ? r.data : null;
    if (reading.value) addReading(reading.value);
    if (f.status === 200) {
      const next = await createImageBitmap(f.data as Blob);
      image?.close();
      image = next;
    }
    draw();
  } catch {
    reading.value = null;
  }
  if (running) setTimeout(() => void tick(), 100);
};

const reset = async () => {
  cellBest.value = Array(9).fill(0);
  recent.value = [];
  stops.value = [];
  inStop = false;
  inStopRef.value = false;
  try {
    await axios.get(`/focus?camera=${encodeURIComponent(camera.value)}&reset=1`);
  } catch {
    // the next reading resets it anyway
  }
};

watch(open, (v) => {
  running = v;
  if (v) {
    void reset();
    void tick();
  } else {
    image?.close();
    image = null;
    reading.value = null;
  }
});
const onKey = (e: KeyboardEvent) => {
  if (open.value && (e.key === "r" || e.key === "R")) void reset();
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
      <div class="d-flex align-center px-4 py-2 bar">
        <div class="mr-6">
          <div class="label">{{ settled ? "Now (settled)" : "Turning…" }}</div>
          <div class="big" :style="{ opacity: settled ? 1 : 0.4 }">
            {{ reading ? Math.round(reading.centre) : "…" }}
          </div>
        </div>
        <div class="mr-6">
          <div class="label">Best stop</div>
          <div class="mid">{{ bestStop ? Math.round(bestStop) : "–" }}</div>
        </div>
        <div class="flex-grow-1 mr-6">
          <div class="verdict" :class="atBest ? 'up' : verdict.tone">
            {{ atBest ? "At the best stop: lock the lens here" : verdict.text }}
          </div>
          <!-- The stops, oldest to newest, against the best: the peak shows as the tallest bar. -->
          <div class="stops">
            <div
              v-for="(st, i) in stops.slice(-STOPS_SHOWN)"
              :key="i"
              class="stop"
              :class="{ best: st.centre === bestStop }"
              :style="{ height: `${Math.max(8, (100 * st.centre) / (bestStop || 1))}%` }"
            ></div>
          </div>
        </div>
        <v-btn class="mr-2" size="large" variant="tonal" @click="reset">Reset (R)</v-btn>
        <v-btn size="large" variant="tonal" @click="open = false">Close (Esc)</v-btn>
      </div>
      <canvas ref="canvas" class="flex-grow-1" style="width: 100%; min-height: 0"></canvas>
      <div class="px-4 py-1 hint">
        Turn the lens a little, let go, and wait for it to settle: each stop is compared with the one before. Keep going
        while it improves; when it gets worse, turn back in smaller steps. Cells are against their best stop; the centre
        is magnified 4x. Lock the lens at the best stop and recalibrate.
      </div>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.bar {
  background: #151219;
  color: #fff;
}
.label {
  font-size: 14px;
  opacity: 0.75;
}
.big {
  font-size: 56px;
  font-weight: 700;
  line-height: 1;
}
.mid {
  font-size: 32px;
  line-height: 1;
}
.verdict {
  font-size: 22px;
  font-weight: 600;
}
.verdict.up {
  color: #4caf50;
}
.verdict.down {
  color: #f44336;
}
.verdict.same,
.verdict.none {
  color: #ddd;
}
.stops {
  display: flex;
  align-items: flex-end;
  gap: 4px;
  height: 36px;
  margin-top: 4px;
}
.stop {
  width: 22px;
  background: #996aff;
  border-radius: 3px 3px 0 0;
}
.stop.best {
  background: #4caf50;
}
.hint {
  color: #ccc;
  font-size: 13px;
  background: #151219;
}
</style>
