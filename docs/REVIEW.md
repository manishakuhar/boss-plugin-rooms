# Review guide

BOSS Rooms adds shared rooms/direct messages and a private assistant inside the console. The plugin opens as a main tab. Its native UI uses BOSS theme colors, a persistent organization selector, message history, threads, search, pins, and an approval dialog for assistant actions.

## Contribution boundaries

- This repository owns the plugin, isolated demo, tests, and proposed database contract.
- The companion BOSS host PR adds an optional Rooms tile beside Home and preserves Search discovery. The tile is absent without the registered production plugin.
- `backend/001_rooms.sql` is a proposal, not an automatically deployed migration. Review its placement in the upstream migration repository before deployment.
- AI Gateway is optional for human messaging, required for assistant replies. Tool-capable gateways use their native loop; completion-only gateways use the bounded Rooms action protocol.

## Reproduce validation

```sh
python3 scripts/fetch-sdk.py
npm ci --prefix backend
npm test --prefix backend
node backend/local-server.test.mjs
./gradlew test buildPluginJar buildLocalDemoPlugin
python3 scripts/verify-artifact.py
```

On Linux, run the Gradle command under `xvfb-run -a`. CI runs both Linux and macOS. Tests use synthetic data and need neither provider credentials nor production database access. The HTTP tests create and remove their own temporary database; they do not reset an existing demo database.

Local validation before submission: 36 Kotlin tests, SQL permission tests, two-client HTTP/restart tests, and real configured-provider checks inside the custom BOSS trial. Live checks exercised approved reading, exact approved sending, declined sending, and reply persistence. Live actions used only fictional accounts and local rooms. Connected external plugins were not invoked.

## Review-sensitive behavior

- Authentication comes from BOSS; server operations validate organization and conversation membership. Personal assistant memory stays per user and organization.
- A shared chat does not receive private assistant history or other conversation names. Reading another conversation is available only from My assistant and requires approval.
- Sending elsewhere requires approval of the destination and exact text. The target membership is checked again after approval. Repeated action IDs cannot execute a changed request.
- Eight steps and a 120-second timeout bound the action loop. Token checks use provider-reported usage; time, step and response-size bounds remain when usage is absent.
- Stop cancels work, not actions that have already committed. Retry IDs are stable within a controller. Offline delivery across app restarts is not guaranteed.

## Before production approval

- Validate the SQL proposal in staging against the actual organization schema, real JWTs and PostgREST.
- Exercise concurrent membership revocation and writes using separate database connections.
- Agree retention, backup/export and administration policy with the backend owner.
- Verify production-plugin install, disable, re-enable and uninstall on supported BOSS versions.
- Verify selected external-tool invocation against BOSS permission governance.
- Obtain maintainer agreement on repository/store ownership and the fixed navigation entry.

These are draft review gates. No production deployment or store release is included in this contribution.

## Maintainer handoff

[Maintainer requirements](MAINTAINER-REQUIREMENTS.md) records requested platform decisions/access separately from verification work owned by this contribution.

Follow-up review: migrations 002 and 003 are also proposals. Messaging and attachment scope, native smoke evidence, and required production broker/event support are documented in MESSAGING-DELIVERY.md and ATTACHMENTS.md. Production storage and closed-app notifications remain maintainer integration gates.
