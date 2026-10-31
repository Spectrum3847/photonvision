<script setup lang="ts">
// SpectrumJetson (photonvision-55): the far-tag search's switch, rate and counters. In the 2026 tree
// these were on Settings > Robot state; they have their own card until that's ported.
import { onBeforeUnmount, onMounted, ref } from "vue";
import axios from "axios";
import { useStateStore } from "@/stores/StateStore";
import PvSwitch from "@/components/common/pv-switch.vue";
import PvSlider from "@/components/common/pv-slider.vue";

interface FarSearchState {
  farSearch: boolean;
  farSweepsPerSecond: number;
  farSearchStarved?: boolean;
  farSearchSweeps?: number;
  farSearchCrops?: number;
  farSearchTags?: number;
  farSearchStarvedSeconds?: number;
  farSearchSweepMs?: number;
}

interface FarSearchChange {
  farSearch?: boolean;
  farSweepsPerSecond?: number;
}

const state = ref<FarSearchState | null>(null);
const busy = ref(false);

const load = async () => {
  try {
    state.value = (await axios.get<FarSearchState>("/farSearch")).data;
  } catch {
    // Backend restarting: try again on the next tick.
  }
};

const set = async (change: FarSearchChange) => {
  busy.value = true;
  try {
    state.value = (await axios.post<FarSearchState>("/farSearch", change)).data;
  } catch (e) {
    useStateStore().showSnackbarMessage({
      message: axios.isAxiosError(e) && typeof e.response?.data === "string" ? e.response.data : "Not changed",
      color: "error"
    });
  } finally {
    busy.value = false;
  }
};

let timer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  void load();
  timer = setInterval(() => void load(), 2000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>

<template>
  <v-card class="mb-3 rounded-12" color="surface" data-testid="far-search">
    <v-card-title class="pb-10px">Far-tag search</v-card-title>
    <v-card-text v-if="state" class="pt-0">
      <pv-switch
        :model-value="state.farSearch"
        :disabled="busy"
        label="Far-tag search when the pose is weak"
        tooltip="Tags are normally searched for on a half-size image, which misses tags under ~20 px (far away). When no camera sees two good near tags for 0.25 s, one camera at a time also searches its full-size image (and follows far tags it finds with small full-size crops): about 1.7x the range. It stops as soon as any camera has a good view again, so it costs nothing while the pose is good. CUDA AprilTag pipelines only."
        :label-cols="4"
        @update:model-value="(v) => set({ farSearch: !!v })"
      />
      <pv-slider
        :model-value="state.farSweepsPerSecond"
        :disabled="busy || !state.farSearch"
        label="Full-size searches a second"
        tooltip="Across all cameras, while searching. Each takes ~5 ms of GPU; 30 a second is ~16%."
        :min="5"
        :max="60"
        :step="5"
        :slider-cols="8"
        @update:model-value="(v) => set({ farSweepsPerSecond: v })"
      />
      <p class="text-caption pb-1" data-testid="far-search-status">
        <template v-if="!state.farSearch">Off: tags are only searched for on the half-size image.</template>
        <template v-else-if="state.farSearchSweeps === undefined">
          Status unknown (no CUDA pipeline running yet, or an older detector library).
        </template>
        <template v-else>
          {{
            state.farSearchStarved ? "Searching now: no camera has a good view." : "Waiting: a camera has a good view."
          }}
          Since start: {{ state.farSearchStarvedSeconds?.toFixed(0) }} s searching,
          {{ state.farSearchSweeps }} full-size searches ({{ state.farSearchSweepMs?.toFixed(1) }} ms each),
          {{ state.farSearchTags }} far tags found.
        </template>
      </p>
    </v-card-text>
  </v-card>
</template>
