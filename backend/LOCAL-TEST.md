# Local Rooms database trial — 2026-09-18

A durable PGlite PostgreSQL test database now runs the same ordered backend/001_rooms.sql, 002_delivery.sql and 003_attachments.sql migrations and boss_rooms_v1 RPC used by the production plugin. Fictional Manisha, Maya and Leo accounts belong to Acorn Studio (local test). No production BOSS database or real authentication tokens are used.

Run `python3 scripts/launch-local.py` from boss-plugin-rooms after building `buildLocalDemoPlugin localDemoLaunchConfig`. Two native windows open with separate test identities. Select Acorn Studio in each, then open the direct conversation between Manisha and Maya (seeded by the native adapter smoke test), or create a room and invite the other test person. Send in one window and check the other; refresh/polling brings messages in. Close and reopen to check persistence.

Data: repo .local-rooms/postgres. Connection tokens: .local-rooms/connection.json, private permissions and gitignored. Backend stays running after windows close. A server.lock prevents two processes from opening the same database. After an abnormal shutdown, confirm no server is running before removing that lock directory. Never distribute the connection file or local data with a plugin.

Standalone AI is unavailable: actual AI needs the separate Rooms Local Test JAR loaded inside BOSS, which supplies its configured AI gateway. That adapter disables connected external MCP tools; model calls inside BOSS use the real configured provider. BOSS-host loading, real-model replies, approved local-room actions, declined sends, and reply persistence were verified in the custom trial.

Checks passed: SQL authorization/membership/revision/idempotency suite; HTTP concurrent identity isolation and two-user messaging; restart persistence; private assistant and memory isolation; invalid-token/browser-origin rejection; duplicate server rejection; native Kotlin adapter through HTTP to real SQL with two users and private-room denial. The native suite contains 37 passing tests. Production JAR checked to exclude test classes. Both JARs build successfully.

Handoff: maintainers install the migration in a staging Supabase environment matching their auth and organization schema, validate policies and actual user membership, then use the production plugin's existing authenticated Supabase provider. Do not deploy the local server or fictional auth fixture. This is schema/adapter compatibility, not a promise that arbitrary databases can be swapped by changing a URL. No production deployment is included. See docs/REVIEW.md for the remaining staging gates.

For a BOSS-host demo, set `ROOMS_DEMO_CONNECTION` to the absolute path of the local connection file before launching BOSS, then load the local-test JAR. The standalone launcher passes the same path as a JVM property. Optional `ROOMS_DEMO_LOG_DIR` overrides the default `.local-rooms/logs` directory. Do not upload either directory.

The local server now stores attachment bytes in `.local-rooms/attachments` with private filesystem permissions. Read positions and notification preferences survive restart. Additive migrations are hash tracked; existing messages are preserved, and changed historical migrations are rejected. Native attachment upload/send/download was verified through the BOSS trial controller.
