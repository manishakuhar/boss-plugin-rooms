# Backend contract and review checklist

This migration is an undeployed proposal for the existing BOSS Supabase backend. The plugin cannot create or migrate server tables. Owner deployment is required before shared chats or server-backed private assistant context can function.

`boss_rooms_v1` uses `auth.uid()` rather than a caller-supplied actor. Every operation after organization discovery checks active organization membership; conversation operations additionally lock and check the room. Organization/public visibility allows discovery and joining, not reading without joining. Private assistant rooms have exactly one member and cannot be converted to shared rooms. A removed org member cannot use previously acquired room IDs.

Tables have RLS enabled and no grants to anon/authenticated. Authenticated clients have execute on the function only. PUBLIC/anon cannot execute it. Functions use a fixed search_path and qualified tables. No service role is shipped in the client.

Agent-attributed posts remain attributed to the authenticated human actor. Message request IDs are unique per author; replay returns the existing matching message and rejects changed content/destination. Updates use expected revisions. Parent IDs must identify a root in the same room. Deletes retain replies while replacing text and removing the pin.

Local test coverage: nonmember/anonymous denials, direct table denial, personal memory isolation, org/room revocation, private assistant immutability, duplicate delivery, revision conflicts, cross-room and nested-thread rejection, pin/delete, direct participant deduplication, pagination and join/leave.

Still required against a staging copy of BOSS: actual schema compatibility, real JWTs and PostgREST responses, concurrent revocation/writes with separate connections, platform admin policy, data retention/backups/export and release rollout. PGlite executes PostgreSQL locally but its fixture does not establish any of those deployment properties.

Apply once; it intentionally does not overwrite pre-existing objects. Roll back only under the backend owner's data-retention procedure; dropping these tables loses conversations and private saved context.
