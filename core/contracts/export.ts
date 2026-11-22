export type ExportFormat = "wav" | "mp3" | "midi" | "musicxml" | "pdf";
export interface ExportRequest { format: ExportFormat; destinationName: string; }
export interface ScoreExportCapabilities {
  notationEditing: "planned" | "implemented";
  tablature: "planned" | "implemented";
  musicXml: "planned" | "implemented";
  pdf: "planned" | "implemented";
}
