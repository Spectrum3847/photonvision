@@ -0,0 +1,272 @@
<script setup lang="ts">
// SpectrumJetson (photonvision-87): which firmware each Thriftiest Cam runs, warns when it can't
// recover from a USB drop by itself, and flashes a stored firmware file onto one. Flashing stops
// PhotonVision for about a minute (every camera) and starts it again; the card picks up again when
// this page reconnects.
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import axios from "axios";
import { useStateStore } from "@/stores/StateStore";
import { useTheme } from "vuetify";

interface Camera {
  port: string;
  serial: string;
  version: string;
  variant: string | null;
  label: string;
  recovers: boolean;
  camera: string;
}
interface Image {
  file: string;
  bytes: number;
  variant: string | null;
  label: string | null;
  recovers: boolean;
  error: string | null;
}
interface Job {
  state: "running" | "done" | "failed";
  message: string;
  port: string;
  image: string;
  started: number;
  updated: number;
  log: string[];
}
interface Status {
  cameras: Camera[];
  images: Image[];
  job: Job | null;
  robotEnabled: boolean;
  error?: string;
}

const theme = useTheme();
const status = ref<Status>({ cameras: [], images: [], job: null, robotEnabled: false });
const chosen = ref<Record<string, string>>({});
const upload = ref<File[] | File | null>(null);
const busy = ref(false);
const confirm = ref<{ cam: Camera; image: Image } | null>(null);

const say = (message: string, color = "success") =>
  useStateStore().showSnackbarMessage({ message, color, timeout: 8000 });

const load = async () => {
  try {
    status.value = (await axios.get<Status>("/thriftiest/firmware")).data;
    // Default choice: the newest file that recovers from USB drops.
    const best = status.value.images
      .filter((i) => i.recovers)
      .map((i) => i.file)
      .sort()
      .pop();
    for (const c of status.value.cameras) if (!chosen.value[c.port] && best) chosen.value[c.port] = best;
  } catch {
    // PhotonVision is restarting (a flash): the poll below tries again.
  }
};
let timer: ReturnType<typeof setInterval> | undefined;
onMounted(() => {
  void load();
  timer = setInterval(() => void load(), 3000);
});
onUnmounted(() => clearInterval(timer));
watch(
  () => useStateStore().backendConnected,
  (connected) => {
    if (connected) void load();
  }
);

const running = computed(
  () => status.value.job?.state === "running" && Date.now() / 1000 - status.value.job.updated < 120
);
const needsRecovery = computed(() => status.value.cameras.filter((c) => !c.recovers));
// The server's reason (plain text), or this.
const errorText = (e: unknown, fallback: string): string =>
  axios.isAxiosError(e) && typeof e.response?.data === "string" && e.response.data ? e.response.data : fallback;
const imageByFile = (f: string) => status.value.images.find((i) => i.file === f);

const sendUpload = async () => {
  const f = Array.isArray(upload.value) ? upload.value[0] : upload.value;
  if (!f) return;
  busy.value = true;
  try {
    const form = new FormData();
    form.append("data", f);
    const check = (await axios.post<Image>("/thriftiest/firmware/upload", form)).data;
    say(`Stored ${check.file}: ${check.label ?? "unknown variant"}`);
    upload.value = null;
    await load();
  } catch (e) {
    say(errorText(e, "Couldn't store the firmware file"), "error");
  } finally {
    busy.value = false;
  }
};
const remove = async (i: Image) => {
  try {
    status.value = (await axios.post<Status>("/thriftiest/firmware/delete", { image: i.file })).data;
  } catch (e) {
    say(errorText(e, "Couldn't delete"), "error");
  }
};
const flash = async (cam: Camera, image: Image) => {
  confirm.value = null;
  busy.value = true;
  try {
    await axios.post("/thriftiest/firmware/flash", { port: cam.port, image: image.file });
    say(
      `Flashing ${image.label ?? image.file} onto ${cam.camera || "the camera"} (port ${cam.port}): PhotonVision restarts in about a minute.`,
      "info"
    );
    await load();
  } catch (e) {
    say(errorText(e, "Couldn't start the flash"), "error");
  } finally {
    busy.value = false;
  }
};
</script>

<template>
  <v-card class="mb-3 rounded-12" color="surface" data-testid="thriftiest-firmware">
    <v-card-title class="pb-10px">Thriftiest Cam firmware</v-card-title>
    <v-card-text class="pt-0">
      <p class="pb-2 text-body-2">
        Which firmware each Thriftiest Cam runs. Only the "usbrecov" firmware comes back by itself from a USB drop (a
        static shock, a loose cable); on any other a dropped camera stays down until it's replugged. Upload a firmware
        file once, then flash a camera: PhotonVision stops for about a minute (every camera) and starts again. Robot
        disabled. A backup of the camera's flash is saved first.
      </p>
      <v-alert v-if="needsRecovery.length" type="warning" variant="tonal" density="compact" class="mb-3">
        {{ needsRecovery.map((c) => c.camera || `port ${c.port}`).join(", ") }}: no USB recovery. Flash the usbrecov
        firmware.
      </v-alert>
      <v-alert v-if="status.error" type="error" variant="tonal" density="compact" class="mb-3">{{
        status.error
      }}</v-alert>
      <v-alert
        v-if="status.job"
        :type="status.job.state === 'failed' ? 'error' : status.job.state === 'done' ? 'success' : 'info'"
        variant="tonal"
        density="compact"
        class="mb-3"
        data-testid="thriftiest-job"
      >
        <b>{{ status.job.image }} → port {{ status.job.port }}:</b> {{ status.job.message }}
        <pre v-if="status.job.state === 'failed' && status.job.log.length" class="text-caption mt-1">{{
          status.job.log.join("\n")
        }}</pre>
      </v-alert>

      <v-table v-if="status.cameras.length" density="compact" class="mb-3">
        <thead>
          <tr>
            <th>Camera</th>
            <th>Port</th>
            <th>Serial</th>
            <th>Firmware</th>
            <th>Flash</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="c in status.cameras" :key="c.port" :data-testid="`thriftiest-cam-${c.port}`">
            <td>{{ c.camera || "–" }}</td>
            <td>{{ c.port }}</td>
            <td>{{ c.serial }}</td>
            <td>
              {{ c.label }}
              <v-chip :color="c.recovers ? 'success' : 'warning'" size="x-small" class="ml-1">
                {{ c.recovers ? "recovers from USB drops" : "no USB recovery" }}
              </v-chip>
            </td>
            <td class="text-no-wrap">
              <div class="d-flex align-center ga-2">
                <v-select
                  v-model="chosen[c.port]"
                  :items="
                    status.images.filter((i) => !i.error).map((i) => ({ title: i.label ?? i.file, value: i.file }))
                  "
                  density="compact"
                  variant="underlined"
                  hide-details
                  style="min-width: 220px"
                />
                <v-btn
                  color="buttonActive"
                  variant="tonal"
                  size="small"
                  :disabled="busy || running || status.robotEnabled || !chosen[c.port]"
                  :data-testid="`thriftiest-flash-${c.port}`"
                  @click="confirm = { cam: c, image: imageByFile(chosen[c.port])! }"
                >
                  Flash
                </v-btn>
              </div>
            </td>
          </tr>
        </tbody>
      </v-table>
      <p v-else class="text-caption pb-3">No Thriftiest Cams connected.</p>
      <p v-if="status.robotEnabled" class="text-caption pb-2">Disable the robot to flash.</p>

      <div class="text-subtitle-2 pb-1">Firmware files on the Jetson</div>
      <v-table v-if="status.images.length" density="compact" class="mb-2">
        <tbody>
          <tr v-for="i in status.images" :key="i.file">
            <td>{{ i.file }}</td>
            <td>{{ i.error ? `not a Thriftiest Cam image: ${i.error}` : (i.label ?? "unknown variant") }}</td>
            <td class="text-right">
              <v-btn
                size="small"
                variant="text"
                color="error"
                icon="mdi-delete"
                :disabled="running"
                @click="remove(i)"
              />
            </td>
          </tr>
        </tbody>
      </v-table>
      <p v-else class="text-caption pb-2">
        None yet: upload one (e.g. ThriftiestCam-Firmware-v5.13-PATCH-usbrecov.bin).
      </p>
      <div class="d-flex align-center ga-2">
        <v-file-input
          v-model="upload"
          accept=".bin"
          label="Firmware file (.bin)"
          density="compact"
          variant="underlined"
          hide-details
          prepend-icon="mdi-chip"
        />
        <v-btn color="buttonActive" variant="tonal" :disabled="busy || !upload" @click="sendUpload">Upload</v-btn>
      </div>
    </v-card-text>

    <v-dialog :model-value="!!confirm" width="520" @update:model-value="(v) => !v && (confirm = null)">
      <v-card v-if="confirm" color="surface">
        <v-card-title>Flash {{ confirm.cam.camera || `port ${confirm.cam.port}` }}?</v-card-title>
        <v-card-text>
          <b>{{ confirm.image.label ?? confirm.image.file }}</b> onto the Thriftiest Cam on USB port
          {{ confirm.cam.port }} (serial {{ confirm.cam.serial }}, now {{ confirm.cam.label }}). PhotonVision stops for
          about a minute, so every camera is off, then starts again. Only the parts of the flash that differ are
          rewritten, a backup is saved first, and the camera keeps its serial. Keep the robot powered until it's done.
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'" @click="confirm = null"
            >Cancel</v-btn
          >
          <v-btn
            color="warning"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            data-testid="thriftiest-flash-confirm"
            @click="flash(confirm.cam, confirm.image)"
          >
            Flash and restart
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-card>
</template>
