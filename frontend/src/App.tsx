import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import {
  ArrowLeft,
  Bug,
  CalendarDays,
  Check,
  ChevronRight,
  CircleAlert,
  CloudOff,
  FolderUp,
  Heart,
  ImagePlus,
  Images,
  Info,
  LoaderCircle,
  LogOut,
  Menu,
  MapPinned,
  Search,
  Settings as SettingsIcon,
  Sparkles,
  Upload,
  UserRound,
  UsersRound,
} from "lucide-react";
import { AnimatePresence, motion } from "motion/react";
import { FormEvent, useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError } from "./api";
import { Button } from "./components/ui/Button";
import { Modal } from "./components/ui/Modal";
import { Switch } from "./components/ui/Switch";
import type {
  DeveloperStatus,
  LibraryScope,
  Person,
  Photo,
  SearchResult,
  Session,
  Settings,
  UploadProgress,
} from "./types";

const suggestions = [
  "Sunsets near water",
  "Flowers in a garden",
  "Bicycles in the city",
  "Signs with visible text",
];

function formatDate(value: string | null | undefined) {
  if (!value) return "Date not set";
  return new Intl.DateTimeFormat(undefined, {
    day: "numeric",
    month: "long",
    year: "numeric",
  }).format(new Date(value));
}

function LoginScreen({ onLogin }: { onLogin: (session: Session) => void }) {
  const [username, setUsername] = useState("admin");
  const [password, setPassword] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  async function submit(event: FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError("");
    try {
      onLogin(await api.login(username, password));
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Unable to sign in right now");
    } finally {
      setBusy(false);
    }
  }

  return (
    <main className="login-page">
      <section className="login-visual" aria-label="A private collection of moments">
        <img src="/media/demo/lake-turquoise.jpg" alt="Turquoise mountain lake" />
        <div className="login-visual__shade" />
        <div className="login-brand">
          <span className="brand-mark"><Sparkles size={18} /></span>
          <span>AskPhotos</span>
        </div>
        <div className="login-copy">
          <p>Your photos, on your server.</p>
          <h1>Find the moment<br />you remember.</h1>
        </div>
        <div className="login-filmstrip" aria-hidden="true">
          <img src="/media/demo/flowers-colorful.jpg" alt="" />
          <img src="/media/demo/beach-marshall.jpg" alt="" />
          <img src="/media/demo/city-architecture.jpg" alt="" />
        </div>
      </section>

      <section className="login-panel">
        <form onSubmit={submit} className="login-form">
          <div className="login-form__heading">
            <span className="eyebrow">Private library</span>
            <h2>Welcome back</h2>
            <p>Sign in to browse and search the photos kept on this server.</p>
          </div>

          <label>
            <span>Username</span>
            <div className="field-with-icon">
              <UserRound size={18} />
              <input
                autoComplete="username"
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                required
              />
            </div>
          </label>
          <label>
            <span>Password</span>
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              required
            />
          </label>
          {error ? <p className="form-error" role="alert"><CircleAlert size={16} />{error}</p> : null}
          <Button type="submit" variant="primary" disabled={busy}>
            {busy ? <LoaderCircle className="spin" size={18} /> : null}
            Sign in
          </Button>
          <p className="privacy-note">Nothing in your library is sent to an external AI service.</p>
        </form>
      </section>
    </main>
  );
}

function PhotoCard({ photo, onOpen }: { photo: Photo; onOpen: (photo: Photo) => void }) {
  return (
    <motion.button
      initial={{ opacity: 0, y: 8 }}
      animate={{ opacity: 1, y: 0 }}
      className="photo-card"
      onClick={() => onOpen(photo)}
      aria-label={`Open ${photo.title}`}
    >
      <img src={photo.thumbnail_url} alt={photo.alt_text} loading="lazy" />
      <span className="photo-card__gradient" />
      <span className="photo-card__meta">
        <span>{photo.title}</span>
        <small>{photo.location_name || formatDate(photo.captured_at)}</small>
      </span>
      {photo.scope === "demo" ? <span className="demo-badge">Demo</span> : null}
      {photo.favorite ? <Heart className="photo-card__favorite" size={17} fill="currentColor" /> : null}
    </motion.button>
  );
}

function PhotoGrid({ photos, onOpen }: { photos: Photo[]; onOpen: (photo: Photo) => void }) {
  if (!photos.length) return null;
  return (
    <div className="photo-grid" aria-label="Photo results">
      {photos.map((photo) => <PhotoCard key={photo.id} photo={photo} onOpen={onOpen} />)}
    </div>
  );
}

function PhotoViewer({ photo, onClose }: { photo: Photo | null; onClose: () => void }) {
  return (
    <Modal open={Boolean(photo)} onOpenChange={(open) => !open && onClose()} title={photo?.title || "Photo"} className="photo-viewer">
      {photo ? (
        <div className="photo-viewer__layout">
          <div className="photo-viewer__canvas">
            <img src={photo.image_url} alt={photo.alt_text} />
          </div>
          <aside className="photo-viewer__info">
            <div className="detail-block">
              <span className="detail-label">When</span>
              <strong>{formatDate(photo.captured_at)}</strong>
            </div>
            <div className="detail-block">
              <span className="detail-label">Where</span>
              <strong>{photo.location_name || "Location not set"}</strong>
            </div>
            {photo.tags.length ? (
              <div className="tag-row">{photo.tags.slice(0, 5).map((tag) => <span key={tag}>{tag}</span>)}</div>
            ) : null}
            {photo.attribution ? (
              <div className="credits-card">
                <Info size={18} />
                <div>
                  <span>Demo photo credit</span>
                  <p>{photo.attribution.creator || "Public-domain contributor"}</p>
                  <a href={photo.attribution.source_url} target="_blank" rel="noreferrer">
                    {photo.attribution.license}<ChevronRight size={14} />
                  </a>
                </div>
              </div>
            ) : null}
          </aside>
        </div>
      ) : null}
    </Modal>
  );
}

function UploadModal({
  open,
  onOpenChange,
  onUploaded,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  onUploaded: () => void;
}) {
  const [queue, setQueue] = useState<UploadProgress[]>([]);
  const [dragging, setDragging] = useState(false);
  const fileInput = useRef<HTMLInputElement>(null);
  const folderInput = useRef<HTMLInputElement>(null);

  useEffect(() => {
    folderInput.current?.setAttribute("webkitdirectory", "");
    folderInput.current?.setAttribute("directory", "");
  }, []);

  function update(key: string, change: Partial<UploadProgress>) {
    setQueue((current) => current.map((item) => item.key === key ? { ...item, ...change } : item));
  }

  async function uploadFile(file: File) {
    const relativePath = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
    const key = `${relativePath}-${file.size}-${file.lastModified}`;
    update(key, { stage: "uploading", progress: 1 });
    try {
      const session = await api.createUpload(file, relativePath);
      let offset = session.offset;
      while (offset < file.size) {
        const chunk = file.slice(offset, Math.min(offset + session.chunk_size, file.size));
        let uploaded = false;
        for (let attempt = 0; attempt < 4 && !uploaded; attempt += 1) {
          try {
            const result = await api.uploadChunk(session.id, offset, chunk);
            offset = result.offset;
            uploaded = true;
          } catch (caught) {
            const current = await api.uploadStatus(session.id);
            if (current.offset > offset) {
              offset = current.offset;
              uploaded = true;
            } else if (attempt === 3) {
              throw caught;
            } else {
              await new Promise((resolve) => window.setTimeout(resolve, 500 * (attempt + 1)));
            }
          }
        }
        update(key, { progress: Math.max(2, Math.round((offset / file.size) * 92)) });
      }
      update(key, { stage: "preparing", progress: 96 });
      const completed = await api.completeUpload(session.id);
      update(key, {
        stage: "ready",
        progress: 100,
        message: completed.duplicate ? "Already in your library" : undefined,
      });
      onUploaded();
    } catch (caught) {
      update(key, {
        stage: "failed",
        message: caught instanceof ApiError ? caught.message : "Could not add this photo",
      });
    }
  }

  async function addFiles(files: FileList | File[]) {
    const selected = Array.from(files).filter((file) => file.type.startsWith("image/") || /\.(jpe?g|png|webp)$/i.test(file.name));
    const additions = selected.map((file) => {
      const relativePath = (file as File & { webkitRelativePath?: string }).webkitRelativePath || file.name;
      return {
        key: `${relativePath}-${file.size}-${file.lastModified}`,
        name: file.name,
        relativePath,
        progress: 0,
        stage: "queued" as const,
      };
    });
    setQueue((current) => [...current, ...additions]);
    for (const file of selected) await uploadFile(file);
  }

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="Add photos"
      description="Choose individual images or a complete folder. You can keep browsing while they are prepared."
      className="upload-modal"
    >
      <div
        className={`dropzone ${dragging ? "dropzone--active" : ""}`}
        onDragEnter={(event) => { event.preventDefault(); setDragging(true); }}
        onDragOver={(event) => event.preventDefault()}
        onDragLeave={() => setDragging(false)}
        onDrop={(event) => {
          event.preventDefault();
          setDragging(false);
          void addFiles(event.dataTransfer.files);
        }}
      >
        <span className="dropzone__icon"><ImagePlus size={28} /></span>
        <strong>Drop photos here</strong>
        <p>JPEG, PNG and WebP up to 250 MB each</p>
        <div className="dropzone__actions">
          <Button variant="primary" onClick={() => fileInput.current?.click()}><Upload size={17} />Choose files</Button>
          <Button onClick={() => folderInput.current?.click()}><FolderUp size={17} />Choose folder</Button>
        </div>
        <input
          ref={fileInput}
          className="sr-only"
          type="file"
          tabIndex={-1}
          aria-hidden="true"
          accept="image/jpeg,image/png,image/webp"
          multiple
          onChange={(event) => event.target.files && void addFiles(event.target.files)}
        />
        <input
          ref={(node) => {
            folderInput.current = node;
            if (node) {
              node.setAttribute("webkitdirectory", "");
              node.setAttribute("directory", "");
            }
          }}
          className="sr-only"
          type="file"
          tabIndex={-1}
          aria-hidden="true"
          accept="image/jpeg,image/png,image/webp"
          multiple
          onChange={(event) => event.target.files && void addFiles(event.target.files)}
        />
      </div>

      {queue.length ? (
        <div className="upload-queue" aria-live="polite">
          <div className="upload-queue__heading"><strong>Adding photos</strong><span>{queue.filter((item) => item.stage === "ready").length}/{queue.length} ready</span></div>
          {queue.map((item) => (
            <div className="upload-item" key={item.key}>
              <div className="upload-item__icon">
                {item.stage === "ready" ? <Check size={17} /> : item.stage === "failed" ? <CircleAlert size={17} /> : <LoaderCircle className={item.stage !== "queued" ? "spin" : ""} size={17} />}
              </div>
              <div className="upload-item__body">
                <div><strong>{item.name}</strong><span>{item.stage === "uploading" ? "Uploading" : item.stage === "preparing" ? "Getting ready" : item.stage === "ready" ? (item.message || "Ready") : item.stage === "failed" ? item.message : "Waiting"}</span></div>
                <div className="progress-track"><span style={{ width: `${item.progress}%` }} /></div>
              </div>
            </div>
          ))}
        </div>
      ) : null}
    </Modal>
  );
}

function ExploreModal({
  open,
  onOpenChange,
  settings,
  onSearch,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  settings?: Settings;
  onSearch: (query: string) => void;
}) {
  const queryClient = useQueryClient();
  const [section, setSection] = useState<"places" | "people" | "moments">("places");
  const [names, setNames] = useState<Record<string, string>>({});
  const places = useQuery({ queryKey: ["places"], queryFn: api.places, enabled: open, retry: false });
  const people = useQuery({
    queryKey: ["people"],
    queryFn: api.people,
    enabled: open && Boolean(settings?.face_indexing_enabled),
    retry: false,
  });
  const moments = useQuery({ queryKey: ["events"], queryFn: api.events, enabled: open, retry: false });
  const namePerson = useMutation({
    mutationFn: ({ person, name }: { person: Person; name: string }) => api.namePerson(person.id, name),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ["people"] }),
  });
  const activeQuery = section === "places" ? places : section === "people" ? people : moments;

  function search(query: string) {
    onOpenChange(false);
    onSearch(query);
  }

  return (
    <Modal
      open={open}
      onOpenChange={onOpenChange}
      title="Explore your library"
      description="Browse the places, people and moments found in your own photos."
      className="explore-modal"
    >
      <div className="explore-tabs" aria-label="Explore sections">
        <button aria-pressed={section === "places"} onClick={() => setSection("places")}><MapPinned size={16} />Places</button>
        <button aria-pressed={section === "people"} onClick={() => setSection("people")}><UsersRound size={16} />People</button>
        <button aria-pressed={section === "moments"} onClick={() => setSection("moments")}><CalendarDays size={16} />Moments</button>
      </div>

      {activeQuery.isLoading ? (
        <div className="center-state compact"><LoaderCircle className="spin" /><p>Opening your collections…</p></div>
      ) : null}
      {activeQuery.isError ? (
        <div className="notice notice--error"><CircleAlert size={18} /><div><strong>Collections are unavailable</strong><p>Your photo library is still ready to browse.</p></div></div>
      ) : null}

      {section === "places" && places.data ? (
        places.data.length ? (
          <div className="collection-list">
            {places.data.map((place) => (
              <button key={place.name} onClick={() => search(`photos in ${place.name}`)}>
                <span className="collection-icon"><MapPinned size={18} /></span>
                <span><strong>{place.name}</strong><small>{place.photo_count} photo{place.photo_count === 1 ? "" : "s"}</small></span>
                <ChevronRight size={17} />
              </button>
            ))}
          </div>
        ) : <div className="collection-empty"><MapPinned size={26} /><strong>No places yet</strong><p>Photos with location information will appear here.</p></div>
      ) : null}

      {section === "people" && !settings?.face_indexing_enabled ? (
        <div className="collection-empty"><UsersRound size={26} /><strong>Familiar faces are off</strong><p>You can turn on private face grouping in Settings.</p></div>
      ) : null}
      {section === "people" && settings?.face_indexing_enabled && people.data ? (
        people.data.length ? (
          <div className="people-list">
            {people.data.map((person, index) => {
              const currentName = names[person.id] ?? person.name ?? "";
              return (
                <div key={person.id} className="person-row">
                  <span className="collection-icon"><UserRound size={18} /></span>
                  <div>
                    <label className="sr-only" htmlFor={`person-${person.id}`}>Name person group {index + 1}</label>
                    <input
                      id={`person-${person.id}`}
                      value={currentName}
                      placeholder={`Person ${index + 1}`}
                      onChange={(event) => setNames((value) => ({ ...value, [person.id]: event.target.value }))}
                    />
                    <small>{person.photo_count} photo{person.photo_count === 1 ? "" : "s"}</small>
                  </div>
                  {person.name && currentName === person.name ? (
                    <Button size="sm" onClick={() => search(`photos of ${person.name}`)}>View</Button>
                  ) : (
                    <Button
                      size="sm"
                      disabled={!currentName.trim() || namePerson.isPending}
                      onClick={() => namePerson.mutate({ person, name: currentName.trim() })}
                    >
                      Save
                    </Button>
                  )}
                </div>
              );
            })}
          </div>
        ) : <div className="collection-empty"><UsersRound size={26} /><strong>No groups yet</strong><p>Face groups appear after your uploaded photos are prepared.</p></div>
      ) : null}

      {section === "moments" && moments.data ? (
        moments.data.length ? (
          <div className="collection-list">
            {moments.data.map((moment) => (
              <button key={moment.id} onClick={() => search(moment.location_name || moment.title)}>
                <span className="collection-icon"><CalendarDays size={18} /></span>
                <span><strong>{moment.title}</strong><small>{moment.photo_ids.length} photo{moment.photo_ids.length === 1 ? "" : "s"}</small></span>
                <ChevronRight size={17} />
              </button>
            ))}
          </div>
        ) : <div className="collection-empty"><CalendarDays size={26} /><strong>No personal moments yet</strong><p>Related uploads will be grouped here by time and place.</p></div>
      ) : null}
    </Modal>
  );
}

function SettingsModal({
  open,
  onOpenChange,
  settings,
  onUpdateDeveloper,
  onUpdateFaces,
  onDeleteFaces,
}: {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  settings?: Settings;
  onUpdateDeveloper: (enabled: boolean) => Promise<void>;
  onUpdateFaces: (enabled: boolean) => Promise<void>;
  onDeleteFaces: () => Promise<void>;
}) {
  return (
    <Modal open={open} onOpenChange={onOpenChange} title="Settings" description="Privacy and library preferences for this account." className="settings-modal">
      <div className="settings-list">
        <div className="settings-row">
          <div><strong>Private by default</strong><p>Your originals and search activity stay on this server.</p></div>
          <span className="status-pill"><Check size={14} />On</span>
        </div>
        <div className="settings-row">
          <div>
            <strong>Group familiar faces</strong>
            <p>Optional. Face signatures stay on this server and can be deleted at any time.</p>
            {settings?.face_indexing_enabled ? (
              <button className="text-action" onClick={() => void onDeleteFaces()}>Delete face data</button>
            ) : null}
          </div>
          <Switch
            label="Group familiar faces"
            checked={settings?.face_indexing_enabled || false}
            onCheckedChange={(value) => void onUpdateFaces(value)}
          />
        </div>
        {settings?.developer_feature_available ? (
          <div className="settings-row">
            <div><strong>Developer mode</strong><p>Show diagnostics and advanced system information.</p></div>
            <Switch
              label="Developer mode"
              checked={settings.developer_mode}
              onCheckedChange={(value) => void onUpdateDeveloper(value)}
            />
          </div>
        ) : null}
        <div className="settings-row">
          <div><strong>Demo library</strong><p>Open-licence photos remain separate from your uploads.</p></div>
          <span className="status-pill">Included</span>
        </div>
      </div>
    </Modal>
  );
}

function DeveloperDrawer({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const status = useQuery({
    queryKey: ["developer-status"],
    queryFn: api.developerStatus,
    enabled: open,
    retry: false,
  });
  return (
    <Modal open={open} onOpenChange={onOpenChange} title="Developer diagnostics" description="Local runtime and pipeline information." className="developer-modal">
      {status.isLoading ? <div className="center-state compact"><LoaderCircle className="spin" /><p>Loading diagnostics…</p></div> : null}
      {status.error ? <div className="notice notice--error"><CircleAlert size={18} />Diagnostics are unavailable.</div> : null}
      {status.data ? <DeveloperDetails status={status.data} /> : null}
    </Modal>
  );
}

export function DeveloperDetails({ status }: { status: DeveloperStatus }) {
  return (
    <dl className="diagnostic-grid" data-testid="developer-diagnostics">
      <div><dt>Environment</dt><dd>{status.environment}</dd></div>
      <div><dt>Database</dt><dd>{status.database}</dd></div>
      <div><dt>Queue</dt><dd>{status.queue_mode}</dd></div>
      <div><dt>Pipeline</dt><dd>{status.pipeline_version}</dd></div>
      <div><dt>Model profile</dt><dd>{status.model_profile}</dd></div>
    </dl>
  );
}

function EmptyLibrary({ scope, onUpload }: { scope: LibraryScope; onUpload: () => void }) {
  return (
    <div className="empty-state">
      <span><Images size={30} /></span>
      <h2>{scope === "personal" ? "Your library is ready" : "No matching photos"}</h2>
      <p>{scope === "personal" ? "Add a few photos, then ask about a place, person, color, or moment." : "Try another library or broaden your search."}</p>
      {scope === "personal" ? <Button variant="primary" onClick={onUpload}><ImagePlus size={17} />Add photos</Button> : null}
    </div>
  );
}

function AppShell({ session, onLogout }: { session: Session; onLogout: () => void }) {
  const queryClient = useQueryClient();
  const [scope, setScope] = useState<LibraryScope>("all");
  const [searchText, setSearchText] = useState("");
  const [activeQuery, setActiveQuery] = useState("");
  const [selectedPhoto, setSelectedPhoto] = useState<Photo | null>(null);
  const [uploadOpen, setUploadOpen] = useState(false);
  const [exploreOpen, setExploreOpen] = useState(false);
  const [settingsOpen, setSettingsOpen] = useState(false);
  const [developerOpen, setDeveloperOpen] = useState(false);
  const [streamedResult, setStreamedResult] = useState<SearchResult | null>(null);
  const [searchProgress, setSearchProgress] = useState("");
  const searchAbort = useRef<AbortController | null>(null);

  const settingsQuery = useQuery({ queryKey: ["settings"], queryFn: api.settings, retry: false });
  const photosQuery = useQuery({ queryKey: ["photos", scope], queryFn: () => api.photos(scope), retry: false });
  const libraryStatusQuery = useQuery({
    queryKey: ["library-status"],
    queryFn: api.libraryStatus,
    refetchInterval: (query) => query.state.data?.getting_ready ? 5000 : false,
    retry: false,
  });
  const searchMutation = useMutation({
    mutationFn: ({ query, searchScope, signal }: { query: string; searchScope: LibraryScope; signal: AbortSignal }) =>
      api.searchStream(query, searchScope, (event) => {
        if (event.event === "results" || event.event === "answer") {
          setStreamedResult(event.data);
        } else if (event.event === "progress" || event.event === "partial") {
          setSearchProgress(event.data.message);
        }
      }, signal),
    onSuccess: (result) => {
      setStreamedResult(result);
      setSearchProgress("");
    },
    onError: () => setSearchProgress(""),
  });

  const searchResult = streamedResult || searchMutation.data;
  const visiblePhotos = activeQuery ? (searchResult?.items || []) : (photosQuery.data?.items || []);
  const groupedLabel = useMemo(() => {
    if (activeQuery) return `${visiblePhotos.length} result${visiblePhotos.length === 1 ? "" : "s"}`;
    if (!visiblePhotos.length) return "";
    return scope === "demo" ? "Demo library" : scope === "personal" ? "My photos" : "All moments";
  }, [activeQuery, scope, visiblePhotos]);

  function submitSearch(event?: FormEvent, suggestion?: string) {
    event?.preventDefault();
    const query = (suggestion || searchText).trim();
    if (!query) return;
    setSearchText(query);
    setActiveQuery(query);
    setStreamedResult(null);
    setSearchProgress("Finding likely matches…");
    searchAbort.current?.abort();
    searchAbort.current = new AbortController();
    searchMutation.mutate({ query, searchScope: scope, signal: searchAbort.current.signal });
  }

  async function updateDeveloperMode(enabled: boolean) {
    await api.updateSettings({ developer_mode: enabled });
    await queryClient.invalidateQueries({ queryKey: ["settings"] });
    if (!enabled) setDeveloperOpen(false);
  }

  async function updateFaceIndexing(enabled: boolean) {
    await api.updateSettings({ face_indexing_enabled: enabled });
    await queryClient.invalidateQueries({ queryKey: ["settings"] });
    await queryClient.invalidateQueries({ queryKey: ["library-status"] });
  }

  async function deleteFaceData() {
    await api.deleteFaceData();
    await api.updateSettings({ face_indexing_enabled: false });
    await queryClient.invalidateQueries({ queryKey: ["settings"] });
  }

  function clearSearch() {
    searchAbort.current?.abort();
    setSearchText("");
    setActiveQuery("");
    setStreamedResult(null);
    setSearchProgress("");
    searchMutation.reset();
  }

  return (
    <div className="app-shell">
      <aside className="side-rail">
        <div className="rail-brand"><span className="brand-mark"><Sparkles size={17} /></span><span>AskPhotos</span></div>
        <nav aria-label="Primary navigation">
          <button className="nav-item nav-item--active" onClick={clearSearch}><Images size={19} /><span>Photos</span></button>
          <button className="nav-item" onClick={() => setExploreOpen(true)}><MapPinned size={19} /><span>Explore</span></button>
          <button className="nav-item" onClick={() => setUploadOpen(true)}><ImagePlus size={19} /><span>Add photos</span></button>
          <button className="nav-item" onClick={() => setSettingsOpen(true)}><SettingsIcon size={19} /><span>Settings</span></button>
        </nav>
        <div className="rail-footer">
          <div className="user-chip"><span>{session.username?.slice(0, 1).toUpperCase()}</span><div><strong>{session.username}</strong><small>Private library</small></div></div>
          <Button size="icon" variant="ghost" aria-label="Sign out" onClick={onLogout}><LogOut size={18} /></Button>
        </div>
      </aside>

      <main className="library-page">
        <header className="mobile-header">
          <div className="rail-brand"><span className="brand-mark"><Sparkles size={17} /></span><span>AskPhotos</span></div>
          <Button size="icon" variant="ghost" aria-label="Open settings" onClick={() => setSettingsOpen(true)}><Menu size={21} /></Button>
        </header>

        <section className="search-zone">
          <div className="search-zone__topline">
            <div><span className="eyebrow">Private photo library</span><h1>{activeQuery ? "Search results" : "Your moments"}</h1></div>
            <div className="top-actions">
              {settingsQuery.data?.developer_mode ? (
                <Button variant="ghost" size="icon" aria-label="Open developer diagnostics" onClick={() => setDeveloperOpen(true)}><Bug size={19} /></Button>
              ) : null}
              <Button variant="primary" onClick={() => setUploadOpen(true)}><ImagePlus size={17} />Add photos</Button>
            </div>
          </div>

          <form className="search-composer" onSubmit={(event) => submitSearch(event)}>
            {activeQuery ? <button type="button" className="search-back" aria-label="Clear search" onClick={clearSearch}><ArrowLeft size={20} /></button> : <Search size={21} />}
            <input value={searchText} onChange={(event) => setSearchText(event.target.value)} placeholder="Ask about your photos…" aria-label="Ask about your photos" />
            <Button
              type="submit"
              variant="primary"
              aria-label="Search photos"
              disabled={!searchText.trim() || searchMutation.isPending}
            >
              {searchMutation.isPending ? <LoaderCircle className="spin" size={17} /> : <Sparkles size={17} />}
              <span>Search</span>
            </Button>
          </form>

          <div className="search-options">
            <div className="scope-control" aria-label="Library scope">
              {(["all", "personal", "demo"] as LibraryScope[]).map((value) => (
                <button key={value} className={scope === value ? "active" : ""} onClick={() => { setScope(value); setActiveQuery(""); }}>
                  {value === "all" ? "All photos" : value === "personal" ? "My photos" : "Demo"}
                </button>
              ))}
            </div>
            {!activeQuery ? <div className="suggestion-row">{suggestions.map((suggestion) => <button key={suggestion} onClick={() => submitSearch(undefined, suggestion)}>{suggestion}</button>)}</div> : null}
          </div>
          {libraryStatusQuery.data?.getting_ready ? (
            <p className="library-progress" role="status">
              {libraryStatusQuery.data.getting_ready} photo{libraryStatusQuery.data.getting_ready === 1 ? "" : "s"} getting ready. You can keep browsing.
            </p>
          ) : null}
        </section>

        <section className="gallery-section">
          {searchMutation.isPending ? (
            <div className="searching-state" role="status"><span><Sparkles size={20} /></span><div><strong>Looking through your photos…</strong><p>{searchProgress || "Matching the moment you described"}</p></div></div>
          ) : null}
          {activeQuery && searchMutation.isError ? <div className="notice notice--error"><CloudOff size={19} /><div><strong>Search is temporarily unavailable</strong><p>Your library is still available to browse.</p></div></div> : null}
          {activeQuery && searchResult ? (
            <div className="search-summary">
              <span className="search-summary__icon"><Sparkles size={19} /></span>
              <div><span>About your search</span><p>{searchResult.summary}</p></div>
            </div>
          ) : null}
          {groupedLabel ? <div className="gallery-heading"><h2>{groupedLabel}</h2><span>{visiblePhotos.length} photos</span></div> : null}
          {photosQuery.isLoading && !activeQuery ? <div className="skeleton-grid" aria-label="Loading photos">{Array.from({ length: 10 }).map((_, index) => <span key={index} />)}</div> : null}
          {photosQuery.isError && !activeQuery ? <div className="center-state"><CloudOff size={30} /><h2>Server unavailable</h2><p>Check that the photo server is running, then try again.</p><Button onClick={() => void photosQuery.refetch()}>Try again</Button></div> : null}
          {!photosQuery.isLoading && !photosQuery.isError && !visiblePhotos.length && !searchMutation.isPending ? <EmptyLibrary scope={scope} onUpload={() => setUploadOpen(true)} /> : null}
          <AnimatePresence mode="popLayout"><PhotoGrid photos={visiblePhotos} onOpen={setSelectedPhoto} /></AnimatePresence>
        </section>
      </main>

      <nav className="mobile-tabs" aria-label="Mobile navigation">
        <button className="active" onClick={clearSearch}><Images size={21} /><span>Photos</span></button>
        <button onClick={() => document.querySelector<HTMLInputElement>('.search-composer input')?.focus()}><Search size={21} /><span>Search</span></button>
        <button onClick={() => setExploreOpen(true)}><MapPinned size={21} /><span>Explore</span></button>
        <button onClick={() => setUploadOpen(true)}><ImagePlus size={21} /><span>Add</span></button>
        <button onClick={() => setSettingsOpen(true)}><SettingsIcon size={21} /><span>Settings</span></button>
      </nav>

      <PhotoViewer photo={selectedPhoto} onClose={() => setSelectedPhoto(null)} />
      <UploadModal open={uploadOpen} onOpenChange={setUploadOpen} onUploaded={() => void queryClient.invalidateQueries({ queryKey: ["photos"] })} />
      <ExploreModal
        open={exploreOpen}
        onOpenChange={setExploreOpen}
        settings={settingsQuery.data}
        onSearch={(query) => submitSearch(undefined, query)}
      />
      <SettingsModal
        open={settingsOpen}
        onOpenChange={setSettingsOpen}
        settings={settingsQuery.data}
        onUpdateDeveloper={updateDeveloperMode}
        onUpdateFaces={updateFaceIndexing}
        onDeleteFaces={deleteFaceData}
      />
      {settingsQuery.data?.developer_mode ? <DeveloperDrawer open={developerOpen} onOpenChange={setDeveloperOpen} /> : null}
    </div>
  );
}

export default function App() {
  const [session, setSession] = useState<Session | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api.session().then(setSession).catch(() => setSession(null)).finally(() => setLoading(false));
  }, []);

  async function logout() {
    await api.logout();
    setSession(null);
  }

  if (loading) return <div className="app-loading"><span className="brand-mark"><Sparkles size={20} /></span><LoaderCircle className="spin" /></div>;
  if (!session) return <LoginScreen onLogin={setSession} />;
  return <AppShell session={session} onLogout={() => void logout()} />;
}
