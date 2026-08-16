# CF-08 — Command and capability discovery

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-08-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/CF-08-command-and-capability-discovery.md`

## Recorded investigation

| Evidence | Type | Recorded observation |
| --- | --- | --- |
| [`docs/product.md:18-39`](../../product.md) | Observed | Hermes owns profiles, sessions, messages, and capabilities. |
| [`docs/architecture.md:20-68,70-97`](../../architecture.md) | Observed | Compose emits intent, `CelesteViewModel` owns session state, the gateway is thin, stored/runtime IDs differ, and the server remains the source of truth. |
| [`docs/hermes-protocol.md:1-12,26-40,55-69,84-96`](../../hermes-protocol.md) | Observed | Unknown routes/methods are compatibility states, not invitations to shell out. |
| Installed Hermes [`tui_gateway/methods_tools.py:331-366`] | Observed current server seam | `commands.catalog` returns command pairs, subcommands, canonical names, categories, skill metadata, count, and an optional warning. |
| Installed Hermes [`tui_gateway/methods_complete.py:218-352`] | Observed current server seam | `complete.slash` accepts the composer text and returns bounded completion items plus `replace_from`; it uses the same server skill/bundle providers and marks `kind`. This is the authoritative autocomplete seam. |
| Installed Hermes [`tui_gateway/methods_tools.py:1077-1214`] | Observed current server seam | `slash.exec` validates a session, routes pending-input/bundle/skill/plugin cases, uses a per-session worker for remaining commands, and returns output/warnings or bounded errors. |
| Installed Hermes [`tools/registry.py:1-15,108-159`] and [`model_tools.py:305-434`] | Observed current server seam | Built-in tools self-register with schema/handler/toolset and are filtered by enabled/disabled toolsets. This is the model dispatch boundary, not a mobile API contract for arbitrary direct tool calls. |
| Installed Hermes [`hermes_cli/web_routers/skills.py`] and [`hermes_cli/mcp_catalog.py`] | Observed current source | Skills/MCP administration and catalog helpers exist in server source. |
| Current Desktop [`apps/desktop/src/lib/desktop-slash-commands.ts`], [`apps/desktop/src/lib/desktop-toolsets.ts`], [`apps/desktop/src/app/skills/mcp-tab.tsx`] | Observed comparison | Desktop has slash metadata, toolset/skill/MCP discovery, and administration toggles. |
| Hermes Conduit at commit `858162a8493300aa37980419ebf007e22dbe4191`: [`SidebarView.swift`](https://github.com/kaishi00/hermes-conduit/blob/858162a8493300aa37980419ebf007e22dbe4191/Conduit/Views/SidebarView.swift), [`ComposerBar.swift`](https://github.com/kaishi00/hermes-conduit/blob/858162a8493300aa37980419ebf007e22dbe4191/Conduit/Views/Components/ComposerBar.swift), [`HermesClient.swift`](https://github.com/kaishi00/hermes-conduit/blob/858162a8493300aa37980419ebf007e22dbe4191/Conduit/Services/HermesClient.swift) | Observed comparison only | Conduit’s Capabilities tab groups skills/toolsets and its composer filters slash commands; its thin JSON-RPC client and reconnect authority are useful interaction evidence, not protocol authority. |

## Unresolved source and behavior questions

- What is the exact current JSON-RPC response shape and identity requirement for `commands.catalog` in the installed Hermes server, and is it profile-scoped or only process-scoped?
- Does current Hermes expose a remote, read-only toolset/MCP capability route/RPC for a non-Desktop client?
- Which catalog field, if any, authorizes the `command.dispatch` versus `slash.exec` path?
- Which commands are considered destructive/administrative, and does current Hermes publish that metadata?
