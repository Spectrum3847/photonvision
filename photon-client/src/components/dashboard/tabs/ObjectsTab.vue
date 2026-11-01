<script setup lang="ts">
// SpectrumJetson (photonvision-100): the object side of an "AprilTag + Object" pipeline. The tag side
// is the AprilCudaTag tab; the objects go to the "<camera>-objects" NetworkTables table, which
// PhotonLib reads as PhotonCamera("<camera>-objects").
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import { type AprilTagObjectPipelineSettings, PipelineType } from "@/types/PipelineTypes";
import PvSlider from "@/components/common/pv-slider.vue";
import PvSelect, { type SelectItem } from "@/components/common/pv-select.vue";
import PvInput from "@/components/common/pv-input.vue";
import { computed, ref, watch } from "vue";
import { useStateStore } from "@/stores/StateStore";
import { useSettingsStore } from "@/stores/settings/GeneralSettingsStore";
import { useDisplay } from "vuetify";
import type { ObjectDetectionModelProperties } from "@/types/SettingTypes";

const currentPipelineSettings = computed<AprilTagObjectPipelineSettings>(
  () => useCameraSettingsStore().currentPipelineSettings as AprilTagObjectPipelineSettings
);
const { mdAndDown } = useDisplay();
const interactiveCols = computed(() =>
  mdAndDown.value && (!useStateStore().sidebarFolded || useCameraSettingsStore().isDriverMode) ? 9 : 8
);

// TensorRT models only: the combined pipeline runs on the Jetson.
const supportedModels = computed<ObjectDetectionModelProperties[]>(() =>
  useSettingsStore().general.availableModels.filter((m) => m.family.toLowerCase() === "tensorrt")
);
const modelWrapper = computed<SelectItem<string>[]>(() =>
  supportedModels.value.map((model) => ({ name: model.nickname, value: model.modelPath }))
);
const selectedModel = computed<string>({
  get: () => currentPipelineSettings.value.model?.modelPath ?? "",
  set: (value) => {
    const model = supportedModels.value.find((m) => m.modelPath === value);
    if (model) useCameraSettingsStore().changeCurrentPipelineSetting({ model }, true);
  }
});

const classes = ref(currentPipelineSettings.value.objectClasses ?? "");
watch(
  () => currentPipelineSettings.value.objectClasses,
  (v) => (classes.value = v ?? "")
);
const objectsTable = computed(() => useCameraSettingsStore().currentCameraSettings.nickname + "-objects");
</script>

<template>
  <div v-if="currentPipelineSettings.pipelineType === PipelineType.AprilTagObject">
    <pv-select
      v-model="selectedModel"
      label="Model"
      tooltip="The TensorRT (ONNX) model; upload one in Settings > Object Detection. The first use builds its engine on this Jetson (a few minutes); tags run meanwhile"
      :select-cols="interactiveCols"
      :items="modelWrapper"
    />
    <pv-slider
      v-model="currentPipelineSettings.objectFpsMax"
      class="pt-2"
      :slider-cols="interactiveCols"
      label="Object FPS max"
      tooltip="At most this many frames a second also go to the model (0 = every processed frame). The tags still run on every frame up to Tag FPS max"
      :min="0"
      :max="60"
      :step="1"
      @update:modelValue="
        (value) => useCameraSettingsStore().changeCurrentPipelineSetting({ objectFpsMax: value }, false)
      "
    />
    <pv-slider
      v-model="currentPipelineSettings.confidence"
      class="pt-2"
      :slider-cols="interactiveCols"
      label="Confidence"
      tooltip="The minimum confidence for a detection to be kept"
      :min="0"
      :max="1"
      :step="0.01"
      @update:modelValue="
        (value) => useCameraSettingsStore().changeCurrentPipelineSetting({ confidence: value }, false)
      "
    />
    <pv-slider
      v-model="currentPipelineSettings.nms"
      class="pt-2"
      :slider-cols="interactiveCols"
      label="NMS IoU"
      tooltip="Overlapping boxes of the same class above this overlap are merged into the best one"
      :min="0"
      :max="1"
      :step="0.01"
      @update:modelValue="(value) => useCameraSettingsStore().changeCurrentPipelineSetting({ nms: value }, false)"
    />
    <pv-slider
      v-model="currentPipelineSettings.objectMaxTargets"
      class="pt-2"
      :slider-cols="interactiveCols"
      label="Max objects"
      tooltip="At most this many objects per result, best first"
      :min="1"
      :max="50"
      :step="1"
      @update:modelValue="
        (value) => useCameraSettingsStore().changeCurrentPipelineSetting({ objectMaxTargets: value }, false)
      "
    />
    <pv-input
      v-model="classes"
      label="Classes"
      tooltip="Class names to keep, comma-separated (press Enter); empty keeps every class"
      placeholder="all classes"
      :input-cols="interactiveCols"
      @on-enter="(value) => useCameraSettingsStore().changeCurrentPipelineSetting({ objectClasses: value }, false)"
    />
    <p class="text-caption pt-2">
      Objects are published on <code>{{ objectsTable }}</code> with their own capture time; on the robot read them with
      <code>new PhotonCamera("{{ objectsTable }}")</code>. Tags stay on this camera's own table, unchanged.
    </p>
  </div>
</template>
