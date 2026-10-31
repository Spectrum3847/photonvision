<script setup lang="ts">
// SpectrumJetson (photonvision-40): a reusable box-mask editor laid over any image or stream
// (the slot). Boxes are fractions (0-1) of the slot's area, so it works at any display size and
// any camera resolution. Shows the mask whenever it's on; with `editing`, drag on empty space to
// draw a box, drag a box to move it, drag any of its 4 corners to resize (the opposite corner
// stays put), click one to select it, and Delete/Backspace removes it. Emits the whole mask on each finished change (not while dragging).
import { computed, onBeforeUnmount, onMounted, ref, useTemplateRef } from "vue";
import { type DetectionMask, DetectionMaskMode, type MaskBox } from "@/types/PipelineTypes";

const props = defineProps<{
  modelValue?: DetectionMask;
  editing?: boolean;
}>();
const emit = defineEmits<{
  (e: "update:modelValue", value: DetectionMask): void;
}>();

const MIN_SIZE = 0.01; // boxes smaller than 1% of the image in either direction are dropped

const root = useTemplateRef<HTMLElement>("root");
const selected = ref<number>(-1);
// While dragging, a working copy of the boxes; otherwise the model's.
const working = ref<MaskBox[] | null>(null);
type Drag =
  | { kind: "draw"; x0: number; y0: number }
  | { kind: "move"; index: number; dx: number; dy: number }
  | { kind: "resize"; index: number; ax: number; ay: number }; // (ax, ay): the fixed corner
let drag: Drag | null = null;

const mode = computed<DetectionMaskMode>(() => props.modelValue?.mode ?? DetectionMaskMode.Off);
const boxes = computed<MaskBox[]>(() => working.value ?? props.modelValue?.boxes ?? []);
const visible = computed(() => props.editing || (mode.value !== DetectionMaskMode.Off && boxes.value.length > 0));
// While editing with the mask off, show boxes as ignore boxes so they can still be drawn.
const onlyInside = computed(() => mode.value === DetectionMaskMode.OnlyInside);
const maskId = `pv-mask-${Math.random().toString(36).slice(2)}`;

const clamp01 = (v: number) => Math.min(1, Math.max(0, v));
const point = (ev: PointerEvent) => {
  const r = root.value!.getBoundingClientRect();
  return { x: clamp01((ev.clientX - r.left) / r.width), y: clamp01((ev.clientY - r.top) / r.height) };
};
const commit = (next: MaskBox[]) => {
  emit("update:modelValue", {
    mode: mode.value,
    boxes: next.map((b) => ({ x: +b.x.toFixed(4), y: +b.y.toFixed(4), w: +b.w.toFixed(4), h: +b.h.toFixed(4) }))
  });
};

const onBackgroundDown = (ev: PointerEvent) => {
  if (!props.editing) return;
  const p = point(ev);
  working.value = [...boxes.value, { x: p.x, y: p.y, w: 0, h: 0 }];
  selected.value = working.value.length - 1;
  drag = { kind: "draw", x0: p.x, y0: p.y };
  (ev.target as Element).setPointerCapture(ev.pointerId);
};
const onBoxDown = (ev: PointerEvent, index: number) => {
  if (!props.editing) return;
  ev.stopPropagation();
  const p = point(ev);
  const b = boxes.value[index];
  working.value = boxes.value.map((x) => ({ ...x }));
  selected.value = index;
  drag = { kind: "move", index, dx: p.x - b.x, dy: p.y - b.y };
  (ev.target as Element).setPointerCapture(ev.pointerId);
};
// Corners: 0 top-left, 1 top-right, 2 bottom-right, 3 bottom-left.
const cornerX = (b: MaskBox, c: number) => (c === 1 || c === 2 ? b.x + b.w : b.x);
const cornerY = (b: MaskBox, c: number) => (c >= 2 ? b.y + b.h : b.y);
const onHandleDown = (ev: PointerEvent, index: number, corner: number) => {
  if (!props.editing) return;
  ev.stopPropagation();
  working.value = boxes.value.map((x) => ({ ...x }));
  selected.value = index;
  const b = boxes.value[index];
  const opposite = (corner + 2) % 4;
  drag = { kind: "resize", index, ax: cornerX(b, opposite), ay: cornerY(b, opposite) };
  (ev.target as Element).setPointerCapture(ev.pointerId);
};
const onMove = (ev: PointerEvent) => {
  if (!drag || !working.value) return;
  const p = point(ev);
  const w = working.value;
  if (drag.kind === "draw") {
    const b = w[w.length - 1];
    b.x = Math.min(drag.x0, p.x);
    b.y = Math.min(drag.y0, p.y);
    b.w = Math.abs(p.x - drag.x0);
    b.h = Math.abs(p.y - drag.y0);
  } else if (drag.kind === "move") {
    const b = w[drag.index];
    b.x = clamp01(Math.min(p.x - drag.dx, 1 - b.w));
    b.y = clamp01(Math.min(p.y - drag.dy, 1 - b.h));
  } else {
    // The dragged corner follows the pointer; it may cross the fixed one (the box flips).
    const b = w[drag.index];
    b.x = Math.min(drag.ax, p.x);
    b.y = Math.min(drag.ay, p.y);
    b.w = Math.max(MIN_SIZE, Math.abs(p.x - drag.ax));
    b.h = Math.max(MIN_SIZE, Math.abs(p.y - drag.ay));
  }
};
const onUp = () => {
  if (!drag || !working.value) return;
  const next = working.value.filter((b) => b.w >= MIN_SIZE && b.h >= MIN_SIZE);
  if (selected.value >= next.length) selected.value = -1;
  drag = null;
  working.value = null;
  commit(next);
};
const onKey = (ev: KeyboardEvent) => {
  if (!props.editing || selected.value < 0) return;
  if (ev.key !== "Delete" && ev.key !== "Backspace") return;
  const target = ev.target as HTMLElement | null;
  if (target && ["INPUT", "TEXTAREA"].includes(target.tagName)) return;
  ev.preventDefault();
  commit(boxes.value.filter((_, i) => i !== selected.value));
  selected.value = -1;
};
onMounted(() => window.addEventListener("keydown", onKey));
onBeforeUnmount(() => window.removeEventListener("keydown", onKey));
</script>

<template>
  <div ref="root" class="pv-mask-overlay">
    <slot />
    <svg
      v-if="visible"
      class="pv-mask-svg"
      :class="{ editing }"
      viewBox="0 0 1 1"
      preserveAspectRatio="none"
      @pointerdown="onBackgroundDown"
      @pointermove="onMove"
      @pointerup="onUp"
      @pointercancel="onUp"
    >
      <template v-if="onlyInside">
        <!-- Darken everything outside the boxes -->
        <mask :id="maskId">
          <rect x="0" y="0" width="1" height="1" fill="white" />
          <rect v-for="(b, i) in boxes" :key="'h' + i" :x="b.x" :y="b.y" :width="b.w" :height="b.h" fill="black" />
        </mask>
        <rect x="0" y="0" width="1" height="1" class="outside" :mask="`url(#${maskId})`" />
      </template>
      <g v-for="(b, i) in boxes" :key="i">
        <rect
          :x="b.x"
          :y="b.y"
          :width="b.w"
          :height="b.h"
          :class="['box', onlyInside ? 'keep' : 'ignore', { selected: editing && i === selected }]"
          vector-effect="non-scaling-stroke"
          @pointerdown="(ev) => onBoxDown(ev, i)"
        />
        <template v-if="editing && i === selected">
          <rect
            v-for="c in 4"
            :key="c"
            :x="cornerX(b, c - 1) - 0.012"
            :y="cornerY(b, c - 1) - 0.016"
            width="0.024"
            height="0.032"
            :class="['handle', c % 2 === 1 ? 'nwse' : 'nesw']"
            vector-effect="non-scaling-stroke"
            @pointerdown="(ev) => onHandleDown(ev, i, c - 1)"
          />
        </template>
      </g>
    </svg>
  </div>
</template>

<style scoped>
.pv-mask-overlay {
  position: relative;
  width: 100%;
}
.pv-mask-svg {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
  touch-action: none;
}
.pv-mask-svg.editing {
  pointer-events: all;
  cursor: crosshair;
}
.outside {
  fill: rgba(0, 0, 0, 0.55);
  pointer-events: none;
}
.box {
  stroke-width: 2;
}
.box.ignore {
  fill: rgba(229, 57, 53, 0.35);
  stroke: rgb(229, 57, 53);
}
.box.keep {
  fill: rgba(67, 160, 71, 0.08);
  stroke: rgb(67, 160, 71);
}
.editing .box {
  cursor: move;
}
.box.selected {
  stroke: #fff;
  stroke-dasharray: 6 4;
}
.handle {
  fill: #fff;
  stroke: #333;
  stroke-width: 1;
}
.handle.nwse {
  cursor: nwse-resize;
}
.handle.nesw {
  cursor: nesw-resize;
}
</style>
