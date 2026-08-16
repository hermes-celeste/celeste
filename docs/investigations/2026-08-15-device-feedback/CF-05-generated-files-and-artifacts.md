# CF-05 — Generated files and artifacts

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-05-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/CF-05-generated-files-and-artifacts.md`

## Recorded investigation

The evidence rules require current Hermes server/Desktop behavior as protocol authority and prohibit private payloads in the public repository (`README.md:43-49`).

The Hermes Desktop source snapshot used for the audit was upstream commit `366fd70f98e42ae4fa2afdb3b05e306b3bddaa55`:

- `apps/desktop/src/store/artifacts.ts` — artifact registry, stable artifact slugs/content hashes, loading artifacts for sessions, preview-tab ownership, and artifact deduplication.
- `apps/desktop/src/lib/artifact-detect.ts` — `ArtifactKind` (`image`, `file`, `link`), artifact detection, content hash/slug, and safe filename/type decisions.
- `apps/desktop/src/app/artifacts/index.tsx` — artifact listing/filter counts and opening an artifact from the registry.
- `apps/desktop/src/components/chat/artifact-card.tsx` and `apps/desktop/src/app/right-sidebar/preview-artifact.tsx` — preview/download/save affordances, sanitized HTML preview, and explicit artifact actions.
- `apps/desktop/src/lib/download-text.ts` and `apps/desktop/src/hooks/use-image-download.ts` — bounded text/image download behavior and user-visible failure handling.
- `apps/desktop/src/lib/desktop-fs.ts`, `apps/desktop/src/app/right-sidebar/files/ipc.ts`, and `apps/desktop/electron/preload.ts` — the distinction between remote filesystem reads and artifact UI, plus explicit read/save/open IPC bridges rather than arbitrary renderer filesystem access.
- `apps/desktop/electron/hardening.ts` and `apps/desktop/electron/main.ts` — path safety, sensitive-path rejection, MIME/extension handling, bounded data-URL reads, save dialogs, and constrained external opening.
- `hermes_cli/web_server.py:2099-2198` — regular-file validation and bounded filesystem read endpoints; `tui_gateway/methods_prompt.py:1006-1010` and surrounding `file.attach`/image/PDF handlers — explicit attach capabilities rather than an implicit workspace export.

Hermes Conduit comparison evidence was upstream commit `858162a8493300aa37980419ebf007e22dbe4191`:

- `Conduit/Views/ChatSupportSheets.swift:115-240` — workspace browsing and a separate preview sheet; this is interaction evidence, not authority for a new Celeste protocol.
- `Conduit/Services/DashboardPath.swift:1-22` — profile-scoped dashboard paths and encoded query components.
- `Conduit/Services/DashboardTicketBridge.swift` (`DashboardTicketBridge`) and `Conduit/Services/HermesClient.swift` (`HermesClient`) — origin-bound dashboard cookies/single-use WebSocket tickets and a thin authenticated RPC client.
- `DataURLLimits.swift:1-15` — a bounded 16 MiB decoded-data limit and 24 MiB JSON-response limit; this is a useful safety calibration for inline/data-URL handling, not proof of a generated-artifact RPC.

Current Celeste seams audited for this feature are `app/src/main/java/dev/hazydreams/hermesceleste/CelesteViewModel.kt`, `app/src/main/java/dev/hazydreams/hermesceleste/network/HermesGateway.kt`, `app/src/main/java/dev/hazydreams/hermesceleste/network/GatewaySessionApi.kt`, `app/src/main/java/dev/hazydreams/hermesceleste/network/DashboardClient.kt`, existing transcript UI, and `app/src/test/java/dev/hazydreams/hermesceleste/network/HermesGatewayTest.kt`.
