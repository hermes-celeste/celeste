# CF-07 — Profile, project, and workspace awareness

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-07-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/CF-07-profile-project-workspace-awareness.md`

## Recorded investigation

Profile management and broader Desktop management surfaces are not currently implemented: `product.md:31-39`.

### Current Celeste

- The application state currently has `profiles` and `selectedProfile`, but no project or workspace projection: `CelesteViewModel.kt:64-88`. `selectProfile` only changes the local selected name and validates it against the current list: `CelesteViewModel.kt:146-153`.
- The session list exposes a profile dropdown, but the profile action is not a context transaction; the screen has no project/workspace context: `SessionListScreen.kt:54-130`, `CelesteRoutes.kt:79-119`.
- Current network models carry a session’s profile and a profile’s display/model metadata, but no project/workspace identity: `DashboardClient.kt:60-75`, `GatewaySessionApi.kt:17-46`.
- Celeste lists profiles over `/api/profiles`, treats only a 404 profile route as older single-profile compatibility, and lists sessions separately: `DashboardClient.kt:331-368`, `hermes-protocol.md:26-41`. Current `session.create` sends a profile and `session.resume` sends a stored session ID, but neither currently carries a project/workspace target: `GatewaySessionApi.kt:23-81`.
- The architecture already assigns application-state ownership to `CelesteViewModel`, keeps transport thin, and states that the dashboard is the source of truth rather than a competing history database: `architecture.md:5-18`, `architecture.md:26-37`, `architecture.md:57-68`.
- Existing origin-bound saved-connection metadata and Keystore rules bind reusable authentication to the exact normalized endpoint/path/auth mode; Sign out/Forget invalidate persistence: `SavedConnection.kt:12-35`, `security.md:30-36`.

### Hermes Desktop and current server authority

- Hermes Desktop normalizes profile keys, gets the running profile from `/api/profiles/active`, refreshes the server profile catalog, and persists/switches the active profile through the Desktop profile API: `apps/desktop/src/store/profile.ts:20-46`, `apps/desktop/src/store/profile.ts:105-142`.
- Desktop invalidates profile-scoped settings/session caches on active gateway profile changes, syncs the connection descriptor to the active profile, serializes gateway activation, and requests a fresh session when switching context: `apps/desktop/src/store/profile.ts:144-188`, `apps/desktop/src/store/profile.ts:257-307`, `apps/desktop/src/store/profile.ts:310-350`.
- Desktop Projects are explicitly per-profile and served by live-gateway `projects.*` methods. The project tree and session membership are server-authoritative; a missing RPC is tracked as capability unavailability: `apps/desktop/src/store/projects.ts:29-60`, `apps/desktop/src/store/projects.ts:137-169`, `apps/desktop/src/store/projects.ts:327-387`.
- Desktop guards project requests by gateway/profile identity and generation, supports profile-scoped/all-profile tree refresh, and lazy-loads project session details rather than inventing membership in the renderer: `apps/desktop/src/store/projects.ts:352-369`, `apps/desktop/src/store/projects.ts:389-513`.
- Desktop’s session navigation and workspace-CWD persistence are explicitly profile-scoped, origin/profile-keyed for remote workspaces, and discard legacy global keys rather than guessing ownership: `apps/desktop/src/store/session.ts:15-78`, `apps/desktop/src/store/session.ts:85-168`.
- The installed Hermes server documents `project_tree.py` as the authoritative project → repo → lane → session builder, with stable server IDs and injected git resolution; project grouping is not a renderer guess: `tui_gateway/project_tree.py:1-24`, `tui_gateway/project_tree.py:328-401`, `tui_gateway/project_tree.py:450-500`.
- Current server RPCs expose the authoritative project tree and hydrated project sessions; the server registers these capability methods and can return a missing-method error on older gateways: `tui_gateway/methods_config.py:108-145`, `tui_gateway/server.py:265-275`. Desktop’s protocol types keep project IDs, folders, and active pointers distinct: `apps/desktop/src/types/hermes.ts:939-966`, while session rows carry `cwd`, server-resolved git identity, and owning profile: `apps/desktop/src/types/hermes.ts:464-523`.

### Hermes Conduit comparison evidence

- Conduit labels its profile picker as the workspace context and tells users that sessions/settings follow the active profile; switching is disabled while a switch is in flight: `Conduit/Views/ProfilePickerSheet.swift:13-59`, `Conduit/Views/ProfilePickerSheet.swift:109-137`.
- Conduit’s drawer always shows the active profile, offers session/project presentation, and disables new-project UI when the server capability is absent: `Conduit/Views/SidebarView.swift:39-65`, `Conduit/Views/SidebarView.swift:145-319`.
- Conduit models projects as server-owned, profile-scoped groupings and states that it only presents authoritative membership; its turn state is based on authoritative gateway snapshots rather than local animation: `Conduit/Models/Models.swift:273-316`, `Conduit/Models/Models.swift:378-415`.
- Conduit isolates published session lists by active profile, clears session/project/projection state when the normalized server identity changes, and uses a profile/client/transition guard during profile switching: `Conduit/Services/AppState.swift:133-144`, `Conduit/Services/AppState.swift:390-399`, `Conduit/Services/AppState.swift:1375-1411`, `Conduit/Services/AppState.swift:5836-5973`.
- Conduit loads profiles from Hermes with graceful legacy fallback and loads projects only against the current client/profile/generation; missing project RPCs become `supportsProjects = false` instead of local state: `Conduit/Services/AppState.swift:5977-5993`, `Conduit/Services/AppState.swift:6047-6114`.
- Conduit’s workspace browser uses the session-reported `runtime.cwd` and `/api/fs/list` scoped by active profile, rejects stale profile results, and exposes an explicit unavailable state when no working directory is reported: `Conduit/Services/AppState.swift:6153-6183`, `Conduit/Views/ChatSupportSheets.swift:115-180`.
- Conduit’s thin Hermes client keeps transport/request correlation separate from session, busy, and reconnect ownership: `Conduit/Services/HermesClient.swift:7-23`. Its chat-resume policy filters catalog entries by active profile before selecting a session: `Conduit/Services/ChatResumePolicy.swift:27-42`.

## Unresolved source and behavior questions

- Which current Hermes versions and RPC/HTTP shapes expose `projects.tree`, `projects.project_sessions`, active project, session project metadata, and server filesystem listing?
- Does Hermes expose an authoritative workspace display label in addition to `cwd`?
- Is a project tree snapshot guaranteed to carry stable session membership/counts?
