<script setup lang="ts">
// SpectrumJetson Rewind: records each camera's own MJPEG frames to the SSD while robot code sets
// /photonvision/rewind/record (or "Record now" here, for bench tests). See docs/REWIND.md.
// photonvision-07, with 75 (file names), 78 (record through disconnects) and 65 (why a delete failed).
import { onBeforeUnmount, onMounted, ref } from "vue";
import axios from "axios";
import { oneAtATime, POLL_TIMEOUT_MS } from "@/lib/Poll";
import PvSwitch from "@/components/common/pv-switch.vue";
import { useStateStore } from "@/stores/StateStore";
import { useTheme } from "vuetify";

const theme = useTheme();

type RewindCamera = { camera: string; fps: number; frames: number; MB: number; problem: string };
type RewindStatus = {
  recording: boolean;
  session: string;
  reason: string;
  seconds: number;
  stopReason: string;
  manual: boolean;
  robotConnected: boolean;
  robotRequest: boolean;
  diskLow: boolean;
  freeGB: number;
  usedGB: number;
  sessionCount: number;
  settings: { fps: number; quotaGB: number; minFreeGB: number; recordThroughDisconnects: boolean };
  cameras: RewindCamera[];
  recent: { name: string; MB: number }[];
};

const status = ref<RewindStatus | null>(null);
const busy = ref(false);
let timer: ReturnType<typeof setInterval> | undefined;

// photonvision-67: one request in flight, with a timeout (Poll.ts).
const load = oneAtATime(async () => {
  try {
    status.value = (await axios.get("/rewind", { timeout: POLL_TIMEOUT_MS })).data;
  } catch {
    status.value = null;
  }
});
const post = async (body: object, message: string) => {
  busy.value = true;
  try {
    status.value = (await axios.post("/rewind", body)).data;
    useStateStore().showSnackbarMessage({ message, color: "success" });
  } catch (e) {
    useStateStore().showSnackbarMessage({
      message:
        axios.isAxiosError(e) && typeof e.response?.data === "string" ? e.response.data : "Rewind request failed",
      color: "error"
    });
    await load();
  } finally {
    busy.value = false;
  }
};
const setManual = (on: boolean) => post({ manual: on }, on ? "Recording (bench)" : "Bench recording stopped");
const setThroughDisconnects = (on: boolean) =>
  post(
    { recordThroughDisconnects: on },
    on ? "Robot recordings now continue through disconnects" : "Only FMS matches continue through disconnects"
  );
const deleteSession = (name: string) => {
  if (window.confirm(`Delete recording ${name}?`)) void post({ delete: name }, `Deleted ${name}`);
};
const downloadUrl = (name: string) => `/api/rewind/download?session=${encodeURIComponent(name)}`;
const fmtTime = (s: number) => `${Math.floor(s / 60)}:${String(Math.floor(s % 60)).padStart(2, "0")}`;

onMounted(() => {
  void load();
  timer = setInterval(() => void load(), 2000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>

<template>
  <v-card v-if="status" class="mb-3 rounded-12" color="surface">
    <v-card-title class="pb-10px">Rewind (camera recording)</v-card-title>
    <v-card-text class="pt-0">
      <v-alert
        class="mb-3"
        density="compact"
        :color="status.recording ? 'error' : 'buttonActive'"
        :icon="status.recording ? 'mdi-record-rec' : 'mdi-information-outline'"
        :variant="theme.global.current.value.dark ? 'tonal' : 'elevated'"
      >
        <template v-if="status.recording">
          Recording <b>{{ status.session }}</b> ({{ status.reason === "robot" ? "robot code asked" : "bench" }},
          {{ fmtTime(status.seconds) }})
        </template>
        <template v-else-if="status.diskLow">Not recording: the SSD is nearly full.</template>
        <template v-else>
          Not recording. Robot code starts recording by setting <code>/photonvision/rewind/record</code> to true{{
            status.robotConnected ? "" : " (no robot connected)"
          }}.
          <span v-if="status.stopReason">Last stop: {{ status.stopReason }}.</span>
        </template>
      </v-alert>

      <pv-switch
        :model-value="status.manual"
        :disabled="busy"
        label="Record now (bench)"
        tooltip="Records every camera until turned off, for bench tests. Robot code normally controls recording."
        :label-cols="4"
        @update:model-value="(v) => setManual(!!v)"
      />
      <pv-switch
        :model-value="status.settings.recordThroughDisconnects"
        :disabled="busy"
        label="Record through disconnects (events)"
        tooltip="If the robot disconnects mid-recording, keep recording until it is back and says stop, up to 5 min, as FMS matches always do. On at events for practice matches, off on the bench."
        :label-cols="4"
        @update:model-value="(v) => setThroughDisconnects(!!v)"
      />

      <v-table v-if="status.recording" density="compact" class="mb-3">
        <thead>
          <tr>
            <th>Camera</th>
            <th>Saved fps</th>
            <th>Frames</th>
            <th>MB</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in status.cameras" :key="c.camera">
            <td>{{ c.camera }}</td>
            <td>{{ c.fps }}</td>
            <td>{{ c.frames }}</td>
            <td>{{ c.MB }}</td>
            <td class="text-error">{{ c.problem }}</td>
          </tr>
        </tbody>
      </v-table>

      <div class="mb-2">
        {{ status.sessionCount }} recording(s), {{ status.usedGB.toFixed(1) }} GB of {{ status.settings.quotaGB }} GB
        allowed; {{ status.freeGB.toFixed(0) }} GB free on the SSD. Saving {{ status.settings.fps }} frames/s per
        camera. The oldest recordings are deleted past the limit. <v-icon size="small">mdi-download</v-icon> downloads a
        recording as a zip of one video (.avi) per camera. A match is about 1 GB: download over Ethernet or USB, not the
        robot radio.
      </div>
      <v-table v-if="status.recent.length" density="compact">
        <thead>
          <tr>
            <th>Recent recordings</th>
            <th>MB</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="r in status.recent" :key="r.name">
            <td>{{ r.name }}</td>
            <td>{{ r.MB }}</td>
            <td class="text-no-wrap">
              <template v-if="r.name !== status.session">
                <!-- A plain link: the browser saves the zip straight to disk as it streams. -->
                <v-btn
                  size="x-small"
                  variant="text"
                  icon="mdi-download"
                  title="Download (a zip of one .avi per camera, plus frame times)"
                  :href="downloadUrl(r.name)"
                  :download="`${r.name}.zip`"
                />
                <v-btn
                  size="x-small"
                  variant="text"
                  icon="mdi-delete"
                  title="Delete"
                  :disabled="busy"
                  @click="deleteSession(r.name)"
                />
              </template>
            </td>
          </tr>
        </tbody>
      </v-table>
    </v-card-text>
  </v-card>
</template>
