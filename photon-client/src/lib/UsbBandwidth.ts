// SpectrumJetson (photonvision-32): every USB camera's bandwidth, reserved and really used, from
// GET /api/usb/bandwidth (UsbBandwidth.java), polled while a page shows it.
import axios from "axios";
import { ref } from "vue";

export interface UsbCamera {
  port: string; // e.g. "1-2.4"
  id: string; // vid:pid
  product: string;
  camera: string | null; // PhotonVision nickname
  uniqueName: string | null;
  altBytes: number[]; // the camera's alternate settings, bytes per 125 us microframe
  reservedBytes: number; // what it reserves now (0: not streaming)
  streaming: boolean;
  capBytes: number | null; // its cap (0: uncapped)
  capFrom: "port" | "model" | null;
  wantBytes: number; // what it reserves when streaming, under its cap
  fps: number | null;
  usedBytesPerSec: number | null;
  recentMaxFrameBytes: number | null;
  problem: string;
}

export interface UsbSurvey {
  budgetBytes: number; // bytes per microframe every USB 2.0 camera shares
  microframesPerSecond: number;
  reservedBytes: number;
  wantBytes: number;
  usedBytesPerSec: number | null;
  driver: "capped" | "capped-readonly" | "stock";
  payloadCap: string;
  cameras: UsbCamera[];
}

export const usbSurvey = ref<UsbSurvey | null>(null);

// The server's JSON (avaje-jsonb) leaves out null fields: put them back as null, as the types say.
const withNulls = (s: UsbSurvey): UsbSurvey => {
  s.usedBytesPerSec ??= null;
  s.cameras = (s.cameras ?? []).map((c) => ({
    ...c,
    camera: c.camera ?? null,
    uniqueName: c.uniqueName ?? null,
    capBytes: c.capBytes ?? null,
    capFrom: c.capFrom ?? null,
    fps: c.fps ?? null,
    usedBytesPerSec: c.usedBytesPerSec ?? null,
    recentMaxFrameBytes: c.recentMaxFrameBytes ?? null,
    problem: c.problem ?? ""
  }));
  return s;
};

let users = 0;
let timer: ReturnType<typeof setTimeout> | undefined;
const poll = async () => {
  try {
    usbSurvey.value = withNulls((await axios.get("/usb/bandwidth")).data);
  } catch {
    usbSurvey.value = null;
  }
  clearTimeout(timer);
  if (users > 0) timer = setTimeout(() => void poll(), 1500);
};

/** Starts polling (shared by every caller); returns the function that stops it. */
export const watchUsbBandwidth = () => {
  if (users++ === 0) void poll();
  return () => {
    if (--users === 0) clearTimeout(timer);
  };
};

/** Polls now (after a change). */
export const refreshUsbBandwidth = () => poll();

/** Bytes per microframe as MB/s. */
export const mbPerSec = (bytes: number) => (bytes * 8000) / 1e6;

/** How many times over the recent largest frame fits in one frame time at this allocation. */
export const frameFits = (c: UsbCamera, bytes: number) =>
  c.fps && c.recentMaxFrameBytes ? (bytes * 8000) / c.fps / c.recentMaxFrameBytes : null;
