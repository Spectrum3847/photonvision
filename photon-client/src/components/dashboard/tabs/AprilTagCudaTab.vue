<script setup lang="ts">
import { PipelineType } from "@/types/PipelineTypes";
import PvSelect from "@/components/common/pv-select.vue";
import PvSlider from "@/components/common/pv-slider.vue";
import NapaSettings from "@/components/dashboard/tabs/NapaSettings.vue";
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
  <div
    v-if="
      currentPipelineSettings.pipelineType === PipelineType.AprilTagCuda ||
      currentPipelineSettings.pipelineType === PipelineType.AprilTagObject
    "
  >
    <pv-select
      v-model="currentPipelineSettings.tagFamily"
      label="Target family"
      :items="['AprilTag 36h11 (6.5in)']"
      :select-cols="interactiveCols"
      @update:modelValue="(value) => useCameraSettingsStore().changeCurrentPipelineSetting({ tagFamily: value }, false)"
    />
    <pv-slider
      v-model="currentPipelineSettings.tagFpsMax"
      :slider-cols="interactiveCols"
      label="Tag FPS max"
      tooltip="Process at most this many frames a second (0 = every frame). The camera mode doesn't change; the frames in between are skipped before decoding, which frees GPU and CPU (e.g. 122 front, 60 sides). Idle mode's lower rate still applies"
      :min="0"
      :max="122"
      :step="1"
      @update:modelValue="(value) => useCameraSettingsStore().changeCurrentPipelineSetting({ tagFpsMax: value }, false)"
    />
    <pv-slider
      v-model="currentPipelineSettings.decisionMargin"
      :slider-cols="interactiveCols"
      label="Decision Margin Cutoff"
      tooltip="Recommended: 15 at 5 ms exposure. Tags with a 'margin' (decoding quality score) below this are rejected. Set it a little below what real tags score at your longest distance; lower it if tags flicker at short exposures, raise it if false tags appear"
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
    <NapaSettings
      flag="tagsOnCpu"
      label="Tags on CPU (NAPA)"
      tooltip="Find AprilTags on the CPU with NAPA (by Zachary Wilson) instead of the GPU detector, leaving the GPU to object detection. About 2-3 ms of one CPU core a frame; JPEG decode stays on the hardware decoder. The GPU detector's far search and mask don't apply"
      :cols="interactiveCols"
    />
    <p class="text-caption pt-2">
      The CUDA detector runs at full resolution with fixed parameters; decimate, blur, threads and refine edges do not
      apply.
    </p>
  </div>
</template>
