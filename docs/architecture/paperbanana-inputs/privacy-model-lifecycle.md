# Agentic Gallery privacy and verified model lifecycle

Create a clear security-architecture diagram for a private, on-device Android gallery. The figure should explain how optional models enter the app, how they are verified and activated, and which privacy gates protect data.

Split the diagram into three connected regions inside an "Android device" boundary.

Region 1: model sources and activation
- Embedded build assets:
  - Pinned SigLIP2 retrieval archive
  - Optional embedded SFace identity archive
- Consumer-only, user-started downloads:
  - Gemma 4 E2B or E4B
  - PP-OCRv5 Mobile
  - SFace when not embedded
- User-imported signed model pack
- All sources converge on:
  - Immutable catalog specification
  - Size and SHA-256 verification
  - Signature verification where applicable
  - App-private staging
  - Atomic generation activation
  - Rollback on load failure
- Output: one active verified generation per model family

Region 2: serialized local inference
- AppServices
- SerializedInferenceResourceManager
- Shared GemmaSessionManager
- LiteRT-LM GPU with CPU fallback
- LiteRT SigLIP2 embedding engine
- OCR engine registry
- Face engine registry
- Memory-pressure and idle eviction
- Model packs are read only by local engines
- No application server or cloud inference

Region 3: privacy and data-policy gates
- MediaStore originals remain owned by the system; app reads granted content URIs
- App-private Room / SQLite / FTS
- App-private previews, keyframes, vectors, evidence, and model packs
- People indexing is off by default and requires explicit opt-in
- Identity queries require verified SFace embeddings plus user-reviewed clusters
- Hidden and unreviewed clusters never resolve as identities
- Sensitive OCR is classified and locked behind biometric or device-credential authentication
- Authentication-protected OCR is excluded from background semantic enrichment
- Semantic facts are typed, allowlisted, provenance-tagged, and cannot contain secrets
- Removing a label, hiding a cluster, or purging the people index does not modify original media

Show data flows:
- Verified models feed only local inference.
- Local inference writes bounded derived artifacts and provenance to private storage.
- The UI receives capabilities and evidence, not unsupported model claims.
- Privacy gates sit between stored sensitive evidence and UI/background inference.

Add two concise trust statements:
- "Runtime media analysis stays on device"
- "Internet permission, when present, is for explicit model download—not inference"

Visual style:
- 16:9 security architecture, white background.
- Use navy and teal for trusted local components, violet for models, amber for consent/authentication gates, red only for blocked paths.
- Include lock, shield, checksum, and atomic-switch symbols sparingly.
- Use dashed red blocked arrows from private media to cloud inference and from protected OCR to background Gemma.
- Do not include a decorative title inside the figure.
- Ensure exact labels are readable at README width.
