export interface MixerTrack {
  id: string; name: string; gain: number; pan: number;
  muted: boolean; solo: boolean;
}
export interface MixerCapabilities {
  equalizer: "planned" | "implemented";
  effects: "planned" | "implemented";
  automation: "planned" | "implemented";
}
