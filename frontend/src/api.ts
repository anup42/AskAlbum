import type {
  DeveloperStatus,
  LibraryScope,
  LibraryStatus,
  Person,
  Photo,
  PhotoEvent,
  Place,
  SearchResult,
  Session,
  Settings,
} from "./types";

export class ApiError extends Error {
  constructor(
    message: string,
    public readonly status: number,
  ) {
    super(message);
  }
}

let csrfToken = "";

function withSecurityHeaders(init?: RequestInit): HeadersInit {
  const method = (init?.method || "GET").toUpperCase();
  return {
    ...(init?.body instanceof Blob ? {} : { "Content-Type": "application/json" }),
    ...(method === "GET" || method === "HEAD" || !csrfToken ? {} : { "X-CSRF-Token": csrfToken }),
    ...init?.headers,
  };
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, {
    credentials: "include",
    ...init,
    headers: withSecurityHeaders(init),
  });
  if (!response.ok) {
    let message = "Something went wrong";
    try {
      const payload = (await response.json()) as { detail?: string };
      if (payload.detail) message = payload.detail;
    } catch {
      // Plain-language fallback intentionally hides server internals.
    }
    throw new ApiError(message, response.status);
  }
  if (response.status === 204) return undefined as T;
  const payload = (await response.json()) as T & { csrf_token?: string };
  if (payload?.csrf_token) csrfToken = payload.csrf_token;
  return payload;
}

export type SearchStreamEvent =
  | { event: "results" | "answer"; data: SearchResult }
  | { event: "progress" | "partial" | "error"; data: { message: string } }
  | { event: "done"; data: { ok: boolean } };

async function searchStream(
  query: string,
  scope: LibraryScope,
  onEvent: (event: SearchStreamEvent) => void,
  signal?: AbortSignal,
): Promise<SearchResult> {
  const init: RequestInit = {
    method: "POST",
    body: JSON.stringify({ query, scope, limit: 50 }),
  };
  const response = await fetch("/api/search/stream", {
    ...init,
    credentials: "include",
    headers: withSecurityHeaders(init),
    signal,
  });
  if (!response.ok || !response.body) {
    throw new ApiError("Search is temporarily unavailable", response.status);
  }
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  let latest: SearchResult | null = null;

  while (true) {
    const { value, done } = await reader.read();
    buffer += decoder.decode(value, { stream: !done }).replace(/\r\n/g, "\n");
    const blocks = buffer.split("\n\n");
    buffer = blocks.pop() || "";
    for (const block of blocks) {
      let eventName = "message";
      const dataLines: string[] = [];
      for (const line of block.split("\n")) {
        if (line.startsWith("event:")) eventName = line.slice(6).trim();
        if (line.startsWith("data:")) dataLines.push(line.slice(5).trimStart());
      }
      if (!dataLines.length) continue;
      const event = {
        event: eventName,
        data: JSON.parse(dataLines.join("\n")),
      } as SearchStreamEvent;
      if (event.event === "results" || event.event === "answer") latest = event.data;
      onEvent(event);
    }
    if (done) break;
  }
  if (!latest) throw new ApiError("Search did not return any results", 503);
  return latest;
}

export const api = {
  session: () => request<Session>("/api/auth/session"),
  login: (username: string, password: string) =>
    request<Session>("/api/auth/login", {
      method: "POST",
      body: JSON.stringify({ username, password }),
    }),
  logout: () => request<void>("/api/auth/logout", { method: "POST" }),
  photos: (scope: LibraryScope) =>
    request<{ items: Photo[]; total: number; scope: LibraryScope }>(`/api/photos?scope=${scope}`),
  search: (query: string, scope: LibraryScope) =>
    request<SearchResult>(
      "/api/search",
      { method: "POST", body: JSON.stringify({ query, scope, limit: 50 }) },
    ),
  searchStream,
  libraryStatus: () => request<LibraryStatus>("/api/library/status"),
  people: () => request<Person[]>("/api/people"),
  namePerson: (id: string, name: string) =>
    request<Person>(`/api/people/${id}`, {
      method: "PATCH",
      body: JSON.stringify({ name }),
    }),
  places: () => request<Place[]>("/api/places"),
  events: () => request<PhotoEvent[]>("/api/events"),
  settings: () => request<Settings>("/api/settings"),
  updateSettings: (values: { developer_mode?: boolean; face_indexing_enabled?: boolean }) =>
    request<Settings>("/api/settings", {
      method: "PUT",
      body: JSON.stringify(values),
    }),
  deleteFaceData: () =>
    request<{ removed: number }>("/api/privacy/faces", {
      method: "DELETE",
    }),
  developerStatus: () => request<DeveloperStatus>("/api/developer/status"),
  createUpload: (file: File, relativePath: string) =>
    request<{ id: string; offset: number; size: number; chunk_size: number }>("/api/uploads/sessions", {
      method: "POST",
      body: JSON.stringify({
        filename: file.name,
        relative_path: relativePath,
        content_type: file.type || "application/octet-stream",
        size: file.size,
      }),
    }),
  uploadStatus: (id: string) =>
    request<{ id: string; offset: number; size: number; chunk_size: number }>(
      `/api/uploads/sessions/${id}`,
    ),
  uploadChunk: async (id: string, offset: number, chunk: Blob) => {
    const response = await fetch(`/api/uploads/sessions/${id}`, {
      method: "PATCH",
      credentials: "include",
      headers: {
        "Content-Type": "application/offset+octet-stream",
        "Upload-Offset": String(offset),
        ...(csrfToken ? { "X-CSRF-Token": csrfToken } : {}),
      },
      body: chunk,
    });
    if (!response.ok) throw new ApiError("Upload was interrupted", response.status);
    return response.json() as Promise<{ offset: number; size: number; chunk_size: number }>;
  },
  completeUpload: (id: string) =>
    request<{ photo: Photo; duplicate: boolean }>(`/api/uploads/sessions/${id}/complete`, {
      method: "POST",
    }),
};
