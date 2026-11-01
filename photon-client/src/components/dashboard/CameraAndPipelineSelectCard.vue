<script setup lang="ts">
import PvSelect, { type SelectItem } from "@/components/common/pv-select.vue";
import { useStateStore } from "@/stores/StateStore";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import { WebsocketPipelineType } from "@/types/WebsocketDataTypes";
import { computed, ref } from "vue";
import PvIcon from "@/components/common/pv-icon.vue";
import PvInput from "@/components/common/pv-input.vue";
import { PipelineType } from "@/types/PipelineTypes";
import { useSettingsStore } from "@/stores/settings/GeneralSettingsStore";
import { useTheme } from "vuetify";
import PvDeleteModal from "@/components/common/pv-delete-modal.vue";
import axios from "axios";

const theme = useTheme();

// Common RegEx used for naming both pipelines and cameras
const nameChangeRegex = /^[A-Za-z0-9_ \-)(]*[A-Za-z0-9][A-Za-z0-9_ \-)(.]*$/;

// Camera Name Edit
const isCameraNameEdit = ref(false);
const currentCameraName = ref(useCameraSettingsStore().currentCameraSettings.nickname);
const startCameraNameEdit = () => {
  currentCameraName.value = useCameraSettingsStore().currentCameraSettings.nickname;
  isCameraNameEdit.value = true;
};
const checkCameraName = (name: string): string | boolean => {
  if (!nameChangeRegex.test(name))
    return "A camera name can only contain letters, numbers, spaces, underscores, hyphens, parenthesis, and periods";
  if (useCameraSettingsStore().cameraNames.some((cameraName) => cameraName === name))
    return "This camera name has already been used";

  return true;
};
const saveCameraNameEdit = (newName: string) => {
  useCameraSettingsStore()
    .changeCameraNickname(newName, false)
    .then((response) => {
      useStateStore().showSnackbarMessage({ color: "success", message: response.data.text || response.data });
      useCameraSettingsStore().currentCameraSettings.nickname = newName;
    })
    .catch((error) => {
      if (error.response) {
        useStateStore().showSnackbarMessage({
          color: "error",
          message: error.response.data.text || error.response.data
        });
      } else if (error.request) {
        useStateStore().showSnackbarMessage({
          color: "error",
          message: "Error while trying to process the request! The backend didn't respond."
        });
      } else {
        useStateStore().showSnackbarMessage({
          color: "error",
          message: "An error occurred while trying to process the request."
        });
      }
      currentCameraName.value = useCameraSettingsStore().currentCameraSettings.nickname;
    })
    .finally(() => (isCameraNameEdit.value = false));
};
const cancelCameraNameEdit = () => {
  isCameraNameEdit.value = false;
  currentCameraName.value = useCameraSettingsStore().currentCameraSettings.nickname;
};

// Pipeline Name Edit
const pipelineNamesWrapper = computed(() => {
  // SpectrumJetson (photonvision-47): the number too, since profiles and Switch all go by number.
  const pipelineNames = useCameraSettingsStore().pipelineNames.map((name, index) => ({
    name: `${index}: ${name}`,
    value: index
  }));

  if (useCameraSettingsStore().isDriverMode) {
    pipelineNames.push({ name: "Driver Mode", value: WebsocketPipelineType.DriverMode.valueOf() });
  }
  if (useCameraSettingsStore().isFocusMode) {
    pipelineNames.push({ name: "Focus Mode", value: WebsocketPipelineType.FocusCamera.valueOf() });
  }
  if (useCameraSettingsStore().isCalibrationMode) {
    pipelineNames.push({ name: "3D Calibration Mode", value: WebsocketPipelineType.Calib3d.valueOf() });
  }

  return pipelineNames;
});
const isPipelineNameEdit = ref(false);
const currentPipelineName = ref(useCameraSettingsStore().currentPipelineSettings.pipelineNickname);
const startPipelineNameEdit = () => {
  currentPipelineName.value = useCameraSettingsStore().currentPipelineSettings.pipelineNickname;
  isPipelineNameEdit.value = true;
};
const checkPipelineName = (name: string): string | boolean => {
  if (!nameChangeRegex.test(name))
    return "A pipeline name can only contain letters, numbers, spaces, underscores, hyphens, parenthesis, and periods";
  if (useCameraSettingsStore().pipelineNames.some((pipelineName) => pipelineName === name))
    return "This pipeline name has already been used";

  return true;
};
const savePipelineNameEdit = (name: string) => {
  useCameraSettingsStore().changeCurrentPipelineNickname(name);
  isPipelineNameEdit.value = false;
};
const cancelPipelineNameEdit = () => {
  isPipelineNameEdit.value = false;
  currentPipelineName.value = useCameraSettingsStore().currentPipelineSettings.pipelineNickname;
};

// Pipeline Creation
const showPipelineCreationDialog = ref(false);
const newPipelineName = ref("");
const newPipelineType = ref<WebsocketPipelineType>(useCameraSettingsStore().currentWebsocketPipelineType);
const validNewPipelineTypes = computed(() => {
  const pipelineTypes = [
    { name: "Reflective", value: WebsocketPipelineType.Reflective },
    { name: "Colored Shape", value: WebsocketPipelineType.ColoredShape },
    { name: "AprilTag", value: WebsocketPipelineType.AprilTag },
    { name: "AprilTagCuda", value: WebsocketPipelineType.AprilTagCuda },
    { name: "ArUco", value: WebsocketPipelineType.Aruco }
  ];
  if (useSettingsStore().general.supportedBackends.length > 0) {
    pipelineTypes.push({ name: "Object Detection", value: WebsocketPipelineType.ObjectDetection });
  }
  // SpectrumJetson (photonvision-100): tags on every frame + TensorRT objects on some
  if (useSettingsStore().general.supportedBackends.includes("TENSORRT")) {
    pipelineTypes.push({ name: "AprilTag + Object", value: WebsocketPipelineType.AprilTagObject });
  }
  return pipelineTypes;
});
// SpectrumJetson (photonvision-46): start from a copy of any camera's pipeline, and create it on
// every camera at once (for profiles like 0 = Event, 1 = Practice field).
const newPipelineSource = ref<string>("blank");
const newPipelineOnAllCameras = ref(false);
const newPipelineBusy = ref(false);
const newPipelineSourceItems = computed<SelectItem<string>[]>(() => [
  { name: "Blank (the defaults)", value: "blank" },
  ...Object.entries(useCameraSettingsStore().cameras).flatMap(([id, camera]) =>
    (camera.pipelineNicknames ?? []).map((pipeline, index) => ({
      name: `Copy of ${camera.nickname} / ${index}: ${pipeline}`,
      value: JSON.stringify([id, index])
    }))
  )
]);
const showCreatePipelineDialog = () => {
  newPipelineName.value = "";
  newPipelineType.value = useCameraSettingsStore().currentWebsocketPipelineType;
  newPipelineSource.value = "blank";
  newPipelineOnAllCameras.value = false;
  showPipelineCreationDialog.value = true;
};
interface CreatePipelineResult {
  created: { camera: string; index: number }[];
  skipped: { camera: string; reason: string }[];
}
const describeCreated = (name: string, result: CreatePipelineResult): string => {
  const byIndex = new Map<number, string[]>();
  for (const c of result.created) byIndex.set(c.index, [...(byIndex.get(c.index) ?? []), c.camera]);
  const parts = [...byIndex.entries()].map(([index, cameras]) => `as pipeline ${index} on ${cameras.join(", ")}`);
  const created = parts.length ? `Created '${name}' ${parts.join("; ")}.` : "Created nothing.";
  const skipped = result.skipped.map((s) => `${s.camera}: ${s.reason}`).join("; ");
  return skipped ? `${created} Skipped ${skipped}.` : created;
};
const createNewPipeline = async () => {
  const type = newPipelineType.value;
  const blank = newPipelineSource.value === "blank";
  if (blank && (type === WebsocketPipelineType.DriverMode || type === WebsocketPipelineType.Calib3d)) return;
  const current = useStateStore().currentCameraUniqueName;
  const [fromCamera, fromPipeline] = blank ? [undefined, undefined] : JSON.parse(newPipelineSource.value);
  const name = newPipelineName.value;
  newPipelineBusy.value = true;
  try {
    const response = await axios.post<CreatePipelineResult>("/settings/createPipeline", {
      name,
      type: blank ? type : undefined,
      fromCamera,
      fromPipeline,
      cameras: newPipelineOnAllCameras.value ? Object.keys(useCameraSettingsStore().cameras) : [current],
      switchCamera: current
    });
    showPipelineCreationDialog.value = false;
    useStateStore().showSnackbarMessage({
      message: describeCreated(name, response.data),
      color: response.data.skipped.length ? "warning" : "success",
      timeout: 8000
    });
  } catch (e) {
    const data = axios.isAxiosError(e) ? e.response?.data : undefined;
    useStateStore().showSnackbarMessage({
      message: data?.skipped ? describeCreated(name, data) : `Couldn't create the pipeline: ${data || e}`,
      color: "error",
      timeout: 8000
    });
  } finally {
    newPipelineBusy.value = false;
  }
};

// SpectrumJetson (photonvision-46): every camera to the pipeline number shown here. Cameras without
// that number stay where they are, as do cameras in driver mode or calibrating.
const switchAllCameras = () => {
  const store = useCameraSettingsStore();
  const current = useStateStore().currentCameraUniqueName;
  const index = store.currentCameraSettings.currentPipelineIndex;
  const name = store.currentPipelineSettings.pipelineNickname;
  const switched: string[] = [];
  const skipped: string[] = [];
  for (const [id, camera] of Object.entries(store.cameras)) {
    if (id === current) continue;
    if (camera.currentPipelineIndex === WebsocketPipelineType.DriverMode.valueOf()) {
      skipped.push(`${camera.nickname} (driver mode)`);
    } else if (
      camera.currentPipelineIndex === WebsocketPipelineType.Calib3d.valueOf() ||
      camera.currentPipelineIndex === WebsocketPipelineType.FocusCamera.valueOf()
    ) {
      skipped.push(`${camera.nickname} (calibrating or focusing)`);
    } else if ((camera.pipelineNicknames?.length ?? 0) <= index) {
      skipped.push(`${camera.nickname} (no pipeline ${index})`);
    } else {
      if (camera.currentPipelineIndex !== index) store.changeCurrentPipelineIndex(index, true, id);
      const theirs = camera.pipelineNicknames[index];
      switched.push(theirs === name ? camera.nickname : `${camera.nickname} (its ${index} is '${theirs}')`);
    }
  }
  const message =
    `Pipeline ${index}: ${switched.length ? `switched ${switched.join(", ")}` : "no other camera switched"}.` +
    (skipped.length ? ` Not switched: ${skipped.join(", ")}.` : "");
  useStateStore().showSnackbarMessage({ message, color: skipped.length ? "warning" : "success", timeout: 8000 });
};
const cancelPipelineCreation = () => {
  showPipelineCreationDialog.value = false;
  newPipelineName.value = "";
  newPipelineType.value = useCameraSettingsStore().currentWebsocketPipelineType;
};

// Pipeline Deletion
const showPipelineDeletionConfirmationDialog = ref(false);

// SpectrumJetson (photonvision-25): copy settings from another camera or pipeline into this one, or into
// the same pipeline number on every camera, instead of copying them by hand.
const COPY_GROUPS = [
  {
    value: "camera",
    name: "Camera: exposure, brightness, gain, white balance, contrast, gamma, sharpness, stream resolution, low latency"
  },
  { value: "apriltag", name: "AprilTag: decision margin, hamming, decimate, blur, threads, refinement, tag family" },
  { value: "output", name: "3D and multi-tag: 3D, multi-tag, single-tag fallback, drawing, target count" },
  { value: "resolution", name: "Resolution (only between cameras with the same video modes)" },
  { value: "objectDetection", name: "Object detection: model, confidence, NMS" }
];
const showCopySettingsDialog = ref(false);
const copyFromCamera = ref("");
const copyFromPipeline = ref(0);
const copyGroups = ref<string[]>(["camera", "apriltag", "output"]);
const copyToAllCameras = ref(false);
const copyBusy = ref(false);
const copyCameraItems = computed<SelectItem<string>[]>(() =>
  Object.keys(useCameraSettingsStore().cameras).map((id) => ({
    name: useCameraSettingsStore().cameras[id].nickname,
    value: id
  }))
);
const copyPipelineItems = computed<SelectItem<number>[]>(() =>
  (useCameraSettingsStore().cameras[copyFromCamera.value]?.pipelineNicknames ?? []).map((name, index) => ({
    name: `${index}: ${name}`,
    value: index
  }))
);
const openCopySettingsDialog = () => {
  const current = useStateStore().currentCameraUniqueName;
  const others = Object.keys(useCameraSettingsStore().cameras).filter((id) => id !== current);
  copyFromCamera.value = others[0] ?? current;
  copyFromPipeline.value = Math.max(0, useCameraSettingsStore().currentCameraSettings.currentPipelineIndex);
  showCopySettingsDialog.value = true;
};
const copySettings = async () => {
  copyBusy.value = true;
  const toPipeline = useCameraSettingsStore().currentCameraSettings.currentPipelineIndex;
  const targets = copyToAllCameras.value
    ? Object.keys(useCameraSettingsStore().cameras).filter(
        (id) =>
          (useCameraSettingsStore().cameras[id].pipelineNicknames?.length ?? 0) > toPipeline &&
          !(id === copyFromCamera.value && toPipeline === copyFromPipeline.value)
      )
    : [useStateStore().currentCameraUniqueName];
  const done: string[] = [];
  const failed: string[] = [];
  for (const toCamera of targets) {
    try {
      await axios.post("/settings/copySettings", {
        fromCamera: copyFromCamera.value,
        fromPipeline: copyFromPipeline.value,
        toCamera,
        toPipeline,
        groups: copyGroups.value
      });
      done.push(useCameraSettingsStore().cameras[toCamera].nickname);
    } catch (e) {
      const why = axios.isAxiosError(e) && typeof e.response?.data === "string" ? e.response.data : "failed";
      failed.push(`${useCameraSettingsStore().cameras[toCamera].nickname}: ${why}`);
    }
  }
  copyBusy.value = false;
  if (failed.length === 0) showCopySettingsDialog.value = false;
  useStateStore().showSnackbarMessage({
    message: failed.length ? `Copy failed for ${failed.join("; ")}` : `Copied settings into ${done.join(", ")}`,
    color: failed.length ? "error" : "success"
  });
};
const confirmDeleteCurrentPipeline = () => {
  useCameraSettingsStore().deleteCurrentPipeline();
  showPipelineDeletionConfirmationDialog.value = false;
};

// Pipeline Type Change
const showPipelineTypeChangeDialog = ref(false);
const pipelineTypesWrapper = computed<{ name: string; value: number }[]>(() => {
  const pipelineTypes = [
    { name: "Reflective", value: WebsocketPipelineType.Reflective },
    { name: "Colored Shape", value: WebsocketPipelineType.ColoredShape },
    { name: "AprilTag", value: WebsocketPipelineType.AprilTag },
    { name: "AprilTagCuda", value: WebsocketPipelineType.AprilTagCuda },
    { name: "ArUco", value: WebsocketPipelineType.Aruco }
  ];
  if (useSettingsStore().general.supportedBackends.length > 0) {
    pipelineTypes.push({ name: "Object Detection", value: WebsocketPipelineType.ObjectDetection });
  }
  // SpectrumJetson (photonvision-100): tags on every frame + TensorRT objects on some
  if (useSettingsStore().general.supportedBackends.includes("TENSORRT")) {
    pipelineTypes.push({ name: "AprilTag + Object", value: WebsocketPipelineType.AprilTagObject });
  }

  if (useCameraSettingsStore().isDriverMode) {
    pipelineTypes.push({ name: "Driver Mode", value: WebsocketPipelineType.DriverMode });
  }
  if (useCameraSettingsStore().isFocusMode) {
    pipelineTypes.push({ name: "Focus Mode", value: WebsocketPipelineType.FocusCamera });
  }
  if (useCameraSettingsStore().isCalibrationMode) {
    pipelineTypes.push({ name: "3D Calibration Mode", value: WebsocketPipelineType.Calib3d });
  }

  return pipelineTypes;
});
const pipelineType = ref<WebsocketPipelineType>(useCameraSettingsStore().currentWebsocketPipelineType);
const currentPipelineType = computed<WebsocketPipelineType>({
  get: () => {
    if (useCameraSettingsStore().isDriverMode) return WebsocketPipelineType.DriverMode;
    if (useCameraSettingsStore().isFocusMode) return WebsocketPipelineType.FocusCamera;
    if (useCameraSettingsStore().isCalibrationMode) return WebsocketPipelineType.Calib3d;
    return pipelineType.value;
  },
  set: (v) => {
    pipelineType.value = v;
  }
});
const confirmChangePipelineType = () => {
  const type = currentPipelineType.value;
  if (type === WebsocketPipelineType.DriverMode || type === WebsocketPipelineType.Calib3d) return;
  useCameraSettingsStore().changeCurrentPipelineType(type);
  showPipelineTypeChangeDialog.value = false;
};
const cancelChangePipelineType = () => {
  pipelineType.value = useCameraSettingsStore().currentWebsocketPipelineType;
  showPipelineTypeChangeDialog.value = false;
};

// Pipeline duplication'
const duplicateCurrentPipeline = () => {
  useCameraSettingsStore().duplicatePipeline(useCameraSettingsStore().currentCameraSettings.currentPipelineIndex);
};

// Change Props whenever the pipeline settings are changed
useCameraSettingsStore().$subscribe((mutation, state) => {
  const currentCameraSettings = state.cameras[useStateStore().currentCameraUniqueName];

  switch (currentCameraSettings.pipelineSettings.pipelineType) {
    case PipelineType.Reflective:
      pipelineType.value = WebsocketPipelineType.Reflective;
      break;
    case PipelineType.ColoredShape:
      pipelineType.value = WebsocketPipelineType.ColoredShape;
      break;
    case PipelineType.AprilTag:
      pipelineType.value = WebsocketPipelineType.AprilTag;
      break;
    case PipelineType.AprilTagCuda:
      pipelineType.value = WebsocketPipelineType.AprilTagCuda;
      break;
    case PipelineType.AprilTagObject:
      pipelineType.value = WebsocketPipelineType.AprilTagObject;
      break;
    case PipelineType.Aruco:
      pipelineType.value = WebsocketPipelineType.Aruco;
      break;
    case PipelineType.ObjectDetection:
      pipelineType.value = WebsocketPipelineType.ObjectDetection;
      break;
  }
});
const wrappedCameras = computed<SelectItem<string>[]>(() =>
  Object.keys(useCameraSettingsStore().cameras).map((cameraUniqueName) => ({
    name: useCameraSettingsStore().cameras[cameraUniqueName].nickname,
    value: cameraUniqueName
  }))
);
</script>

<template>
  <v-card color="surface" class="rounded-12">
    <v-row no-gutters class="pl-4 pt-2 pb-0">
      <v-col cols="10" class="pa-0">
        <pv-select
          v-if="!isCameraNameEdit"
          v-model="useStateStore().currentCameraUniqueName"
          label="Camera"
          :items="wrappedCameras"
          @update:modelValue="pipelineType = useCameraSettingsStore().currentWebsocketPipelineType"
        />
        <pv-input
          v-else
          v-model="currentCameraName"
          class="pt-2"
          :input-cols="12 - 3"
          :rules="[(v) => checkCameraName(v)]"
          label="Camera"
          @onEnter="saveCameraNameEdit"
          @onEscape="cancelCameraNameEdit"
        />
      </v-col>
      <v-col cols="2" style="display: flex; align-items: center; justify-content: center">
        <div v-if="isCameraNameEdit" style="display: flex; gap: 14px">
          <pv-icon
            icon-name="mdi-content-save"
            color="#c5c5c5"
            :disabled="checkCameraName(currentCameraName) !== true"
            @click="() => saveCameraNameEdit(currentCameraName)"
          />
          <pv-icon icon-name="mdi-cancel" color="red-darken-2" @click="cancelCameraNameEdit" />
        </div>
        <pv-icon
          v-else
          color="#c5c5c5"
          icon-name="mdi-pencil"
          tooltip="Edit Camera Name"
          @click="startCameraNameEdit"
        />
      </v-col>
    </v-row>
    <v-row no-gutters class="pl-4 pb-0 pt-0">
      <v-col cols="10" class="pa-0">
        <pv-select
          v-if="!isPipelineNameEdit"
          :model-value="useCameraSettingsStore().currentCameraSettings.currentPipelineIndex"
          label="Pipeline"
          tooltip="Each pipeline runs on a camera output and stores a unique set of processing settings"
          :disabled="
            useCameraSettingsStore().isDriverMode ||
            useCameraSettingsStore().isFocusMode ||
            useCameraSettingsStore().isCalibrationMode ||
            !useCameraSettingsStore().hasConnected
          "
          :items="pipelineNamesWrapper"
          @update:modelValue="(args) => useCameraSettingsStore().changeCurrentPipelineIndex(args, true)"
        />
        <pv-input
          v-else
          v-model="currentPipelineName"
          :input-cols="12 - 3"
          :rules="[(v) => checkPipelineName(v)]"
          label="Pipeline"
          @onEnter="(v) => savePipelineNameEdit(v)"
          @onEscape="cancelPipelineNameEdit"
        />
      </v-col>
      <v-col cols="2" class="pa-0" style="display: flex; align-items: center; justify-content: center">
        <div v-if="isPipelineNameEdit" style="display: flex; gap: 14px">
          <pv-icon
            icon-name="mdi-content-save"
            color="#c5c5c5"
            :disabled="checkPipelineName(currentPipelineName) !== true"
            @click="() => savePipelineNameEdit(currentPipelineName)"
          />
          <pv-icon icon-name="mdi-cancel" color="red-darken-2" @click="cancelPipelineNameEdit" />
        </div>
        <v-menu v-else-if="!useCameraSettingsStore().isDriverMode" offset="7">
          <template #activator="{ props }">
            <v-icon color="#c5c5c5" v-bind="props" @click="cancelPipelineNameEdit"> mdi-menu </v-icon>
          </template>
          <v-list density="compact" color="primary">
            <v-list-item @click="startPipelineNameEdit">
              <v-list-item-title>
                <pv-icon color="#c5c5c5" :right="true" icon-name="mdi-pencil" tooltip="Edit pipeline name" />
              </v-list-item-title>
            </v-list-item>
            <v-list-item @click="duplicateCurrentPipeline">
              <v-list-item-title>
                <pv-icon color="#c5c5c5" :right="true" icon-name="mdi-content-copy" tooltip="Duplicate pipeline" />
              </v-list-item-title>
            </v-list-item>
            <v-list-item @click="switchAllCameras">
              <v-list-item-title>
                <pv-icon
                  color="#c5c5c5"
                  :right="true"
                  icon-name="mdi-swap-horizontal"
                  :tooltip="`Switch every camera to pipeline ${useCameraSettingsStore().currentCameraSettings.currentPipelineIndex}`"
                />
              </v-list-item-title>
            </v-list-item>
            <v-list-item @click="openCopySettingsDialog">
              <v-list-item-title>
                <pv-icon
                  color="#c5c5c5"
                  :right="true"
                  icon-name="mdi-clipboard-arrow-down-outline"
                  tooltip="Copy settings from another camera or pipeline"
                />
              </v-list-item-title>
            </v-list-item>
            <v-list-item @click="showCreatePipelineDialog">
              <v-list-item-title>
                <pv-icon color="green" :right="true" icon-name="mdi-plus" tooltip="Add new pipeline" />
              </v-list-item-title>
            </v-list-item>
            <v-list-item @click="showPipelineDeletionConfirmationDialog = true">
              <v-list-item-title>
                <pv-icon
                  color="red-darken-2"
                  :right="true"
                  icon-name="mdi-trash-can-outline"
                  tooltip="Delete pipeline"
                />
              </v-list-item-title>
            </v-list-item>
          </v-list>
        </v-menu>
        <pv-icon
          v-else-if="useCameraSettingsStore().isDriverMode && useCameraSettingsStore().pipelineNames.length === 0"
          color="#c5c5c5"
          :right="true"
          icon-name="mdi-plus"
          tooltip="Add new pipeline"
          @click="showCreatePipelineDialog"
        />
      </v-col>
    </v-row>
    <v-row no-gutters class="pl-4 pt-0 pb-4">
      <v-col cols="10" class="pa-0">
        <pv-select
          v-model="currentPipelineType"
          label="Type"
          tooltip="Changes the pipeline type, which changes the type of processing that will happen on input frames"
          :disabled="
            useCameraSettingsStore().isDriverMode ||
            useCameraSettingsStore().isFocusMode ||
            useCameraSettingsStore().isCalibrationMode ||
            !useCameraSettingsStore().hasConnected
          "
          :items="pipelineTypesWrapper"
          @update:modelValue="showPipelineTypeChangeDialog = true"
        />
      </v-col>
    </v-row>
    <v-dialog v-model="showCopySettingsDialog" width="600">
      <v-card color="surface">
        <v-card-title class="pb-0">
          Copy settings into {{ useCameraSettingsStore().currentCameraSettings.nickname }} /
          {{ useCameraSettingsStore().currentPipelineSettings.pipelineNickname }}
        </v-card-title>
        <v-card-text class="pt-2 pb-0">
          <pv-select v-model="copyFromCamera" label="From camera" :select-cols="8" :items="copyCameraItems" />
          <pv-select v-model="copyFromPipeline" label="From pipeline" :select-cols="8" :items="copyPipelineItems" />
          <p class="pt-3 pb-1">Settings to copy:</p>
          <v-checkbox
            v-for="group in COPY_GROUPS"
            :key="group.value"
            v-model="copyGroups"
            :value="group.value"
            :label="group.name"
            density="compact"
            hide-details
          />
          <v-checkbox
            v-model="copyToAllCameras"
            class="pt-2"
            density="compact"
            hide-details
            :label="`Also copy into pipeline ${useCameraSettingsStore().currentCameraSettings.currentPipelineIndex} of every other camera`"
          />
          <p class="pt-2 text-caption">Orientation, names and each camera's exposure limits are never copied.</p>
        </v-card-text>
        <v-card-actions class="pr-5 pt-10px pb-5">
          <v-btn
            color="buttonPassive"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            @click="showCopySettingsDialog = false"
          >
            Cancel
          </v-btn>
          <v-btn
            color="buttonActive"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            :disabled="copyBusy || copyGroups.length === 0"
            @click="copySettings"
          >
            Copy
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <v-dialog v-model="showPipelineCreationDialog" persistent width="500">
      <v-card color="surface">
        <v-card-title class="pb-0"> Create New Pipeline </v-card-title>
        <v-card-text class="pt-0 pb-0">
          <pv-input
            v-model="newPipelineName"
            placeholder="Pipeline Name"
            :label-cols="4"
            :input-cols="12 - 4"
            label="Pipeline Name"
            :rules="[(v) => checkPipelineName(v)]"
          />
          <pv-select
            v-model="newPipelineSource"
            :select-cols="12 - 4"
            label="Start from"
            tooltip="Blank starts from the defaults. A copy takes every setting of that pipeline, including its type. From another camera, each camera keeps its own orientation, exposure limits, mask and, unless the video modes match, resolution."
            :items="newPipelineSourceItems"
          />
          <pv-select
            v-if="newPipelineSource === 'blank'"
            v-model="newPipelineType"
            :select-cols="12 - 4"
            label="Tracking Type"
            tooltip="Pipeline type, which changes the type of processing that will happen on input frames"
            :items="validNewPipelineTypes"
          />
          <v-checkbox
            v-model="newPipelineOnAllCameras"
            density="compact"
            hide-details
            :label="`Create on every camera (${Object.keys(useCameraSettingsStore().cameras).length})`"
          />
          <p v-if="newPipelineOnAllCameras" class="text-caption pb-2">
            Each camera gets it as its next pipeline number, so cameras with the same number of pipelines get the same
            number. Only this camera switches to it.
          </p>
        </v-card-text>
        <v-card-actions class="pr-5 pt-10px pb-5">
          <v-btn
            color="buttonPassive"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            @click="cancelPipelineCreation"
          >
            Cancel
          </v-btn>
          <v-btn
            color="buttonActive"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            :disabled="newPipelineBusy || checkPipelineName(newPipelineName) !== true"
            @click="createNewPipeline"
          >
            Create
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <pv-delete-modal
      v-model="showPipelineDeletionConfirmationDialog"
      :width="500"
      title="Delete Pipeline"
      description="Are you sure you want to delete the current pipeline? This action cannot be undone."
      :on-confirm="confirmDeleteCurrentPipeline"
    />
    <v-dialog v-model="showPipelineTypeChangeDialog" persistent width="600">
      <v-card color="surface" dark>
        <v-card-title class="pb-0">Change Pipeline Type</v-card-title>
        <v-card-text>
          Are you sure you want to change the current pipeline type? This will cause all the pipeline settings to be
          overwritten and they will be lost. If this isn't what you want, duplicate this pipeline first or export
          settings.
        </v-card-text>
        <v-card-actions class="pa-5 pt-0">
          <v-btn
            color="buttonPassive"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            class="text-black"
            @click="cancelChangePipelineType"
          >
            Cancel
          </v-btn>
          <v-btn
            color="buttonActive"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            @click="confirmChangePipelineType"
          >
            Confirm
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-card>
</template>
