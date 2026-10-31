<script setup lang="ts">
// SpectrumJetson (photonvision-40): the pipeline's detection mask. The boxes are drawn on the
// input stream (pv-mask-overlay in CamerasCard) while "Draw on the stream" is on.
import { computed, onBeforeUnmount } from "vue";
import PvSelect from "@/components/common/pv-select.vue";
import PvSwitch from "@/components/common/pv-switch.vue";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import { useStateStore } from "@/stores/StateStore";
import { type DetectionMask, DetectionMaskMode, PipelineType } from "@/types/PipelineTypes";

const mask = computed<DetectionMask>(
  () => useCameraSettingsStore().currentPipelineSettings.detectionMask ?? { mode: DetectionMaskMode.Off, boxes: [] }
);
const setMask = (m: DetectionMask) => useCameraSettingsStore().changeCurrentPipelineSetting({ detectionMask: m }, true);

const modeItems = [
  { name: "Off", value: DetectionMaskMode.Off },
  { name: "Ignore inside the boxes", value: DetectionMaskMode.IgnoreInside },
  { name: "Search only inside the boxes", value: DetectionMaskMode.OnlyInside }
];
const setMode = (mode: DetectionMaskMode) => setMask({ ...mask.value, mode });
const removeBox = (i: number) => setMask({ ...mask.value, boxes: mask.value.boxes.filter((_, j) => j !== i) });
const clearBoxes = () => setMask({ ...mask.value, boxes: [] });

const drawing = computed<boolean>({
  get: () => useStateStore().maskEditing,
  set: (v) => (useStateStore().maskEditing = v)
});
onBeforeUnmount(() => (useStateStore().maskEditing = false));

const isObjectDetection = computed(() => useCameraSettingsStore().currentPipelineType === PipelineType.ObjectDetection);
const pct = (v: number) => `${Math.round(v * 100)}%`;
</script>

<template>
  <div>
    <pv-select
      :model-value="mask.mode"
      label="Mask"
      tooltip="Ignore the areas inside the boxes (ceiling lights, the robot's own bumper), or search only inside them. Boxes are drawn on the input stream."
      :items="modeItems"
      :select-cols="8"
      @update:modelValue="setMode"
    />
    <pv-switch
      v-model="drawing"
      label="Draw on the stream"
      tooltip="On: drag on a stream to draw a box, drag a box to move it, drag any corner to resize, and press Delete to remove the selected box. Turn it off when you're done."
      :switch-cols="8"
    />
    <v-alert
      v-if="mask.mode === DetectionMaskMode.OnlyInside && mask.boxes.length === 0"
      density="compact"
      type="info"
      variant="tonal"
      class="mt-2"
      text="With no boxes, 'search only inside' does nothing: the whole image is searched."
    />
    <v-alert
      v-else-if="mask.mode === DetectionMaskMode.Off && mask.boxes.length > 0"
      density="compact"
      type="info"
      variant="tonal"
      class="mt-2"
      text="The boxes are kept, but the mask is off."
    />
    <p class="text-body-2 mt-3 mb-1">
      {{
        isObjectDetection
          ? "A detection whose centre is in an ignored area is dropped."
          : "Ignored areas are removed on the GPU before tags are searched for, so they cost nothing. A tag that crosses the edge of an ignored area is lost: keep the boxes clear of anywhere a tag can be."
      }}
    </p>
    <v-table v-if="mask.boxes.length" density="compact" class="mt-2">
      <thead>
        <tr>
          <th>Box</th>
          <th>Left</th>
          <th>Top</th>
          <th>Width</th>
          <th>Height</th>
          <th />
        </tr>
      </thead>
      <tbody>
        <tr v-for="(b, i) in mask.boxes" :key="i">
          <td>{{ i + 1 }}</td>
          <td>{{ pct(b.x) }}</td>
          <td>{{ pct(b.y) }}</td>
          <td>{{ pct(b.w) }}</td>
          <td>{{ pct(b.h) }}</td>
          <td>
            <v-btn icon="mdi-delete" size="small" variant="text" @click="removeBox(i)" />
          </td>
        </tr>
      </tbody>
    </v-table>
    <v-btn v-if="mask.boxes.length" class="mt-2" size="small" variant="tonal" color="error" @click="clearBoxes">
      Remove all boxes
    </v-btn>
  </div>
</template>
