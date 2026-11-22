export interface TimeStretchRequest { sourceFrames: number; ratio: number; preservePitch: boolean; }
export interface PitchShiftRequest { semitones: number; cents?: number; preserveDuration: boolean; }
export interface DspCapabilities {
  timeStretch: "planned" | "implemented";
  pitchShift: "planned" | "implemented";
  liveScrub: "planned" | "implemented";
  loopAB: "planned" | "implemented";
}
