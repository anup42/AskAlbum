# Agentic Gallery progressive grounded-query pipeline

Create a publication-quality flowchart showing how a natural-language gallery request becomes progressive, evidence-grounded results entirely on an Android device. Use exact labels and clearly distinguish Kotlin-owned deterministic logic from optional Gemma inference.

Main left-to-right flow:

1. User and UI
   - Ask screen submits a natural-language query
   - GalleryViewModel exposes cancellable progressive states
   - States: Understanding -> Plan ready -> Initial results -> Verifying -> Composing answer -> Completed

2. Bounded planning
   - LiteRtLmQueryPlanner
   - If a verified Gemma pack is active: one constrained plan call plus at most one schema-repair call
   - Otherwise: deterministic QueryCompiler fallback
   - DeterministicPlanOverlay
   - GalleryQueryPlanValidator
   - Output: typed GalleryQueryPlan with intent, media scope, time/filter, terms, people clauses, OCR clause, grouping, aggregation, sort, verification, answer mode, and limit
   - For contextual follow-ups, apply an app-owned PlanPatch to the persisted active result set; the model never supplies result IDs

3. Kotlin-owned scope and hard eligibility
   - Reviewed-person alias resolution
   - PeopleQueryGate fails closed if identity search is not ready
   - Media kind, time, album, place, merchant, people, and active-result filters
   - Negative and HARD constraints remain app-owned

4. Parallel retrieval channels
   - Lexical: SQLite FTS plus metadata, labels, and OCR
   - Semantic: SigLIP2 text embedding and memory-mapped FP16 vector search
   - Event: compiled event index
   - People: reviewed identity links only
   - OCR: structured blocks and allowlisted document facts
   - Each channel produces a typed status: success, unavailable, failed, partial, or not required

5. Ranking and initial response
   - HybridRankFusion using reciprocal-rank style fusion
   - DuplicateCollapse
   - EventDiversity
   - Add cached semantic facts with provenance
   - Emit initial result cards immediately

6. Optional bounded visual verification
   - VisualVerificationPolicy
   - LiteRtGemmaVisualVerifier
   - Loads only bounded candidate images or matched private video keyframes
   - Evaluates typed conditions; Kotlin computes final HARD-condition acceptance
   - GPU then CPU fallback through shared GemmaSessionManager

7. Answer and safety
   - CapabilityAnswerExecutor performs deterministic count, sum, min/max, list, timeline, compare, and document fact QA
   - SensitiveEvidencePolicy locks protected OCR until biometric or device-credential authentication
   - Optional LiteRtGemmaGroundedAnswerComposer receives only bounded evidence
   - GroundedClaimValidator rejects unsupported claims and unknown evidence IDs

8. Persisted outcome
   - SearchOutcome: hits, exactness, warnings, evidence IDs, channel reports, timing
   - Persist result set and conversation state for safe follow-ups
   - "Why this answer?" shows evidence and provenance

Emphasize these trust boundaries:
- Gemma proposes typed plans and phrasing; Kotlin owns validation, IDs, filters, arithmetic, acceptance, and privacy.
- No SQL, file paths, content URIs, or arbitrary execution can come from model output.
- No cloud inference.

Visual style:
- 16:9 white background, crisp academic flowchart.
- Navy for deterministic Kotlin control, violet for optional Gemma steps, teal for data/evidence, amber for safety gates.
- Use a subtle parallel-channel fan-out and merge in the center.
- Use a small trust-boundary callout, not a large decorative title.
- Make labels readable at README width and avoid tiny prose.
