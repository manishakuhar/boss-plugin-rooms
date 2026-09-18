# BOSS Rooms

Native Kotlin/Compose BOSS plugin with shared conversations and a private, tool-using personal assistant. **First implementation; not deployed or certified for production.**

## Implemented

- Explicit organization selection; private or organization rooms, direct/group conversations, authenticated message posting, threads, search and pins.
- **My assistant** is a private, single-member conversation inside Rooms. Earlier questions and answers are included in subsequent prompts (bounded to the latest 40 messages / 32,000 characters). Older history remains stored but is not automatically all sent to the model.
- Editable **What my assistant knows** background, stored per user and organization with revision checks. Save an empty value to clear it. Team-room agent runs never load this memory or personal chat history.
- Native `AiGatewayAPI.runAgent` integration for tool-capable providers, plus a bounded application-owned action loop over `complete` for Codex CLI and other text gateways. Scoped search/thread tools, approved reading of other conversations from My assistant, proposed messages and explicitly selected BOSS MCP tools. No scripted agent response or custom API-key store.
- Human review of the exact destination/body before sending elsewhere, and review of every selected connected-tool call. Run budget: eight steps, 12,000 reported tokens before further completion-loop actions (native gateway budget: 12,000 tokens), 110-second native gateway budget within a 120-second overall timeout. Stop cancels pending work; it cannot undo actions already committed.
- Account/org-scoped operations, server-side membership checks, idempotent post requests, revision-checked edits/deletion/pins, pagination and 5-second polling.
- Plugin UI has no invented task board or separate decision workflow. Native UI includes message edit/delete and room owner membership editing and transfer.

## Build

Requires JDK 17 and Python 3. SDK 1.0.93 is checksum-pinned and matches BOSS v9.5.20's published SDK pin.

```
python3 scripts/fetch-sdk.py
./gradlew test buildPluginJar
cd backend
npm ci
npm test
```

Artifact: `build/libs/boss-plugin-rooms-0.1.0.jar`. The plugin declares BOSS 9.5.20+ and SDK 1.0.93+. AI Gateway must be installed, configured with a model. Missing providers produce a visible error; the plugin never silently substitutes fake AI.

## Backend deployment prerequisite

A BOSS backend owner must review and install `backend/001_rooms.sql`, `backend/002_delivery.sql`, then `backend/003_attachments.sql` through the normal migration process. **This repository does not deploy it automatically.** The migration expects BOSS `organisations`, `organisation_members`, `auth.users` and `auth.uid()`. They add RPC-only room/message/memory, read/mention/preference and attachment metadata tables and `boss_rooms_v1(operation, payload)`; clients use the existing authenticated `SupabaseDataProvider`, never a service-role key.

Before deployment, apply in an isolated staging Supabase project based on the actual BOSS schema, test with two real accounts and one nonmember, verify session revocation and concurrent writes, then review deployment/retention requirements with its owner. The local PostgreSQL tests use a small organization/auth fixture and are not a staging deployment.

After backend installation, install the JAR in an isolated BOSS trial profile, open the Rooms Favorites tile (custom trial host), or find Rooms through BOSS Search, choose your organization, and open My assistant. Configure AI Gateway in BOSS. Write a question; the native UI uses the real gateway and saves its response. For team chat, create a room or direct conversation with active organization members.

## Privacy and limitations

Personal history/background is sent to the selected AI provider when asking My assistant. It is not end-to-end encrypted. Personal assistant context is separated by organization; it is not a global cross-company profile. Private conversations are membership-restricted server-side, not merely hidden in navigation.

Room-agent context stays in that thread. Other conversation contents, browsers, project files and credentials are not automatically read. Connected tools may access additional data only after explicit selection and per-call review. Room posts use the authenticated human as actor and label assistant authorship; that label is attribution, not cryptographic proof of which model produced it.

The production Compose screens have passed offscreen UI tests at 1000×700, 420×500 and 360×260, including sign-out clearing; screenshots were inspected. The local trial has also passed live configured-provider checks for chat, approved room reading and message sending, plus declined-send and restored-reply checks. UI automation covers action approval and retry recovery. Two-account production staging tests remain required. No messages have been sent to coworkers, no backend migration deployed, and no GitHub publication made.

Current limitations: in-app notifications poll the selected organization while Rooms is open; newest 100-message pages have explicit earlier-page loading; unconfirmed send IDs live only for the open tab. Text drafts persist when host plugin storage is available; attachment selections do not. There is no durable offline send queue, closed-app push, calls or background scheduling. Attachment UI and the fictional local adapter are implemented; production requires the authenticated storage broker described in [docs/ATTACHMENTS.md](docs/ATTACHMENTS.md). A single tab runs one assistant at a time. Lost send acknowledgements can be retried with the same request ID while the tab remains open. Do not claim guaranteed delivery across app restarts.


The custom trial host opens Rooms from the Favorites tile beside Home, with a compact icon when the sidebar is collapsed and BOSS Search as an alternative entry. Repeated clicks focus the existing Rooms tab. Organization switching is at the top of Rooms; the last organization/conversation is remembered per user. The local test includes Acorn Studio and Northstar Lab.

## Review and CI

See [the review guide](docs/REVIEW.md) for contribution boundaries, reproducible checks and staging gates. GitHub Actions builds the production/demo artifacts and runs native UI, controller, agent, SQL and HTTP tests on Linux and macOS. Production artifacts are checked to exclude local identities, connection files and SDK classes.
