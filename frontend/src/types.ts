import type { components } from "./generated/api-schema";

export type LibraryScope = "all" | "personal" | "demo";

export type Attribution = components["schemas"]["Attribution"];
export type Photo = components["schemas"]["PhotoResponse"];
export type Session = components["schemas"]["SessionResponse"];
export type Settings = components["schemas"]["SettingsResponse"];
export type DeveloperStatus = components["schemas"]["DeveloperStatusResponse"];
export type LibraryStatus = components["schemas"]["LibraryStatusResponse"];
export type Person = components["schemas"]["PersonResponse"];
export type Place = components["schemas"]["PlaceResponse"];
export type PhotoEvent = components["schemas"]["EventResponse"];

export interface SearchResult {
  query: string;
  summary: string;
  evidence_photo_ids: string[];
  items: Photo[];
}

export interface UploadProgress {
  key: string;
  name: string;
  relativePath: string;
  progress: number;
  stage: "queued" | "uploading" | "preparing" | "ready" | "failed";
  message?: string;
}
