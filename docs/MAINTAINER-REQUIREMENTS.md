# Maintainer requirements for a Rooms release

The implementation and test fixture are submitted for review. Neither the public contribution repository nor its CI artifacts constitute an official BOSS store release. Installing the plugin does not create its backend.

## Decisions or access requested from maintainers

| Requirement | What is already supplied | Requested decision or action |
| --- | --- | --- |
| Backend ownership and migration placement | `backend/001_rooms.sql`: three tables, authenticated RPC, indexes and restricted table access; `backend/DEPLOYMENT.md` | Name the backend owner, review/adapt the contract, and choose its official migration location. Stage it through the normal process before authorizing production rollout. |
| Actual staging environment and identities | Local SQL and two-client HTTP tests with fictional users | Provide an approved staging environment and test-account access through the normal secure process, or arrange owner-run tests. Validate real JWT/PostgREST behavior, organization membership, private assistant/DM isolation and concurrent revocation. No service-role key or production credential needs to be posted to the PR. |
| Plugin repository and Toolbox ownership | Production JAR, stable plugin ID, optional AI Gateway dependency, build workflow and artifact checks | Confirm whether the repository remains author-owned or moves upstream, the owning organization/publisher, store visibility and authorized publication path. Publish through the normal version/checksum/signature process; development CI artifacts are not a store listing. |
| Supported host release and navigation | Companion BOSS PR #972; SDK/minimum-version declarations | Approve the fixed Rooms entry and coordinate a supported BOSS release containing it. Confirm the minimum supported host/SDK versions before store publication. Older hosts may use their standard plugin entry instead of the new tile. |
| Privacy and administration policy | Per-user/per-organization assistant memory, membership-gated conversations, explicit approval before cross-conversation reads or sends | Confirm permitted AI providers/data sharing for organizations, administrator access policy, retention/deletion/export/backup requirements and the operational owner. The current prototype is not end-to-end encrypted and must not be advertised as such. |
| Release operations | Bounded agent runs, cancellable work, visible errors and documented local limitations | Confirm existing platform quotas/rate limits and operational monitoring, deployment sequence and rollback ownership. Database rollout must precede enabling shared Rooms for users. Disable/rollback must not silently delete conversation data. |

## Follow-up validation of the requests

Checked against the BOSS organization migrations, `SupabaseDataProviderImpl`, store ownership/signature code, the Rooms SQL proposal and plugin manifest. The backend request is an extension of the existing BOSS Supabase service, not a request for a new database product, authentication system, realtime service or credential store. The existing `BossConsole/supabase/migrations` directory is the proposed migration destination, subject to its owner's review. Store signing should use the existing publishing service; we are not requesting signing keys.

Two policy choices require explicit answers before finalizing the migration:

- **Offboarding and deletion:** the proposed foreign keys currently have no cascade/cleanup behavior for stored conversations, message authors and assistant memory. They can prevent deleting a referenced user or organization. Confirm ownership transfer, archival/purge/export and backup treatment before adding those references in production. Define whether rejoining an organization restores previous room access; the current implementation checks active membership but retains room membership IDs. We will implement the agreed behavior and regression tests.
- **Server-side enablement and roles:** the RPC currently admits any active organization member, with additional room membership/ownership checks. Hiding or disabling the Toolbox plugin does not revoke authenticated RPC access. Confirm whether Rooms needs an organization enablement flag or distinct create/manage permissions, and whether the member directory may show every active member with email as a fallback name. Any agreed restriction must be enforced on the server, not just in the UI.

An organization membership in upstream BOSS has status `active`, `pending` or `invited`; removal deletes the row through `remove_organisation_member`. The local fixture now follows those statuses and tests row-deletion revocation as well as pending/invited denial. It still is not a substitute for real schema/JWT/concurrency validation.

No additional tables for unread state, attachments or notification delivery are requested for the current polling-based scope. Those are future features, not hidden installation prerequisites.

## Work owned by this contribution

These are implementation/verification tasks, not requests for maintainers to write our code:

- Keep both PRs current, fix our CI failures, and maintain reproducible tests and documentation.
- Exercise production installation, disable/re-enable, update, uninstall and tab cleanup on supported hosts. A registration test is not a complete Toolbox lifecycle test.
- Verify missing/unconfigured AI Gateway recovery; human messaging must remain independent of the optional assistant dependency.
- Verify selected external tools through the host's permission/approval system in an authorized test environment. The local demo intentionally disables external tools.
- Once staging exists, run or support the real-account and concurrent database checks, address findings, and record evidence before asking for production approval.
- Publish only the production JAR through the agreed publisher workflow. Never publish the local database, connection tokens or fictional-auth adapter as the production plugin.

## Submitted evidence and limits

35 plugin tests and the SQL/HTTP suites passed locally and in Linux/macOS CI. Live configured-provider checks passed against fictional local rooms for approved reads, approved sends, declined sends and reply persistence. The host navigation checks passed locally; subsequent upstream CI exposed a test-fixture naming collision with the window-branding convention, which was corrected in host commit `6d761ac09`; affected tests and quality checks passed locally.

Still outstanding: official store registration/signing, actual-backend staging evidence, lifecycle/external-tool validation and the maintainer decisions above. These remain draft release gates; no production migration or store deployment has been performed.

Related proposals: [plugin PR #1](https://github.com/manishakuhar/boss-plugin-rooms/pull/1) and [BOSS PR #972](https://github.com/risa-labs-inc/BossConsole/pull/972).

## Messaging extension

Review additive `backend/002_delivery.sql` with the Rooms schema. It adds reads, notification preferences and mention recipients. The current SDK permits in-app alerts only. Closed-app/OS delivery requires an approved server event transport and host notification integration, including click routing, sound preferences and cross-device deduplication. See [messaging scope](MESSAGING-DELIVERY.md). These requests are proposals, not deployed infrastructure.

## Attachment extension

Review `backend/003_attachments.sql` and the [attachment broker contract and deployment requirements](ATTACHMENTS.md). A private bucket alone is insufficient: we also need an authenticated broker/host adapter, server-only upload finalization after validation/scanning, quotas, cleanup and download revocation policy. The local test adapter is implemented; no production adapter or storage has been provisioned. Attachment bytes are never automatically included in AI context.
