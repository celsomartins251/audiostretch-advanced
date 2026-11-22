export type StemKind = "vocals" | "bass" | "drums" | "guitar" | "keys" | "other";
export interface TimedNote { startFrame: number; endFrame: number; midiNote: number; confidence: number; }
export interface TimedChord { startFrame: number; endFrame: number; symbol: string; confidence: number; }
export interface AnalysisCapabilities {
  stemSeparation: "planned" | "implemented";
  drumComponentSeparation: "planned" | "implemented";
  noteRecognition: "planned" | "implemented";
  chordRecognition: "planned" | "implemented";
}
