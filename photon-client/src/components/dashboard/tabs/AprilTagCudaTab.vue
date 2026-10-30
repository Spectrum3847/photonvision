<script setup lang="ts">
import { PipelineType } from "@/types/PipelineTypes";
import PvSelect from "@/components/common/pv-select.vue";
import PvSlider from "@/components/common/pv-slider.vue";
import { computed } from "vue";
import { useStateStore } from "@/stores/StateStore";
import type { ActivePipelineSettings } from "@/types/PipelineTypes";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import { useDisplay } from "vuetify";

// Ported to Vue 3 / Vuetify 3 to match AprilTagTab.vue (the original used Vue 2's
// $vuetify.breakpoint and @input, and rendered blank).
//
// Only settings the CUDA path actually uses are shown. decimate, blur, threads and
// refineEdges are ignored: GpuDetectorJNI only receives the calibration (setparams)
// and hard-codes its detector parameters.
const currentPipelineSettings = computed<ActivePipelineSettings>(
  () => useCameraSettingsStore().currentPipelineSettings
);
const { mdAndDown } = useDisplay();
const interactiveCols = computed(() =>
  mdAndDown.value && (!useStateStore().sidebarFolded || useCameraSettingsStore().isDriverMode) ? 8 : 7
);
</script>

<template>
  <div v-if="currentPipelineSettings.pipelineType === PipelineType.AprilTagCuda">
    <pv-select
      v-model="currentPipelineSettings.tagFamily"
      label="Target family"
      :items="['AprilTag 36h11 (6.5in)']"
      :select-cols="interactiveCols"
      @update:modelValue="(value) => useCameraSettingsStore().changeCurrentPipelineSetting({ tagFamily: value }, false)"
    />
    <pv-slider
      v-model="currentPipelineSettings.decisionMargin"
      :slider-cols="interactiveCols"
      label="Decision Margin Cutoff"
      tooltip="Tags with a 'margin' (decoding quality score) less than this will be rejected. Lower it if tags flicker at short exposures; raise it to reduce false positives"
      :min="0"
      :max="250"
      @update:modelValue="
        (value) => useCameraSettingsStore().changeCurrentPipelineSetting({ decisionMargin: value }, false)
      "
    />
    <pv-slider
      v-model="currentPipelineSettings.numIterations"
      :slider-cols="interactiveCols"
      label="Pose Estimation Iterations"
      tooltip="Number of iterations the pose estimation algorithm will run, 50-100 is a good starting point"
      :min="0"
      :max="500"
      @update:modelValue="
        (value) => useCameraSettingsStore().changeCurrentPipelineSetting({ numIterations: value }, false)
      "
    />
    <p class="text-caption pt-2">
      The CUDA detector runs at full resolution with fixed parameters; decimate, blur, threads and refine edges do not
      apply.
    </p>
  </div>
</template>
