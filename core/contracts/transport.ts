/** Canonical DSP time unit: audio frames, not milliseconds. */
export type FramePosition = number;
export interface TransportState {
  playing: boolean;
  positionFrames: FramePosition;
  durationFrames: FramePosition;
  sampleRate: number;
}
export interface TransportPort {
  play(): Promise<void>;
  pause(): Promise<void>;
  stop(): Promise<void>;
  seekToFrame(frame: FramePosition): Promise<void>;
}
