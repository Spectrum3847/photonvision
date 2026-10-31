<script setup lang="ts">
// SpectrumJetson (photonvision-26): what to set, in what order, and why, right above the camera
// settings. The numbers are this team's bench results with Thrifty Bot Thriftiest Cams (OV9281,
// 1280x800, f ~737 px); see the SpectrumJetson README.
import { computed } from "vue";
import { useCameraSettingsStore } from "@/stores/settings/CameraSettingsStore";
import { PipelineType } from "@/types/PipelineTypes";

const pipelineType = computed(() => useCameraSettingsStore().currentPipelineSettings.pipelineType);
const isAprilTag = computed(
  () => pipelineType.value === PipelineType.AprilTag || pipelineType.value === PipelineType.AprilTagCuda
);
const isObjectDetection = computed(() => pipelineType.value === PipelineType.ObjectDetection);
</script>

<template>
  <v-expansion-panels v-if="isAprilTag || isObjectDetection" class="pb-3" variant="accordion">
    <v-expansion-panel>
      <v-expansion-panel-title>
        <v-icon start size="small">mdi-map-marker-path</v-icon>
        Tuning guide: what to set, in order
      </v-expansion-panel-title>
      <v-expansion-panel-text>
        <ol v-if="isAprilTag" class="pl-4 guide">
          <li>
            <b>Resolution: 1280x800 at 120 FPS, MJPEG</b>, the resolution the camera is calibrated at. YUYV only manages
            about 5 fps at this size.
          </li>
          <li>
            <b>Auto Exposure: off.</b> Auto exposure picks long exposures, which look great with the robot standing
            still and blur as soon as it moves or turns. So does an image tuned by eye while standing still.
          </li>
          <li>
            <b>Exposure: as short as tags still decode.</b> Start at <b>5 ms (50)</b>.
            <ul>
              <li>
                Blur grows with exposure. Spinning at 3 rad/s smears a tag about 11 px in 5 ms and 44 px in 20 ms.
                Driving at 4 m/s moves the camera 2 cm in 5 ms.
              </li>
              <li>Go shorter (3 ms) if tags blur while driving; longer only if far tags drop out.</li>
              <li>
                Under event lights, run the flicker check. If frames pulse in brightness, use 8.3 ms (83), which matches
                the lights' 120 Hz flicker.
              </li>
            </ul>
          </li>
          <li>
            <b>Brightness: 100</b>, to make up for the short exposure. These cameras report no gain control, so
            brightness is what's left. If tag edges wash out, the decision margin drops: back off.
          </li>
          <li>
            <b>Contrast, gamma, sharpness:</b> leave them at the camera's defaults unless you're testing them. Sharpness
            is the one worth trying lower: the camera's sharpening can put halos on tag edges.
          </li>
          <li>
            <b>Decision margin cutoff (AprilTag tab): 15</b> at 5 ms. Tags scoring below it are dropped. Set it a little
            below what real tags score at your longest distance, and raise it if false tags appear.
          </li>
          <li>
            <b>Leave the rest:</b> Stream Resolution smallest (it only affects this view, and bigger costs CPU), white
            balance doesn't matter on a mono camera.
          </li>
          <li>
            <b>3D and multi-tag on (Output tab)</b>, once this resolution is calibrated. The robot's pose code needs
            both.
          </li>
          <li>
            <b>Check it on the field:</b> drive and spin past tags and watch that they don't drop out. Retune at every
            venue; lighting differs. Keep an Event and a Practice pipeline at the same numbers on every camera, and use
            the pipeline menu's <i>Copy settings from…</i> to set the other cameras.
          </li>
        </ol>
        <ol v-else class="pl-4 guide">
          <li><b>Resolution:</b> the one the model and calibration expect. The model sees a 640x640 letterbox.</li>
          <li>
            <b>Auto Exposure: off. Exposure: short</b>, about 5–10 ms. Moving game pieces blur like tags do, and blurred
            pieces score lower.
          </li>
          <li>
            <b>Colour cameras:</b> Auto White Balance off with a fixed temperature, so a piece's colour doesn't drift
            between frames.
          </li>
          <!-- 2026's "Frame rate: capped at 30 fps" line comes back with that cap (feature 14, photonvision-14). -->
          <li>
            <b>Confidence (Object Detection tab):</b> start at the model's default, then lower it until real pieces are
            found at your longest distance without false ones.
          </li>
        </ol>
        <p class="pt-2 text-caption">
          Calibrating? The calibration uses its own settings. A long exposure (about 15 ms) is fine there because the
          board is held still. Don't copy it into these settings.
        </p>
      </v-expansion-panel-text>
    </v-expansion-panel>
  </v-expansion-panels>
</template>

<style scoped lang="scss">
.guide li {
  padding-bottom: 6px;
}
.guide ul {
  padding-left: 18px;
  padding-top: 4px;
}
</style>
