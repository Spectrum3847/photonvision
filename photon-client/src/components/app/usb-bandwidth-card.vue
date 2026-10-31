<script setup lang="ts">
// SpectrumJetson (photonvision-32): the USB bandwidth every camera reserves and really uses, against
// the one budget all the Jetson's USB 2.0 ports share, with a setting per camera for what it may
// reserve.
import axios from "axios";
import { computed, onBeforeUnmount, ref } from "vue";
import { useStateStore } from "@/stores/StateStore";
import {
  frameFits,
  mbPerSec,
  refreshUsbBandwidth,
  usbSurvey,
  watchUsbBandwidth,
  type UsbCamera
} from "@/lib/UsbBandwidth";

const stop = watchUsbBandwidth();
onBeforeUnmount(stop);

const COLOURS = ["#4caf50", "#2196f3", "#ff9800", "#ab47bc", "#26c6da", "#ec407a", "#9ccc65", "#ffca28"];
const s = computed(() => usbSurvey.value);
const cams = computed(() => s.value?.cameras ?? []);
const name = (c: UsbCamera) => c.camera ?? c.product;
const colour = (i: number) => COLOURS[i % COLOURS.length];
const pct = (bytes: number) => (s.value ? (100 * bytes) / s.value.budgetBytes : 0);
const used = (c: UsbCamera) => (c.usedBytesPerSec ?? 0) / 8000; // bytes per microframe
const free = computed(() => (s.value ? s.value.budgetBytes - s.value.wantBytes : 0));
const canChange = computed(() => s.value?.driver === "capped");

const fmtMB = (bps: number | null) => (bps === null ? "…" : `${(bps / 1e6).toFixed(1)} MB/s`);
const fitClass = (x: number | null) => (x === null ? "" : x < 1.15 ? "text-error" : x < 1.3 ? "text-warning" : "");
// A camera that needs more than its allocation can't take it: it squeezes its JPEGs to fit.
const share = (c: UsbCamera) => used(c) / Math.max(1, c.wantBytes);
const squeezed = (c: UsbCamera) => c.usedBytesPerSec !== null && share(c) >= 0.9;

// The allocation choices: the camera's alternate settings, each with what it means here.
const choices = (c: UsbCamera) => {
  if (!s.value) return [];
  const others = s.value.wantBytes - c.wantBytes;
  const out: { title: string; value: number | null; props: { disabled: boolean; subtitle: string } }[] = c.altBytes.map(
    (b) => {
      const fits = frameFits(c, b);
      const over = others + b > s.value!.budgetBytes;
      const notes = [
        fits !== null ? `largest frame fits ${fits.toFixed(1)}x` : "",
        fits !== null && c.recentMaxFrameBytes
          ? `${((1000 * c.recentMaxFrameBytes) / (b * 8000)).toFixed(1)} ms on USB`
          : "",
        over ? "over the budget" : ""
      ].filter(Boolean);
      return {
        title: `${b} B · ${mbPerSec(b).toFixed(1)} MB/s`,
        value: b,
        props: { disabled: over, subtitle: notes.join(", ") }
      };
    }
  );
  if (c.capFrom === "port") {
    out.push({
      title: "Back to this model's default",
      value: null,
      props: { disabled: false, subtitle: "the cap set for every camera of this model" }
    });
  }
  return out;
};

const busy = ref<string | null>(null);
const setAllocation = async (c: UsbCamera, bytes: number | null) => {
  busy.value = c.port;
  try {
    const r = await axios.post("/usb/bandwidth", { port: c.port, bytes });
    useStateStore().showSnackbarMessage({ message: r.data, color: "success" });
  } catch (e) {
    const why = axios.isAxiosError(e) && typeof e.response?.data === "string" ? e.response.data : "";
    useStateStore().showSnackbarMessage({ message: why || "Request failed", color: "error" });
  } finally {
    busy.value = null;
    void refreshUsbBandwidth();
  }
};
</script>

<template>
  <v-card color="surface" class="rounded-12">
    <v-card-title>USB bandwidth</v-card-title>
    <v-card-subtitle class="text-wrap">
      Every USB 2.0 port (the four USB-A ports, USB-C, and hubs on them) shares one budget. A camera reserves its
      allocation whatever it sends, and one that doesn't fit can't start.
    </v-card-subtitle>
    <v-card-text v-if="!s" class="pt-3">Reading the USB cameras…</v-card-text>
    <v-card-text v-else class="pt-3">
      <div class="budget">
        <div
          v-for="(c, i) in cams"
          :key="c.port"
          class="seg"
          :style="{ width: `${pct(c.wantBytes)}%`, background: colour(i) + '55', borderColor: colour(i) }"
          :title="`${name(c)}: ${c.wantBytes} B allocated, ${used(c).toFixed(0)} B used`"
        >
          <div
            class="seg-used"
            :style="{ width: `${c.wantBytes ? (100 * used(c)) / c.wantBytes : 0}%`, background: colour(i) }"
          />
        </div>
      </div>
      <div class="text-body-2 mt-2">
        Allocated <b>{{ s.wantBytes }}</b> of ~{{ s.budgetBytes }} bytes per 125 µs ({{
          mbPerSec(s.wantBytes).toFixed(1)
        }}
        of {{ mbPerSec(s.budgetBytes).toFixed(1) }} MB/s), using <b>{{ fmtMB(s.usedBytesPerSec) }}</b
        >.
        <span v-if="free > 0">{{ free }} bytes free.</span>
        <span v-else-if="free === 0">Full: to give a camera more, lower another first.</span>
        <span v-else class="text-error">Over the budget by {{ -free }} bytes: a camera won't start.</span>
      </div>
      <v-alert v-if="s.driver !== 'capped'" density="compact" variant="tonal" color="warning" class="mt-2">
        {{
          s.driver === "stock"
            ? "The stock camera driver is loaded: allocations can't be capped. Install ours:"
            : "This build of our camera driver can't change allocations while running. Reinstall it:"
        }}
        <code>scripts/jetson/11-uvcvideo-payload-cap.sh --install</code>
      </v-alert>

      <v-table density="compact" class="mt-2">
        <thead>
          <tr>
            <th></th>
            <th>Camera</th>
            <th>FPS</th>
            <th>Using</th>
            <th>Largest frame</th>
            <th style="min-width: 250px">Allocation</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(c, i) in cams" :key="c.port">
            <td><span class="dot" :style="{ background: colour(i) }" /></td>
            <td>
              {{ name(c) }}
              <div class="text-caption">port {{ c.port }} · {{ c.id }}</div>
              <div v-if="c.problem" class="text-caption text-error">{{ c.problem }}</div>
              <div v-else-if="!c.streaming" class="text-caption text-warning">not streaming</div>
            </td>
            <td>{{ c.fps === null ? "" : c.fps.toFixed(0) }}</td>
            <td>
              <template v-if="c.usedBytesPerSec !== null">
                {{ fmtMB(c.usedBytesPerSec) }}
                <div class="text-caption" :class="squeezed(c) ? 'text-warning' : ''">
                  {{ Math.round(100 * share(c)) }}% of its allocation{{ squeezed(c) ? ": squeezed" : "" }}
                </div>
              </template>
            </td>
            <td>
              <template v-if="c.recentMaxFrameBytes">
                {{ (c.recentMaxFrameBytes / 1000).toFixed(1) }} KB
                <div class="text-caption" :class="fitClass(frameFits(c, c.wantBytes))">
                  <template v-if="frameFits(c, c.wantBytes) !== null"
                    >fits {{ frameFits(c, c.wantBytes)!.toFixed(1) }}x,
                  </template>
                  {{ ((1000 * c.recentMaxFrameBytes) / (c.wantBytes * 8000)).toFixed(1) }} ms on USB
                </div>
              </template>
            </td>
            <td>
              <v-select
                :model-value="c.wantBytes"
                :items="choices(c)"
                density="compact"
                variant="outlined"
                hide-details
                :disabled="!canChange || busy !== null"
                :loading="busy === c.port"
                @update:model-value="(v: number | null) => setAllocation(c, v)"
              >
                <template #selection>
                  {{ c.wantBytes }} B · {{ mbPerSec(c.wantBytes).toFixed(1) }} MB/s
                  <span class="text-caption ml-1"
                    >({{
                      c.capFrom === "port" ? "set here" : c.capFrom === "model" ? "model default" : "uncapped"
                    }})</span
                  >
                </template>
              </v-select>
            </td>
          </tr>
        </tbody>
      </v-table>
      <div class="text-caption mt-2">
        A camera can't send more than its allocation, and never takes another camera's. When its frames don't fit, the
        cameras we tested keep their frame rate and compress harder: smaller, lower-quality JPEGs, each taking longer to
        cross USB. "Squeezed" (using 90% or more) means that's happening. "Fits" is how many times the largest recent
        frame fits in one frame time; the ms is how long a frame takes to cross USB. Changes are saved for the next
        boot, and the camera is reset to apply them (a second or two without frames).
      </div>
    </v-card-text>
  </v-card>
</template>

<style scoped>
.budget {
  display: flex;
  height: 22px;
  border-radius: 6px;
  overflow: hidden;
  background: rgba(128, 128, 128, 0.15);
}
.seg {
  height: 100%;
  border-right: 2px solid;
  position: relative;
}
.seg-used {
  height: 100%;
}
.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
</style>
