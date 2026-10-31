<script setup lang="ts">
// SpectrumJetson (photonvision-50): the robot's state as PhotonVision sees it, and idle mode
// (photonvision-49): fewer frames while the robot is disabled, switchable for full-speed tests.
// Also the far-tag search (55), quiet mode (56), the thermal limit (57), the event profile (51) and
// the cooling (71), as on the 2026 card.
import { computed, onBeforeUnmount, onMounted, ref } from "vue";
import axios from "axios";
import PvSwitch from "@/components/common/pv-switch.vue";
import PvSlider from "@/components/common/pv-slider.vue";
import PvSelect, { type SelectItem } from "@/components/common/pv-select.vue";
import { useStateStore } from "@/stores/StateStore";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import { useTheme } from "vuetify";
import { loadRobotState, robotState, setRobotState, type CoolingMode, type RobotStateChange } from "@/lib/RobotState";

// photonvision-51: each pipeline number, with what every camera has there, so a profile set up at
// mismatched numbers shows before a match rather than during one.
const eventPipelineItems = computed<SelectItem<number>[]>(() => {
  const cameras = Object.values(useCameraSettingsStore().cameras);
  const most = Math.max(1, ...cameras.map((c) => c.pipelineNicknames?.length ?? 0));
  return Array.from({ length: most }, (_, index) => {
    const byName = new Map<string, string[]>();
    const missing: string[] = [];
    for (const c of cameras) {
      const name = c.pipelineNicknames?.[index];
      if (name === undefined) missing.push(c.nickname);
      else byName.set(name, [...(byName.get(name) ?? []), c.nickname]);
    }
    const parts = [...byName.entries()].map(([name, on]) =>
      on.length === cameras.length ? `${name} (every camera)` : `${name} (${on.join(", ")})`
    );
    if (missing.length) parts.push(`none on ${missing.join(", ")}`);
    return { name: `${index}: ${parts.join("; ")}`, value: index };
  });
});

const theme = useTheme();
const busy = ref(false);
let timer: ReturnType<typeof setInterval> | undefined;

const set = async (change: RobotStateChange) => {
  busy.value = true;
  try {
    await setRobotState(change);
  } catch (e) {
    useStateStore().showSnackbarMessage({
      message:
        axios.isAxiosError(e) && typeof e.response?.data === "string"
          ? e.response.data
          : "Couldn't change the robot state settings",
      color: "error"
    });
    void loadRobotState();
  } finally {
    busy.value = false;
  }
};

// photonvision-71: the cooling. A team with the stock fan that leaves it on "No fan" runs with
// its fan stopped (and the cameras capped once the chip reaches 95 C).
const coolingItems: SelectItem<CoolingMode>[] = [
  { name: "Fan: NVIDIA's quiet profile (the stock heatsink and fan)", value: "quiet" },
  { name: "Fan at full speed (coolest, loudest)", value: "full" },
  { name: "No fan (a heatsink plate): cameras capped at 60 fps from 95 °C", value: "off" }
];

onMounted(() => {
  void loadRobotState();
  timer = setInterval(() => void loadRobotState(), 1000);
});
onBeforeUnmount(() => clearInterval(timer));
</script>

<template>
  <v-card v-if="robotState" class="mb-3 rounded-12" color="surface" data-testid="robot-state">
    <v-card-title class="pb-10px">Robot state</v-card-title>
    <v-card-text class="pt-0">
      <v-alert
        class="mb-3"
        density="compact"
        :color="robotState.idleNow ? 'warning' : 'buttonActive'"
        :icon="robotState.robotConnected ? 'mdi-robot' : 'mdi-robot-off'"
        :variant="theme.global.current.value.dark ? 'tonal' : 'elevated'"
      >
        <template v-if="!robotState.robotConnected">No robot connected: every camera runs at full speed.</template>
        <template v-else-if="!robotState.controlWordSeen">
          Robot connected, but its state (enabled or not) hasn't arrived: treated as enabled, cameras at full speed.
        </template>
        <template v-else>
          Robot
          <b>{{ robotState.enabled ? (robotState.autonomous ? "enabled (auto)" : "enabled") : "disabled" }}</b
          >{{ robotState.fmsAttached ? ", field (FMS) connected" : "" }}.
          {{ robotState.idleNow ? `Cameras idling at ${robotState.idleFps} fps.` : "Cameras at full speed." }}
        </template>
      </v-alert>
      <v-alert
        v-if="robotState.thermalLimitNow"
        type="warning"
        density="compact"
        class="mb-3"
        data-testid="thermal-limit"
      >
        Too hot: every camera is capped at {{ robotState.thermalLimitFps }} fps until the chip is under 88 °C.
      </v-alert>
      <pv-switch
        :model-value="robotState.idleWhileDisabled"
        :disabled="busy"
        label="Idle while the robot is disabled"
        tooltip="While a robot is connected and disabled, each camera processes only this many frames a second: less power and heat between matches, and still plenty to set the starting pose. Full speed again within 5 ms of enable. Turn off to test full speed with the robot disabled."
        :label-cols="4"
        @update:model-value="(v) => set({ idleWhileDisabled: !!v })"
      />
      <pv-slider
        :model-value="robotState.idleFps"
        :disabled="busy || !robotState.idleWhileDisabled"
        label="Idle frames a second"
        tooltip="Per camera, while the robot is disabled. 30 is plenty to set the pose before auto."
        :min="5"
        :max="60"
        :step="5"
        :slider-cols="8"
        @update:model-value="(v) => set({ idleFps: v })"
      />
      <v-divider class="my-3" />
      <pv-switch
        :model-value="robotState.farSearch"
        :disabled="busy"
        label="Far-tag search when the pose is weak"
        tooltip="Tags are normally searched for on a half-size image, which misses tags under ~20 px (far away). When no camera sees two good near tags for 0.25 s, one camera at a time also searches its full-size image (and follows far tags it finds with small full-size crops): about 1.7x the range. It stops as soon as any camera has a good view again, so it costs nothing while the pose is good. CUDA AprilTag pipelines only."
        :label-cols="4"
        @update:model-value="(v) => set({ farSearch: !!v })"
      />
      <pv-slider
        :model-value="robotState.farSweepsPerSecond"
        :disabled="busy || !robotState.farSearch"
        label="Full-size searches a second"
        tooltip="Across all cameras, while searching. Each takes ~5 ms of GPU; 30 a second is ~16%."
        :min="5"
        :max="60"
        :step="5"
        :slider-cols="8"
        @update:model-value="(v) => set({ farSweepsPerSecond: v })"
      />
      <p class="text-caption pb-1" data-testid="far-search-status">
        <template v-if="!robotState.farSearch">Off: tags are only searched for on the half-size image.</template>
        <template v-else-if="robotState.farSearchSweeps === undefined">
          Status unknown (no CUDA pipeline running yet, or an older detector library).
        </template>
        <template v-else>
          {{
            robotState.farSearchStarved
              ? "Searching now: no camera has a good view."
              : "Waiting: a camera has a good view."
          }}
          Since start: {{ robotState.farSearchStarvedSeconds?.toFixed(0) }} s searching,
          {{ robotState.farSearchSweeps }} full-size searches ({{ robotState.farSearchSweepMs?.toFixed(1) }} ms each),
          {{ robotState.farSearchTags }} far tags found.
        </template>
      </p>
      <v-divider class="my-3" />
      <template v-if="robotState.quietHelper || robotState.quietNow">
        <pv-switch
          :model-value="robotState.quietAfterMatch"
          :disabled="busy"
          label="Quiet after a match"
          tooltip="Power cuts only damage an SSD while it's being written. 20 s after a match ends (field connected, teleop, then disabled), the Jetson stops writing to the SSD: logs go to memory (and warnings to the robot's log). The cameras keep running. Any enable ends it within a fraction of a second."
          :label-cols="4"
          @update:model-value="(v) => set({ quietAfterMatch: !!v })"
        />
        <pv-slider
          :model-value="robotState.quietAfterDisabledSeconds"
          :disabled="busy"
          label="Quiet after disabled for (s)"
          tooltip="Also go quiet after the robot has been disabled this long, on or off the field (pit, practice). 0 = never."
          :min="0"
          :max="600"
          :step="10"
          :slider-cols="8"
          @update:model-value="(v) => set({ quietAfterDisabledSeconds: v })"
        />
        <p class="text-caption pb-1" data-testid="quiet-status">
          <template v-if="robotState.quietNow"
            >Quiet now ({{ robotState.quietReason }}): the SSD isn't being written.</template
          >
          <template v-else>Writing normally.</template>
          <template v-if="robotState.quietLastLeaveMs >= 0">
            Last switch back to writing took {{ robotState.quietLastLeaveMs }} ms.</template
          >
          <template v-if="!robotState.quietHelper">
            The quiet-mode helper isn't installed (10-data-partition.sh).</template
          >
          <template v-if="robotState.quietError"> Problem: {{ robotState.quietError }}</template>
          Robot code can decide instead with /photonvision/jetson/quiet.
        </p>
      </template>
      <p v-else class="text-caption pb-1" data-testid="quiet-status">
        Quiet mode (no SSD writes after a match) needs the spectrum-quiet helper (SpectrumJetson 10-data-partition.sh):
        not on this device.
      </p>
      <v-divider class="my-3" />
      <template v-if="robotState.clocksHelper">
        <pv-slider
          :model-value="robotState.throttleClocksAfterDisabledSeconds"
          :disabled="busy"
          label="Lower clocks after disabled for (s)"
          tooltip="Lower the CPU and GPU clocks (about 3 W less, a cooler chip) once the robot has been disabled this long, or no robot is connected (the bench). Back to full clocks the moment the robot is enabled. At least 10 s, so never in the gap between auto and teleop. 0 = never."
          :min="0"
          :max="600"
          :step="10"
          :slider-cols="8"
          @update:model-value="(v) => set({ throttleClocksAfterDisabledSeconds: v })"
        />
        <p class="text-caption pb-1" data-testid="clocks-status">
          <template v-if="robotState.clocksMode === 'full'"
            >Always full clocks: mode full (sudo spectrum-clocks mode auto to undo).</template
          >
          <template v-else-if="robotState.clocksThrottled">Lower clocks now ({{ robotState.clocksReason }}).</template>
          <template v-else>Full clocks.</template>
          <template v-if="(robotState.clocksLastFullMs ?? -1) >= 0">
            Last switch back to full took {{ robotState.clocksLastFullMs }} ms.</template
          >
          <template v-if="robotState.clocksError"> Problem: {{ robotState.clocksError }}</template>
        </p>
        <v-divider class="my-3" />
      </template>
      <pv-switch
        :model-value="robotState.eventProfileOnFms"
        :disabled="busy"
        label="Event pipeline when the field connects"
        tooltip="When the field (FMS) connects, every camera switches to the pipeline number below, once. Anything after that sticks: a switch on the dashboard, robot code choosing a pipeline, or turning this off. Cameras without that number, in driver mode or calibrating are left alone."
        :label-cols="4"
        @update:model-value="(v) => set({ eventProfileOnFms: !!v })"
      />
      <pv-select
        :model-value="robotState.eventPipeline"
        :disabled="busy || !robotState.eventProfileOnFms"
        label="Event pipeline"
        tooltip="The pipeline number every camera switches to. Each entry lists what the cameras have at that number."
        :items="eventPipelineItems"
        :select-cols="8"
        @update:model-value="(v) => set({ eventPipeline: Number(v) })"
      />
      <div class="d-flex align-center pt-2">
        <v-btn
          size="small"
          variant="tonal"
          color="buttonActive"
          :disabled="busy || !robotState.eventProfileOnFms"
          @click="set({ switchNow: true })"
        >
          Switch now
        </v-btn>
        <span class="text-caption pl-3" data-testid="last-event-switch">
          {{
            robotState.lastEventSwitch
              ? `Last: ${robotState.lastEventSwitch}`
              : "Not switched since PhotonVision started."
          }}
        </span>
      </div>
      <template v-if="robotState.fanAvailable && robotState.fanMode !== ''">
        <v-divider class="my-3" />
        <pv-select
          :model-value="robotState.fanMode"
          :disabled="busy"
          label="Cooling"
          tooltip="How this Jetson is cooled. With the stock heatsink and fan, use a Fan setting: on No fan the fan stays stopped. No fan is for a heatsink plate the fan can't blow through: the fan stays off, and if the chip reaches 95 °C every camera is capped at 60 fps until it's under 88 °C. Applies at once, and at every boot."
          :items="coolingItems"
          :select-cols="8"
          data-testid="cooling"
          @update:model-value="(v) => set({ fanMode: v })"
        />
        <p v-if="robotState.fanError" class="text-caption pb-1 text-error">
          Couldn't change the cooling: {{ robotState.fanError }}
        </p>
      </template>
    </v-card-text>
  </v-card>
</template>
