<script setup lang="ts">
// SpectrumJetson (photonvision-53, 68): named snapshots of every setting (cameras, pipelines,
// calibrations, Robot state, Rewind, far search, the robot's field map), kept on the Jetson.
// Restoring saves the current settings first ("Before restoring ..."), then restarts PhotonVision.
import { computed, onMounted, ref, watch } from "vue";
import axios from "axios";
import { useStateStore } from "@/stores/StateStore";
import { useTheme } from "vuetify";

interface Snapshot {
  id: string;
  name: string;
  reason: string;
  createdAt: number;
  version: string;
  bytes: number;
  cameras: { nickname: string; pipelines: string[] }[];
}

const theme = useTheme();
const snapshots = ref<Snapshot[]>([]);
const newName = ref("");
const busy = ref(false);
const confirmRestore = ref<Snapshot | null>(null);
const confirmDelete = ref<Snapshot | null>(null);

const load = async () => {
  try {
    snapshots.value = (await axios.get<Snapshot[]>("/snapshots")).data;
  } catch {
    snapshots.value = [];
  }
};
onMounted(load);
// After a restore PhotonVision restarts; reload the list (with its new "Before restoring" entry)
// when this page reconnects.
watch(
  () => useStateStore().backendConnected,
  (connected) => connected && load()
);

const say = (message: string, color = "success") =>
  useStateStore().showSnackbarMessage({ message, color, timeout: 6000 });
const why = (e: unknown, fallback: string) =>
  axios.isAxiosError(e) && typeof e.response?.data === "string" && e.response.data ? e.response.data : fallback;

const save = async () => {
  busy.value = true;
  try {
    const s = (await axios.post<Snapshot>("/snapshots", { name: newName.value })).data;
    newName.value = "";
    say(`Saved '${s.name}'`);
    await load();
  } catch (e) {
    say(why(e, "Couldn't save the snapshot"), "error");
  } finally {
    busy.value = false;
  }
};
const restore = async (s: Snapshot) => {
  confirmRestore.value = null;
  busy.value = true;
  try {
    const before = (await axios.post<Snapshot>("/snapshots/restore", { id: s.id })).data;
    say(
      `Restoring '${s.name}': PhotonVision is restarting. The settings before it are saved as '${before.name}'.`,
      "info"
    );
  } catch (e) {
    say(why(e, "Couldn't restore"), "error");
  } finally {
    busy.value = false;
  }
};
const remove = async (s: Snapshot) => {
  confirmDelete.value = null;
  try {
    snapshots.value = (await axios.post<Snapshot[]>("/snapshots/delete", { id: s.id })).data;
    say(`Deleted '${s.name}'`);
  } catch (e) {
    say(why(e, "Couldn't delete"), "error");
  }
};

const when = (ms: number) => new Date(ms).toLocaleString();
const reasonText: Record<string, string> = {
  manual: "saved by hand",
  "before restore": "automatic, before a restore",
  "field connected": "automatic, field connected"
};
const summary = (s: Snapshot) =>
  s.cameras.map((c) => `${c.nickname}: ${c.pipelines.map((p, i) => `${i}: ${p}`).join(", ")}`).join("; ");
const nameOk = computed(() => /^[\w .()-]{0,60}$/.test(newName.value));
</script>

<template>
  <v-card class="mb-3 rounded-12" color="surface">
    <v-card-title class="pb-10px">Settings snapshots</v-card-title>
    <v-card-text class="pt-0">
      <p class="pb-2 text-body-2">
        Every camera, pipeline and calibration, plus Robot state, Rewind, far search and the robot's field map, saved on
        the Jetson under a name. Save one before an event and after tuning; restoring one brings every camera back to
        it. PhotonVision also saves one the first time the field connects each day.
      </p>
      <div class="d-flex align-center ga-2 pb-3">
        <v-text-field
          v-model="newName"
          data-testid="snapshot-name"
          label="Name (e.g. Before Houston, tuned exposure)"
          density="compact"
          variant="underlined"
          hide-details="auto"
          :error-messages="nameOk ? [] : ['Letters, numbers, spaces and . ( ) - _ only']"
          @keydown.enter="nameOk && !busy && save()"
        />
        <v-btn
          color="buttonActive"
          variant="tonal"
          :disabled="busy || !nameOk"
          data-testid="snapshot-save"
          @click="save"
        >
          Save snapshot
        </v-btn>
      </div>
      <v-table v-if="snapshots.length" density="compact" data-testid="snapshot-table">
        <thead>
          <tr>
            <th>Name</th>
            <th>Saved</th>
            <th>Cameras and pipelines</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="s in snapshots" :key="s.id" :data-testid="`snapshot-${s.id}`">
            <td>
              <b>{{ s.name }}</b>
              <div class="text-caption">{{ reasonText[s.reason] ?? s.reason }}</div>
            </td>
            <td class="text-no-wrap">{{ when(s.createdAt) }}</td>
            <td class="text-caption">{{ summary(s) }}</td>
            <td class="text-no-wrap">
              <v-btn size="small" variant="text" :href="`/api/snapshots/download?id=${s.id}`" icon="mdi-download" />
              <v-btn
                size="small"
                variant="text"
                color="warning"
                :disabled="busy"
                icon="mdi-backup-restore"
                data-testid="snapshot-restore"
                @click="confirmRestore = s"
              />
              <v-btn
                size="small"
                variant="text"
                color="error"
                icon="mdi-delete"
                data-testid="snapshot-delete"
                @click="confirmDelete = s"
              />
            </td>
          </tr>
        </tbody>
      </v-table>
      <p v-else class="text-caption">No snapshots yet.</p>
    </v-card-text>

    <v-dialog :model-value="!!confirmRestore" width="520" @update:model-value="(v) => !v && (confirmRestore = null)">
      <v-card v-if="confirmRestore" color="surface">
        <v-card-title>Restore '{{ confirmRestore.name }}'?</v-card-title>
        <v-card-text>
          Every camera's pipelines, settings and calibrations go back to {{ when(confirmRestore.createdAt) }}, and so do
          the network settings (team number, addresses), the AprilTag field layout, Robot state, Rewind's and the far
          search's settings. PhotonVision restarts (cameras are back in about 20 s). The current settings are saved
          first as a snapshot, so this can be undone.
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'" @click="confirmRestore = null">
            Cancel
          </v-btn>
          <v-btn
            color="warning"
            :variant="theme.global.current.value.dark ? 'outlined' : 'elevated'"
            data-testid="snapshot-restore-confirm"
            @click="restore(confirmRestore)"
          >
            Restore and restart
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
    <v-dialog :model-value="!!confirmDelete" width="440" @update:model-value="(v) => !v && (confirmDelete = null)">
      <v-card v-if="confirmDelete" color="surface">
        <v-card-title>Delete '{{ confirmDelete.name }}'?</v-card-title>
        <v-card-actions>
          <v-spacer />
          <v-btn @click="confirmDelete = null">Cancel</v-btn>
          <v-btn color="error" data-testid="snapshot-delete-confirm" @click="remove(confirmDelete)">Delete</v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
  </v-card>
</template>
