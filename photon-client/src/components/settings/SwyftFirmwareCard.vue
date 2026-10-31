<script setup lang="ts">
// SpectrumJetson: which Swyft USB Cameras are connected, and flashes a stored firmware file (Swyft's
// .rfw download) onto one, optionally with a new serial number. Flashing stops PhotonVision for under
// a minute (every camera) and starts it again; the card picks up again when this page reconnects.
// After a flash the camera's video stays off until the robot is power cycled: a Jetson reboot keeps
// USB power on and isn't enough (docs/SWYFT-CAMERA-FIRMWARE.md).
import { computed, onMounted, onUnmounted, ref, watch } from "vue";
import axios from "axios";
import { useStateStore } from "@/stores/StateStore";
import { useTheme } from "vuetify";

interface Camera {
  port: string;
  bootLoader: boolean;
  serial: string | null;
  revision: string | null;
  camera: string;
}
interface Image {
  file: string;
  bytes: number;
  serial: string | null;
  image: string | null;
  label: string | null;
  error: string | null;
}
interface Job {
  state: "running" | "done" | "failed";
  message: string;
  port: string;
  image: string;
  serial: string;
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
const confirm = ref<{ cam: Camera; image: Image; serial: string } | null>(null);

const say = (message: string, color = "success") =>
  useStateStore().showSnackbarMessage({ message, color, timeout: 8000 });

const load = async () => {
  try {
    status.value = (await axios.get<Status>("/swyft/firmware")).data;
    // Default choice: a low exposure image, else the newest good one.
    const good = status.value.images.filter((i) => !i.error);
    const best = (good.find((i) => i.image === "low-exposure") ?? good[good.length - 1])?.file;
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
const inBootLoader = computed(() => status.value.cameras.filter((c) => c.bootLoader));
// The server's reason (plain text), or this.
const errorText = (e: unknown, fallback: string): string =>
  axios.isAxiosError(e) && typeof e.response?.data === "string" && e.response.data ? e.response.data : fallback;
const imageByFile = (f: string) => status.value.images.find((i) => i.file === f);
const validSerial = (s: string) => s === "" || /^[A-Za-z0-9._-]{1,30}$/.test(s);

const sendUpload = async () => {
  const f = Array.isArray(upload.value) ? upload.value[0] : upload.value;
  if (!f) return;
  busy.value = true;
  try {
    const form = new FormData();
    form.append("data", f);
    const check = (await axios.post<Image>("/swyft/firmware/upload", form)).data;
    say(`Stored ${check.file}: ${check.label ?? "unknown image"}`);
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
    status.value = (await axios.post<Status>("/swyft/firmware/delete", { image: i.file })).data;
  } catch (e) {
    say(errorText(e, "Couldn't delete"), "error");
  }
};
const flash = async (cam: Camera, image: Image, serial: string) => {
  confirm.value = null;
  busy.value = true;
  try {
    await axios.post("/swyft/firmware/flash", { port: cam.port, image: image.file, serial: serial.trim() });
    say(
      `Flashing ${image.label ?? image.file} onto ${cam.camera || "the camera"} (port ${cam.port}): PhotonVision restarts in under a minute. Then power cycle the robot.`,
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
  <v-card class="mb-3 rounded-12" color="surface" data-testid="swyft-firmware">
    <v-card-title class="pb-10px">Swyft camera firmware</v-card-title>
    <v-card-text class="pt-0">
      <p class="pb-2 text-body-2">
        The Swyft USB Cameras on the Jetson. Swyft's low exposure firmware lets the exposure go down to 0.244 ms (stock:
        3.91 ms), and a flash can give the camera a serial number of its own. Upload a firmware file (.rfw) once, then
        flash a camera: PhotonVision stops for under a minute (every camera) and starts again. Robot disabled. A backup
        of the camera's flash is saved first.
        <b>Afterwards the camera sends no video until the robot is power cycled</b>
        (main breaker): a Jetson reboot isn't enough.
      </p>
      <v-alert v-if="inBootLoader.length" type="warning" variant="tonal" density="compact" class="mb-3">
        {{ inBootLoader.map((c) => c.camera || `port ${c.port}`).join(", ") }}: stuck in its boot loader. Flash it again
        (keep it plugged in), or power cycle the robot.
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
        data-testid="swyft-job"
      >
        <b
          >{{ status.job.image }} → port {{ status.job.port
          }}{{ status.job.serial ? ` (serial ${status.job.serial})` : "" }}:</b
        >
        {{ status.job.message }}
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
          <tr v-for="c in status.cameras" :key="c.port" :data-testid="`swyft-cam-${c.port}`">
            <td>{{ c.camera || "–" }}</td>
            <td>{{ c.port }}</td>
            <td>{{ c.serial ?? "–" }}</td>
            <td>
              <v-chip v-if="c.bootLoader" color="warning" size="x-small">in the boot loader</v-chip>
              <span v-else>revision {{ c.revision }}</span>
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
                  :data-testid="`swyft-flash-${c.port}`"
                  @click="confirm = { cam: c, image: imageByFile(chosen[c.port])!, serial: c.serial ?? '' }"
                >
                  Flash
                </v-btn>
              </div>
            </td>
          </tr>
        </tbody>
      </v-table>
      <p v-else class="text-caption pb-3">No Swyft cameras connected.</p>
      <p v-if="status.robotEnabled" class="text-caption pb-2">Disable the robot to flash.</p>

      <div class="text-subtitle-2 pb-1">Firmware files on the Jetson</div>
      <v-table v-if="status.images.length" density="compact" class="mb-2">
        <tbody>
          <tr v-for="i in status.images" :key="i.file">
            <td>{{ i.file }}</td>
            <td>{{ i.error ? `not a Swyft camera image: ${i.error}` : (i.label ?? "unknown image") }}</td>
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
      <p v-else class="text-caption pb-2">None yet: upload one (e.g. LowExposureSWYFTUSBOV9281.rfw).</p>
      <div class="d-flex align-center ga-2">
        <v-file-input
          v-model="upload"
          accept=".rfw"
          label="Firmware file (.rfw)"
          density="compact"
          variant="underlined"
          hide-details
          prepend-icon="mdi-chip"
        />
        <v-btn color="buttonActive" variant="tonal" :disabled="busy || !upload" @click="sendUpload">Upload</v-btn>
      </div>
    </v-card-text>

    <v-dialog :model-value="!!confirm" width="560" @update:model-value="(v) => !v && (confirm = null)">
      <v-card v-if="confirm" color="surface">
        <v-card-title>Flash {{ confirm.cam.camera || `port ${confirm.cam.port}` }}?</v-card-title>
        <v-card-text>
          <p class="pb-3">
            <b>{{ confirm.image.label ?? confirm.image.file }}</b> onto the Swyft camera on USB port
            {{ confirm.cam.port }} (serial {{ confirm.cam.serial ?? "–" }}). PhotonVision stops for under a minute, so
            every camera is off, then starts again. A backup is saved first. Keep the robot powered until it's done.
          </p>
          <v-alert type="warning" variant="tonal" density="compact" class="mb-3">
            Then <b>power cycle the robot</b> (main breaker): until then this camera sends no video. A Jetson reboot
            won't bring it back.
          </v-alert>
          <v-text-field
            v-model="confirm.serial"
            label="Serial number (blank: the firmware file's)"
            density="compact"
            variant="underlined"
            :rules="[(v: string) => validSerial(v) || 'Letters, digits and . _ - only']"
            data-testid="swyft-flash-serial"
          />
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'" @click="confirm = null"
            >Cancel</v-btn
          >
          <v-btn
            color="warning"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            :disabled="!validSerial(confirm.serial)"
            data-testid="swyft-flash-confirm"
            @click="flash(confirm.cam, confirm.image, confirm.serial)"
          >
            Flash and restart
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-card>
</template>
