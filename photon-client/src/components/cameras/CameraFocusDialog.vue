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

const share = (v: number, best: number) => (best > 0 ? Math.min(1, v / best) : 0);
const centrePct = computed(() => (reading.value ? 100 * share(reading.value.centre, reading.value.best) : 0));
const cells = computed(() =>
  (reading.value?.grid ?? Array(9).fill(0)).map((v, i) => Math.round(100 * share(v, cellBest.value[i])))
);
const tint = (pct: number) => (pct >= 97 ? "76, 175, 80" : pct >= 85 ? "255, 193, 7" : "244, 67, 54");
const status = computed(() => {
  if (!reading.value) return "Measuring…";
  if (centrePct.value >= 97) return "At the sharpest so far";
  if (centrePct.value >= 85) return "Close: keep turning slowly";
  return "Softer than the best: turn back";
});

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
    ctx.fillStyle = `rgba(${tint(pct)}, 0.12)`;
    ctx.fillRect(cx, cy, w / 3, h / 3);
    ctx.strokeStyle = `rgba(${tint(pct)}, 0.9)`;
    ctx.lineWidth = Math.max(2, h / 300);
    ctx.strokeRect(cx, cy, w / 3, h / 3);
    if (i === 4) return; // the loupe and the big number cover the centre
    ctx.fillStyle = "rgba(0, 0, 0, 0.55)";
    ctx.fillRect(cx + w / 6 - font * 1.6, cy + h / 6 - font * 0.65, font * 3.2, font * 1.3);
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
    if (reading.value) cellBest.value = cellBest.value.map((b, i) => Math.max(b, reading.value!.grid[i]));
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
          <div class="label">Centre</div>
          <div class="big">{{ reading ? Math.round(reading.centre) : "…" }}</div>
        </div>
        <div class="mr-6">
          <div class="label">Sharpest so far</div>
          <div class="mid">{{ reading ? Math.round(reading.best) : "" }}</div>
        </div>
        <div class="flex-grow-1 mr-6">
          <div class="label">{{ status }}</div>
          <!-- Peak hold: the bar is now, the mark at the right end is the best so far. -->
          <div class="meter">
            <div
              class="fill"
              :style="{ width: `${centrePct}%`, background: `rgb(${tint(Math.round(centrePct))})` }"
            ></div>
            <div class="peak"></div>
          </div>
        </div>
        <v-btn class="mr-2" size="large" variant="tonal" @click="reset">Reset (R)</v-btn>
        <v-btn size="large" variant="tonal" @click="open = false">Close (Esc)</v-btn>
      </div>
      <canvas ref="canvas" class="flex-grow-1" style="width: 100%; min-height: 0"></canvas>
      <div class="px-4 py-1 hint">
        Turn the lens slowly past the sharpest point and back. Each cell is against its own best since Reset; the centre
        is magnified 4x. Lock the lens at 100% and recalibrate.
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
.meter {
  position: relative;
  height: 22px;
  background: rgba(255, 255, 255, 0.12);
  border-radius: 11px;
  overflow: hidden;
  margin-top: 6px;
}
.fill {
  height: 100%;
  transition: width 0.1s linear;
}
.peak {
  position: absolute;
  right: 0;
  top: 0;
  width: 4px;
  height: 100%;
  background: #fff;
}
.hint {
  color: #ccc;
  font-size: 13px;
  background: #151219;
}
</style>
