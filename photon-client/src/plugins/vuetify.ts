import "vuetify/styles";
void import("@mdi/font/css/materialdesignicons.css");
import type { ThemeDefinition } from "vuetify";
import { createVuetify } from "vuetify";

// Spectrum's purple, taken from the dino mascot (assets/images/dino.webp)
const CommonColors = {
  pastelPurple: "#D8C6FE",
  bowPurple: "#996AFF",
  purple: "#6B52B5",
  deepPurple: "#4E3A8C",
  darkGray: "#151219",
  gray: "#221C2E",
  paleLavender: "#F4F0FF",
  white: "#FFFFFF"
};

export const DarkTheme: ThemeDefinition = {
  dark: true,
  colors: {
    background: CommonColors.darkGray,
    sidebar: CommonColors.darkGray,

    surface: CommonColors.gray,
    primary: CommonColors.pastelPurple,
    secondary: CommonColors.bowPurple,
    accent: CommonColors.purple,

    toggle: CommonColors.purple,
    logsBackground: CommonColors.darkGray,

    buttonActive: CommonColors.pastelPurple,
    buttonPassive: CommonColors.bowPurple,

    "surface-variant": "#5a4a7a",
    "on-surface-variant": "#f0f0f0",

    error: "#ff2e2e",
    info: "#2196F3",
    success: "#4CAF50",
    warning: "#FFC107"
  }
};

export const LightTheme: ThemeDefinition = {
  dark: false,
  colors: {
    background: CommonColors.paleLavender,
    sidebar: CommonColors.deepPurple,

    surface: CommonColors.white,
    primary: CommonColors.purple,
    secondary: CommonColors.bowPurple,
    accent: CommonColors.pastelPurple,

    toggle: CommonColors.bowPurple,
    logsBackground: CommonColors.white,

    buttonActive: CommonColors.purple,
    buttonPassive: CommonColors.pastelPurple,

    "surface-variant": "#5a4a7a",
    "on-surface-variant": "#f0f0f0",

    error: "#b80000",
    info: "#2196F3",
    success: "#4CAF50",
    warning: "#FFC107"
  }
};

export default createVuetify({
  theme: { defaultTheme: "LightTheme", themes: { LightTheme: LightTheme, DarkTheme: DarkTheme } },
  display: { thresholds: { md: 1460, lg: 2000 } }
});
