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

## Work owned by this contribution

These are implementation/verification tasks, not requests for maintainers to write our code:

- Keep both PRs current, fix our CI failures, and maintain reproducible tests and documentation.
- Exercise production installation, disable/re-enable, update, uninstall and tab cleanup on supported hosts. A registration test is not a complete Toolbox lifecycle test.
- Verify missing/unconfigured AI Gateway recovery; human messaging must remain independent of the optional assistant dependency.
- Verify selected external tools through the host's permission/approval system in an authorized test environment. The local demo intentionally disables external tools.
- Once staging exists, run or support the real-account and concurrent database checks, address findings, and record evidence before asking for production approval.
- Publish only the production JAR through the agreed publisher workflow. Never publish the local database, connection tokens or fictional-auth adapter as the production plugin.

## Submitted evidence and limits

35 plugin tests and the SQL/HTTP suites passed locally and in Linux/macOS CI. Live configured-provider checks passed against fictional local rooms for approved reads, approved sends, declined sends and reply persistence. The host navigation checks passed locally; subsequent upstream CI exposed a test-fixture naming collision with the window-branding convention, which is being corrected in the host PR.

Still outstanding: official store registration/signing, actual-backend staging evidence, lifecycle/external-tool validation and the maintainer decisions above. These remain draft release gates; no production migration or store deployment has been performed.

Related proposals: [plugin PR #1](https://github.com/manishakuhar/boss-plugin-rooms/pull/1) and [BOSS PR #972](https://github.com/risa-labs-inc/BossConsole/pull/972).
