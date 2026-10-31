// SpectrumJetson (photonvision-50): /api/robotState, shared by Settings > Robot state and the
// dashboard's idle notice.
import { ref } from "vue";
import axios from "axios";

export interface RobotState {
  idleWhileDisabled: boolean;
  idleFps: number;
  robotConnected: boolean;
  // photonvision-62: false until a control word arrives (the robot then counts as enabled)
  controlWordSeen: boolean;
  enabled: boolean;
  autonomous: boolean;
  fmsAttached: boolean;
  idleNow: boolean;
  // photonvision-51
  eventProfileOnFms: boolean;
  eventPipeline: number;
  lastEventSwitch: string;
  // photonvision-55 (the far search's counters only once the detector library has them)
  farSearch: boolean;
  farSweepsPerSecond: number;
  farSearchStarved?: boolean;
  farSearchSweeps?: number;
  farSearchCrops?: number;
  farSearchTags?: number;
  farSearchStarvedSeconds?: number;
  farSearchSweepMs?: number;
  // photonvision-56
  quietAfterMatch: boolean;
  quietAfterDisabledSeconds: number;
  quietNow: boolean;
  quietReason: string;
  quietError: string;
  quietHelper: boolean;
  quietLastLeaveMs: number;
  // photonvision-57
  thermalLimitNow: boolean;
  thermalLimitFps: number;
  // photonvision-71: the cooling ("" without the spectrum-fan helper)
  fanAvailable: boolean;
  fanMode: CoolingMode | "";
  fanError: string;
}

export type CoolingMode = "quiet" | "full" | "off";

export interface RobotStateChange {
  idleWhileDisabled?: boolean;
  idleFps?: number;
  eventProfileOnFms?: boolean;
  eventPipeline?: number;
  switchNow?: boolean;
  farSearch?: boolean;
  farSweepsPerSecond?: number;
  quietAfterMatch?: boolean;
  quietAfterDisabledSeconds?: number;
  fanMode?: CoolingMode;
}

export const robotState = ref<RobotState | null>(null);

export const loadRobotState = async () => {
  try {
    robotState.value = (await axios.get<RobotState>("/robotState")).data;
  } catch {
    robotState.value = null;
  }
};

export const setRobotState = async (change: RobotStateChange) => {
  robotState.value = (await axios.post<RobotState>("/robotState", change)).data;
};
