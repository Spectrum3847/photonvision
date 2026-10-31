<script setup lang="ts">
import { useSettingsStore } from "@/stores/settings/GeneralSettingsStore";
import type { FieldTag, Quaternion } from "@/types/PhotonTrackingTypes";
import { toDeg } from "@/lib/MathUtils";
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import axios from "axios";
import { oneAtATime, POLL_TIMEOUT_MS } from "@/lib/Poll";
import { useStateStore } from "@/stores/StateStore";
import { useTheme } from "vuetify";
const { Euler, Quaternion: ThreeQuat } = await import("three");
const theme = useTheme();

// SpectrumJetson (photonvision-22): tags left out of multi-tag on every camera. List the bad ones.
// (2026's photonvision-67 poll guard, feature 13, isn't ported: a plain 2 s poll for now.)
type ExcludedTags = { saved: number[]; robot: number[]; active: number[] };
const excluded = ref<ExcludedTags | null>(null);
const excludedText = ref("");
const excludedBusy = ref(false);
const excludedTextValid = (text: string) => /^[\d,\s]*$/.test(text);
// photonvision-67: one request in flight, with a timeout (Poll.ts).
const loadExcluded = oneAtATime(async (updateText: boolean = false) => {
  try {
    excluded.value = (await axios.get("/excludedTags", { timeout: POLL_TIMEOUT_MS })).data;
    if (updateText && excluded.value) excludedText.value = excluded.value.saved.join(", ");
  } catch {
    excluded.value = null;
  }
});
const saveExcluded = async () => {
  excludedBusy.value = true;
  try {
    const saved = excludedText.value
      .split(/[,\s]+/)
      .filter((part) => part.length > 0)
      .map((part) => parseInt(part, 10));
    excluded.value = (await axios.post("/excludedTags", { saved })).data;
    excludedText.value = excluded.value?.saved.join(", ") ?? excludedText.value;
    useStateStore().showSnackbarMessage({ message: "Saved the tags to leave out of multi-tag", color: "success" });
  } catch (e) {
    useStateStore().showSnackbarMessage({
      message:
        axios.isAxiosError(e) && typeof e.response?.data === "string"
          ? e.response.data
          : "Could not save the tags to leave out",
      color: "error"
    });
  } finally {
    excludedBusy.value = false;
  }
};
const isExcluded = (id: number) => excluded.value?.active.includes(id) ?? false;
let excludedTimer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  void loadExcluded(true);
  excludedTimer = setInterval(() => void loadExcluded(false), 2000); // the robot's list can change
});
onBeforeUnmount(() => clearInterval(excludedTimer));

const currentFieldLayout = computed(() => useSettingsStore().currentFieldLayout);
const fieldWidth = computed(() => currentFieldLayout.value["field-dimensions"].width);
const fieldLength = computed(() => currentFieldLayout.value["field-dimensions"].length);
const fieldTags = computed<FieldTag[]>(() => currentFieldLayout.value["field-tags"] ?? []);

const quaternionToEuler = (rot_quat: Quaternion): { x: number; y: number; z: number } => {
  const quat = new ThreeQuat(rot_quat.X, rot_quat.Y, rot_quat.Z, rot_quat.W);
  const euler = new Euler().setFromQuaternion(quat, "ZYX");

  return { x: toDeg(euler.x), y: toDeg(euler.y), z: toDeg(euler.z) };
};
</script>

<template>
  <v-card color="surface" class="rounded-12">
    <v-card-title>AprilTag Field Layout</v-card-title>
    <v-card-text class="pt-0">
      <p>Field width: {{ fieldWidth.toFixed(2) }} meters</p>
      <p>Field length: {{ fieldLength.toFixed(2) }} meters</p>

      <div class="pt-4 pb-4">
        <p class="pb-2">
          <b>Tags left out of multi-tag</b> (every camera). List the bad tags, e.g. one mounted wrong at an event.
          They're still reported as targets.
        </p>
        <div class="d-flex align-center">
          <v-text-field
            v-model="excludedText"
            density="compact"
            label="Tag IDs, e.g. 7, 12"
            hide-details="auto"
            :rules="[(v: string) => excludedTextValid(v) || 'Numbers, commas and spaces only']"
            :disabled="excludedBusy"
            class="pr-3"
          />
          <v-btn
            color="buttonActive"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            :disabled="excludedBusy || !excludedTextValid(excludedText)"
            @click="saveExcluded"
          >
            Save
          </v-btn>
        </div>
        <p v-if="excluded && excluded.robot.length" class="pt-2">Robot code adds: {{ excluded.robot.join(", ") }}</p>
        <p class="pt-2">
          Left out now: {{ excluded ? (excluded.active.length ? excluded.active.join(", ") : "none") : "unknown" }}
        </p>
      </div>

      <!-- Simple table height must be set here and in the CSS for the fixed-header to work -->
      <v-table fixed-header height="100%" density="compact">
        <template #default>
          <thead style="font-size: 1.25rem">
            <tr>
              <th class="text-center">ID</th>
              <th class="text-center">X meters</th>
              <th class="text-center">Y meters</th>
              <th class="text-center">Z meters</th>
              <th class="text-center">θ<sub>x</sub>&deg;</th>
              <th class="text-center">θ<sub>y</sub>&deg;</th>
              <th class="text-center">θ<sub>z</sub>&deg;</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(tag, index) in fieldTags" :key="index" :style="isExcluded(tag.ID) ? 'opacity: 0.4' : ''">
              <td>{{ tag.ID }}{{ isExcluded(tag.ID) ? " (left out)" : "" }}</td>
              <td v-for="(val, idx) in Object.values(tag.pose.translation)" :key="idx">{{ val.toFixed(2) }}&nbsp;m</td>
              <td v-for="(val, idx) in Object.values(quaternionToEuler(tag.pose.rotation.quaternion))" :key="idx + 4">
                {{ val.toFixed(2) }}&deg;
              </td>
            </tr>
          </tbody>
        </template>
      </v-table>
    </v-card-text>
  </v-card>
</template>

<style scoped lang="scss">
.v-table {
  width: 100%;
  height: 100%;
  text-align: center;

  th,
  td {
    font-size: 1rem !important;
    color: white !important;
  }

  td {
    font-family: monospace !important;
  }

  ::-webkit-scrollbar {
    width: 0;
    height: 0.55em;
    border-radius: 5px;
  }

  ::-webkit-scrollbar-track {
    -webkit-box-shadow: inset 0 0 6px rgba(0, 0, 0, 0.3);
    border-radius: 10px;
  }

  ::-webkit-scrollbar-thumb {
    background-color: rgb(var(--v-theme-accent));
    border-radius: 10px;
  }
}
</style>
