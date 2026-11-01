<script setup lang="ts">
// Spectrum: NAPA detector settings (CPU AprilTag detector by Zachary Wilson, GPL-3.0-only), shared by the
// AprilTag tab ("Detector: NAPA") and the CUDA / AprilTag + Object tab ("Tags on CPU (NAPA)").
import PvSlider from "@/components/common/pv-slider.vue";
import PvSwitch from "@/components/common/pv-switch.vue";
import { computed } from "vue";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";

const props = defineProps<{ flag: "napa" | "tagsOnCpu"; label: string; tooltip: string; cols: number }>();
// eslint-disable-next-line @typescript-eslint/no-explicit-any
const s = computed<any>(() => useCameraSettingsStore().currentPipelineSettings);
const set = (change: Record<string, unknown>) =>
  // eslint-disable-next-line @typescript-eslint/no-explicit-any
  useCameraSettingsStore().changeCurrentPipelineSetting(change as any, false);
</script>

<template>
  <pv-switch
    v-model="s[props.flag]"
    :switch-cols="props.cols"
    :label="props.label"
    :tooltip="props.tooltip"
    @update:modelValue="(value) => set({ [props.flag]: value })"
  />
  <template v-if="s[props.flag]">
    <pv-slider
      v-model="s.napaMinTagPx"
      :slider-cols="props.cols"
      label="NAPA min tag px"
      tooltip="Smallest tag (black square side, px) NAPA is sure to search for. 16 finds ~13% more tags than our GPU detector on field recordings; 32 (NAPA's default) is 5x faster but misses 18% of tags"
      :min="8"
      :max="64"
      :step="1"
      @update:modelValue="(value) => set({ napaMinTagPx: value })"
    />
    <pv-slider
      v-model="s.napaMaxHamming"
      :slider-cols="props.cols"
      label="NAPA max hamming"
      tooltip="Corrected code bits (replaces the hamming filter). Keep 1: 3 finds a few more tags but risks wrong IDs"
      :min="0"
      :max="3"
      :step="1"
      @update:modelValue="(value) => set({ napaMaxHamming: value })"
    />
    <pv-switch
      v-model="s.napaFarSearch"
      :switch-cols="props.cols"
      label="NAPA far search"
      tooltip="Search the full frame at 32 px every frame, and small tags (far min tag px) only around tags already seen and in capped sweeps of one band at a time. Cheaper than min tag px 16 everywhere"
      @update:modelValue="(value) => set({ napaFarSearch: value })"
    />
    <pv-slider
      v-if="s.napaFarSearch"
      v-model="s.napaFarMinTagPx"
      :slider-cols="props.cols"
      label="NAPA far min tag px"
      tooltip="Smallest tag searched in the far search's crops and sweeps"
      :min="8"
      :max="32"
      :step="1"
      @update:modelValue="(value) => set({ napaFarMinTagPx: value })"
    />
  </template>
</template>
