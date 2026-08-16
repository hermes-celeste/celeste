# DF-08 — Agent activity presentation

> **Historical investigation snapshot, captured 2026-08-15.** These notes have not been revalidated. They preserve source pointers and observations from the abandoned implementation effort; they do not prescribe product behavior or implementation. Recheck every relevant source immediately before using the information.

**Recovered from:** `backup/df-08-with-specs` → `docs/milestones/2026-08-15-first-device-pass/specs/DF-08-agent-activity-presentation.md`

## Recorded investigation

**Observed** means the cited source was inspected. **Open** requires confirmation against the exact Hermes runtime used for acceptance.

| Evidence | Type | Recorded observation |
| --- | --- | --- |
| [`docs/hermes-protocol.md:71-82`](../../hermes-protocol.md) | Observed | Celeste projects `message.*`, `session.*`, `tool.start`, `tool.complete`, and legacy `tool_call`/`tool_result` events. |
| [`docs/architecture.md:70-97`](../../architecture.md) | Observed | Stored session ID locates durable history, runtime session ID addresses live RPCs, resume applies a snapshot before buffered events, and the current tool reducer pairs completion by name rather than tool ID. |
| [`docs/security.md:44-59`](../../security.md) and [`docs/hermes-protocol.md:96`] | Observed | The repository treats raw conversation content, credentials, tool arguments/results, attachments, and live identifiers as sensitive. |
| Installed Hermes [`tui_gateway/methods_tools.py:1077-1214`] | Observed current server seam | `slash.exec` is session-scoped, routes special commands through `command.dispatch`, starts a per-session worker on demand, and returns bounded RPC output/errors. It is not evidence that arbitrary model tools may be called directly by a client. |
| Installed Hermes [`tui_gateway/methods_tools.py:331-366`] and [`tui_gateway/methods_complete.py:218-352`] | Observed current server seams | The catalog has `pairs`, `sub`, `canon`, `categories`, `skills`, `skill_count`, and `warning`; `complete.slash` returns bounded items and a replacement offset, derives skill/bundle entries from server providers, and adds metadata such as `kind`. |
| Installed Hermes [`tools/registry.py:1-15,108-159`] and [`model_tools.py:305-434`] | Observed current server seams | Built-in tools self-register and are filtered by configured toolsets before model calls. Tool schema registration/dispatch is an agent boundary, not a safe mobile “call this tool” API. |
| Installed Hermes [`hermes_cli/commands.py` — `SlashCommandCompleter` and command registry], [`hermes_cli/web_routers/skills.py`] | Observed current source | Command/skill metadata and skills administration are server-owned. |
| Current Hermes Desktop [`apps/desktop/src/lib/desktop-slash-commands.ts`], [`apps/desktop/src/lib/desktop-toolsets.ts`], [`apps/desktop/src/app/skills/mcp-tab.tsx`] | Observed comparison | Desktop has a richer local catalog and a Capabilities/skills/MCP administration surface. |
| Hermes Conduit at commit `858162a8493300aa37980419ebf007e22dbe4191`: [`SidebarView.swift`](https://github.com/kaishi00/hermes-conduit/blob/858162a8493300aa37980419ebf007e22dbe4191/Conduit/Views/SidebarView.swift), [`ComposerBar.swift`](https://github.com/kaishi00/hermes-conduit/blob/858162a8493300aa37980419ebf007e22dbe4191/Conduit/Views/Components/ComposerBar.swift), [`HermesClient.swift`](https://github.com/kaishi00/hermes-conduit/blob/858162a8493300aa37980419ebf007e22dbe4191/Conduit/Services/HermesClient.swift) | Observed comparison only | Conduit separates Sessions/Cron/Capabilities, provides slash filtering in the composer, renders reasoning/tool activity as different message roles, and keeps transport thin. It is not protocol authority. |

## Unresolved source and behavior questions

- Which exact current Hermes event type and payload field is the supported user-visible reasoning summary/full mode?
- Which exact Hermes event fields carry a stable tool-call ID for both start and completion, and is that ID retained in `session.resume`?
- Does the accepted server persist tool detail, or is it live-only?
