<script setup lang="ts">
// SpectrumJetson (photonvision-34): a focus score while turning a camera's lens, like Limelight's.
// The Jetson measures the image's sharpness on a 3x3 grid (FocusMeter, GET /api/focus) while this
// is on. Each cell is shown against its own best since "Reset", which doesn't depend on how much
// detail that part of the scene has.
import axios from "axios";
import { computed, onBeforeUnmount, ref, watch } from "vue";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import CameraFocusDialog from "@/components/cameras/CameraFocusDialog.vue";

const viewOpen = ref(false);

interface Reading {
  grid: number[]; // row-major, cell 4 is the centre
  centre: number;
  best: number;
  width: number;
  height: number;
  ageMs: number;
}

const on = ref(false);
const reading = ref<Reading | null>(null);
const cellBest = ref<number[]>(Array(9).fill(0));
const camera = computed(() => useCameraSettingsStore().currentCameraSettings.uniqueName);

let timer: ReturnType<typeof setTimeout> | undefined;
const poll = async (reset = false) => {
  clearTimeout(timer);
  if (reset) cellBest.value = Array(9).fill(0);
  try {
    const r = await axios.get(`/focus?camera=${encodeURIComponent(camera.value)}${reset ? "&reset=1" : ""}`);
    reading.value = r.status === 200 ? r.data : null;
    if (reading.value) cellBest.value = cellBest.value.map((b, i) => Math.max(b, reading.value!.grid[i]));
  } catch {
    reading.value = null;
  }
  if (on.value) timer = setTimeout(() => void poll(), 250);
};
watch(on, (v) => {
  if (v) void poll(true);
  else {
    clearTimeout(timer);
    reading.value = null;
  }
});
watch(camera, () => {
  if (on.value) void poll(true);
});
onBeforeUnmount(() => clearTimeout(timer));

const share = (v: number, best: number) => (best > 0 ? Math.min(1, v / best) : 0);
const centrePct = computed(() => (reading.value ? 100 * share(reading.value.centre, reading.value.best) : 0));
const colour = (pct: number) => (pct >= 97 ? "success" : pct >= 85 ? "warning" : "error");
const status = computed(() => {
  if (!reading.value) return "Measuring…";
  if (centrePct.value >= 97) return "At the sharpest so far.";
  if (centrePct.value >= 85) return "Close to the sharpest so far.";
  return "Softer than the sharpest so far: turn back.";
});
const cells = computed(() =>
  (reading.value?.grid ?? Array(9).fill(0)).map((v, i) => Math.round(100 * share(v, cellBest.value[i])))
);
</script>

<template>
  <CameraFocusDialog v-model="viewOpen" />
  <v-card color="surface" class="rounded-12 mt-3">
    <v-card-title class="d-flex align-center">
      Focus
      <v-spacer />
      <v-btn class="mr-3" size="small" color="primary" variant="tonal" @click="viewOpen = true">
        Open focus view
      </v-btn>
      <v-switch v-model="on" label="Measure" color="primary" density="compact" hide-details class="flex-grow-0" />
    </v-card-title>
    <v-card-text v-if="!on" class="pt-0 text-body-2">
      Turn on while focusing the lens: it scores how sharp this camera's image is, like Limelight's focus tool.
    </v-card-text>
    <v-card-text v-else class="pt-0">
      <div class="d-flex align-end" style="gap: 20px">
        <div>
          <div class="text-caption">Centre</div>
          <div class="text-h4">{{ reading ? Math.round(reading.centre) : "…" }}</div>
        </div>
        <div>
          <div class="text-caption">Sharpest so far</div>
          <div class="text-h6">{{ reading ? Math.round(reading.best) : "" }}</div>
        </div>
        <v-spacer />
        <v-btn size="small" variant="tonal" @click="poll(true)">Reset</v-btn>
      </div>
      <v-progress-linear :model-value="centrePct" :color="colour(centrePct)" height="10" rounded class="mt-2" />
      <div class="text-caption mt-1">{{ status }}</div>

      <div class="text-caption mt-3">Each part of the image, against its own sharpest so far:</div>
      <div class="grid mt-1">
        <div v-for="(pct, i) in cells" :key="i" class="cell" :class="`text-${colour(pct)}`">{{ pct }}%</div>
      </div>

      <div class="text-caption mt-3">
        Point the camera at something detailed that stays still: a tag or the calibration board, about as far away as
        the tags you care about most. Press Reset, turn the lens slowly past the sharpest point, then back to it. When
        the centre is at 100%, the corners should be close to 100% too; a corner well below means a tilted lens or a
        soft corner. Then lock the lens (glue or silicone) and recalibrate. The numbers depend on the scene and light,
        so only compare them without moving the camera.
      </div>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 4px;
  max-width: 240px;
}
.cell {
  text-align: center;
  padding: 6px 0;
  border: 1px solid rgba(128, 128, 128, 0.35);
  border-radius: 6px;
  font-variant-numeric: tabular-nums;
}
</style>
