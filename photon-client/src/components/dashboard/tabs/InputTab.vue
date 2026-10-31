<script setup lang="ts">
import { onBeforeUnmount as onBeforeUnmountTc, onMounted as onMountedTc } from "vue";
import { describeTagContrast, loadTagContrast, tagContrast } from "@/lib/TagContrast";
import PvSlider from "@/components/common/pv-slider.vue";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import PvSwitch from "@/components/common/pv-switch.vue";
import PvSelect from "@/components/common/pv-select.vue";
import { computed } from "vue";
import { useSettingsStore } from "@/stores/settings/GeneralSettingsStore";
import { useStateStore } from "@/stores/StateStore";
import { getResolutionString } from "@/lib/PhotonUtils";
import { PVUsbCamera, type ExtraCameraControl } from "@/types/SettingTypes";
import { useDisplay } from "vuetify";

// Due to something with libcamera or something else IDK much about, the 90° rotations need to be disabled if the libcamera drivers are being used.
// SpectrumJetson: USB (UVC) cameras take exposure in the UVC standard's units of 100 us, so
// the raw number / 10 is milliseconds (83 = 8.3 ms, 50 = 5 ms). Show that on the slider.
const exposureLabel = computed(() => {
  const s = useCameraSettingsStore();
  if (s.currentCameraSettings.matchedCameraInfo?.type !== PVUsbCamera) return "Exposure";
  const raw = Number(s.currentPipelineSettings.cameraExposureRaw);
  return Number.isFinite(raw) ? `Exposure (${(raw / 10).toFixed(1)} ms)` : "Exposure";
});
const exposureTooltip = computed(() =>
  useCameraSettingsStore().currentCameraSettings.matchedCameraInfo?.type !== PVUsbCamera
    ? "Directly controls how long the camera shutter remains open. Units are dependant on the underlying driver."
    : "How long the shutter stays open, in units of 100 µs (the USB camera standard): 50 = 5 ms, 83 = 8.3 ms. Shorter means less motion blur but a darker image; exposure also caps the frame rate (10 ms or more cannot reach 100 fps)."
);

const cameraRotations = computed(() =>
  ["Normal", "90° CW", "180°", "90° CCW"].map((v, i) => ({
    name: v,
    value: i,
    disabled: useCameraSettingsStore().isCSICamera ? [1, 3].includes(i) : false
  }))
);

const streamDivisors = [1, 2, 4, 6];
const getFilteredStreamDivisors = (): number[] => {
  const currentResolutionWidth = useCameraSettingsStore().currentVideoFormat?.resolution.width;
  return currentResolutionWidth
    ? streamDivisors.filter(
        (x) =>
          useCameraSettingsStore().isDriverMode ||
          !useSettingsStore().gpuAccelerationEnabled ||
          currentResolutionWidth / x < 400
      )
    : [];
};
const getNumberOfSkippedDivisors = () => streamDivisors.length - getFilteredStreamDivisors().length;

const cameraResolutions = (): { name: string; value: number }[] =>
  useCameraSettingsStore().currentCameraSettings.validVideoFormats.map<{ name: string; value: number }>((f) => ({
    name: `${getResolutionString(f.resolution)} at ${f.fps} FPS, ${f.pixelFormat}`,
    value: f.index || 0 // Index won't ever be undefined
  }));
const handleResolutionChange = (value: number) => {
  useCameraSettingsStore().changeCurrentPipelineSetting({ cameraVideoModeIndex: value }, false);

  useCameraSettingsStore().changeCurrentPipelineSetting({ streamingFrameDivisor: getNumberOfSkippedDivisors() }, false);
  useCameraSettingsStore().currentPipelineSettings.streamingFrameDivisor = 0;

  if (!useCameraSettingsStore().isCurrentVideoFormatCalibrated && !useCameraSettingsStore().isDriverMode) {
    useCameraSettingsStore().changeCurrentPipelineSetting({ solvePNPEnabled: false }, true);
  }
};

const streamResolutions = computed(() => {
  const streamDivisors = getFilteredStreamDivisors();
  const currentResolution = useCameraSettingsStore().currentVideoFormat?.resolution;
  return currentResolution
    ? streamDivisors.map((x, i) => ({
        name: `${Math.floor(currentResolution.width / x)}x${Math.floor(currentResolution.height / x)}`,
        value: i
      }))
    : [];
});
const currentStreamResolutionIndex = computed<number>({
  get: () => {
    const stored = useCameraSettingsStore().currentPipelineSettings.streamingFrameDivisor;
    const skipped = getNumberOfSkippedDivisors();
    return stored - skipped;
  },
  set: (index) => {
    useCameraSettingsStore().changeCurrentPipelineSetting({
      streamingFrameDivisor: index + getNumberOfSkippedDivisors()
    });
  }
});
// SpectrumJetson (photonvision-28): contrast, gamma, sharpness and backlight compensation, which the
// stock UI doesn't show. Only the ones the camera has; -1 in the pipeline = the camera's default.
const extraControls = computed(() => useCameraSettingsStore().currentCameraSettings.extraControls ?? []);
const extraControlValue = (c: ExtraCameraControl) => {
  const v = Number(useCameraSettingsStore().currentPipelineSettings[c.key]);
  return Number.isFinite(v) && v >= 0 ? v : c.default;
};
const extraControlTooltips: Record<ExtraCameraControl["key"], string> = {
  cameraContrast:
    "Leave at the camera's default unless testing. Higher contrast can raise the decision margin at short exposures; too high clips tag edges. Check it against the decision margin and far tags.",
  cameraGamma:
    "Leave at the camera's default unless testing. The camera's tone curve: higher brightens mid-tones and changes how dark the tag's black cells look.",
  cameraSharpness:
    "Leave at the camera's default unless testing. The camera sharpens edges before JPEG compression. Sharpening can put halos on tag edges, which may shift corners, and makes JPEGs bigger (more USB bandwidth). Lower values are worth testing.",
  cameraBacklightCompensation:
    "Only matters with auto exposure. Leave it at the camera's default with auto exposure off."
};
// updateStore true (photonvision-39): these sliders show the store's value one-way (no v-model),
// so with false the camera changed but the slider and number snapped back to the old value.
const setExtraControl = (c: ExtraCameraControl, value: number | boolean) =>
  useCameraSettingsStore().changeCurrentPipelineSetting(
    { [c.key]: typeof value === "boolean" ? Number(value) : value },
    true
  );

const { mdAndDown } = useDisplay();

// SpectrumJetson (photonvision-54): how this camera's tags look, next to the controls that change it.
let tagContrastTimer: ReturnType<typeof setInterval> | undefined;
onMountedTc(() => {
  void loadTagContrast();
  tagContrastTimer = setInterval(() => void loadTagContrast(), 1000);
});
onBeforeUnmountTc(() => clearInterval(tagContrastTimer));

const interactiveCols = computed(() =>
  mdAndDown.value && (!useStateStore().sidebarFolded || useCameraSettingsStore().isDriverMode) ? 8 : 7
);
</script>

<template>
  <div>
    <v-alert
      data-testid="tag-contrast"
      density="compact"
      variant="tonal"
      class="mb-2"
      :type="
        tagContrast[useCameraSettingsStore().currentCameraSettings.nickname]?.verdict === 'good'
          ? 'success'
          : tagContrast[useCameraSettingsStore().currentCameraSettings.nickname]?.verdict === 'no tags' ||
              !tagContrast[useCameraSettingsStore().currentCameraSettings.nickname]
            ? 'info'
            : 'warning'
      "
    >
      Tags: {{ describeTagContrast(tagContrast[useCameraSettingsStore().currentCameraSettings.nickname]) }}
      <div v-if="tagContrast[useCameraSettingsStore().currentCameraSettings.nickname]?.advice" class="text-caption">
        {{ tagContrast[useCameraSettingsStore().currentCameraSettings.nickname].advice }}
      </div>
    </v-alert>
    <pv-switch
      v-model="useCameraSettingsStore().currentPipelineSettings.cameraAutoExposure"
      label="Auto Exposure"
      :switch-cols="interactiveCols"
      tooltip="Enables or Disables camera automatic adjustment for current lighting conditions"
      @update:modelValue="
        (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraAutoExposure: args }, false)
      "
    />
    <pv-slider
      v-model="useCameraSettingsStore().currentPipelineSettings.cameraExposureRaw"
      :disabled="useCameraSettingsStore().currentCameraSettings.pipelineSettings.cameraAutoExposure"
      :label="exposureLabel"
      :tooltip="exposureTooltip"
      :min="useCameraSettingsStore().minExposureRaw"
      :max="useCameraSettingsStore().maxExposureRaw"
      :slider-cols="interactiveCols"
      :step="1"
      @update:modelValue="
        (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraExposureRaw: args }, false)
      "
    />
    <pv-slider
      v-model="useCameraSettingsStore().currentPipelineSettings.cameraBrightness"
      label="Brightness"
      :min="0"
      :max="100"
      :slider-cols="interactiveCols"
      @update:modelValue="
        (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraBrightness: args }, false)
      "
    />
    <template v-for="c in extraControls" :key="c.key">
      <pv-switch
        v-if="c.max - c.min === 1"
        :model-value="extraControlValue(c) === c.max"
        :label="c.label"
        :tooltip="`${extraControlTooltips[c.key]} Camera default: ${c.default === c.max ? 'on' : 'off'}.`"
        :switch-cols="interactiveCols"
        @update:modelValue="(v) => setExtraControl(c, v ? c.max : c.min)"
      />
      <pv-slider
        v-else
        :model-value="extraControlValue(c)"
        :label="c.label"
        :tooltip="`${extraControlTooltips[c.key]} Camera default: ${c.default}.`"
        :min="c.min"
        :max="c.max"
        :step="c.step"
        :slider-cols="interactiveCols"
        @update:modelValue="(v) => setExtraControl(c, v)"
      />
    </template>
    <pv-slider
      v-if="useCameraSettingsStore().currentPipelineSettings.cameraGain >= 0"
      v-model="useCameraSettingsStore().currentPipelineSettings.cameraGain"
      label="Camera Gain"
      tooltip="Controls camera gain, similar to brightness"
      :min="0"
      :max="100"
      :slider-cols="interactiveCols"
      @update:modelValue="(args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraGain: args }, false)"
    />
    <pv-slider
      v-if="useCameraSettingsStore().currentPipelineSettings.cameraRedGain !== -1"
      v-model="useCameraSettingsStore().currentPipelineSettings.cameraRedGain"
      label="Red AWB Gain"
      :min="0"
      :max="100"
      :slider-cols="interactiveCols"
      tooltip="Controls red automatic white balance gain, which affects how the camera captures colors in different conditions"
      @update:modelValue="
        (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraRedGain: args }, false)
      "
    />
    <pv-slider
      v-if="useCameraSettingsStore().currentPipelineSettings.cameraBlueGain !== -1"
      v-model="useCameraSettingsStore().currentPipelineSettings.cameraBlueGain"
      label="Blue AWB Gain"
      :min="0"
      :max="100"
      :slider-cols="interactiveCols"
      tooltip="Controls blue automatic white balance gain, which affects how the camera captures colors in different conditions"
      @update:modelValue="
        (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraBlueGain: args }, false)
      "
    />
    <pv-switch
      v-model="useCameraSettingsStore().currentPipelineSettings.cameraAutoWhiteBalance"
      label="Auto White Balance"
      :switch-cols="interactiveCols"
      tooltip="Enables or Disables camera automatic adjustment for current lighting conditions"
      @update:modelValue="
        (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraAutoWhiteBalance: args }, false)
      "
    />
    <pv-slider
      v-model="useCameraSettingsStore().currentPipelineSettings.cameraWhiteBalanceTemp"
      :disabled="useCameraSettingsStore().currentPipelineSettings.cameraAutoWhiteBalance"
      label="White Balance Temperature"
      :min="useCameraSettingsStore().minWhiteBalanceTemp"
      :max="useCameraSettingsStore().maxWhiteBalanceTemp"
      :slider-cols="interactiveCols"
      @update:modelValue="
        (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ cameraWhiteBalanceTemp: args }, false)
      "
    />
    <!-- Spectrum: no Low Latency Mode switch; it's never on (CpuImageProcessor). -->
    <pv-select
      v-model="useCameraSettingsStore().currentPipelineSettings.inputImageRotationMode"
      label="Orientation"
      tooltip="Rotates the camera stream"
      :items="cameraRotations"
      :select-cols="interactiveCols"
      @update:modelValue="
        (args) => useCameraSettingsStore().changeCurrentPipelineSetting({ inputImageRotationMode: args }, false)
      "
    />
    <pv-select
      v-model="useCameraSettingsStore().currentPipelineSettings.cameraVideoModeIndex"
      label="Resolution"
      tooltip="Resolution and FPS the camera should directly capture at"
      :items="cameraResolutions()"
      :select-cols="interactiveCols"
      @update:modelValue="(args) => handleResolutionChange(args)"
    />
    <pv-select
      v-model="currentStreamResolutionIndex"
      label="Stream Resolution"
      tooltip="Resolution to which camera frames are downscaled for streaming to the dashboard"
      :items="streamResolutions"
      :select-cols="interactiveCols"
    />
    <pv-switch
      v-if="useCameraSettingsStore().isDriverMode"
      v-model="useCameraSettingsStore().currentPipelineSettings.crosshair"
      label="Crosshair"
      :switch-cols="interactiveCols"
      tooltip="Enables or disables a crosshair overlay on the camera stream"
      @update:modelValue="(args) => useCameraSettingsStore().changeCurrentPipelineSetting({ crosshair: args }, false)"
    />
  </div>
</template>
