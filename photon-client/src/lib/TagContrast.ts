// SpectrumJetson (photonvision-54): /api/tagContrast, how each camera's tags look (their white margin
// and black border): shared by the Input tab and the Match Ready page.
import axios from "axios";
import { ref } from "vue";

export interface TagContrast {
  verdict: "good" | "whites clipping" | "low contrast" | "blacks lifted" | "no tags";
  advice: string;
  white?: number;
  black?: number;
  contrast?: number;
  clipped?: number;
  tags?: number;
}

export const tagContrast = ref<Record<string, TagContrast>>({});

export const loadTagContrast = async () => {
  try {
    tagContrast.value = (await axios.get<Record<string, TagContrast>>("/tagContrast")).data;
  } catch {
    tagContrast.value = {};
  }
};

export const describeTagContrast = (t?: TagContrast): string => {
  if (!t || t.verdict === "no tags") return "no tag close enough to measure";
  return `white ${t.white}, black ${t.black} (contrast ${t.contrast}), ${t.clipped}% clipped: ${t.verdict}`;
};
