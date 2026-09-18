# Attachments

This draft builds on messaging PR #2. It provides native file selection and file drop, up to five attachments per message, upload retry/removal, attachment-only messages, explicit image previews and downloads. Supported files: PNG, JPEG, PDF and plain text, up to 10 MiB each. Image previews are limited to 16 million pixels. Files are not sent to the assistant or connected tools.

Apply additive migration `003_attachments.sql` after 002 through the database owner. Metadata and room access stay in Postgres; bytes stay outside the database. New uploads are private to the uploading member until linked atomically to a sent message. Uploads cannot overwrite published files. Deleting a message immediately denies subsequent downloads. Downloaded copies cannot be recalled.

## Production dependency

The current BOSS SDK has no authenticated file storage API. `RoomsAttachmentProvider` is the narrow broker contract for upload and download. The production plugin does not invent credentials or use the local server. It explains that storage must be configured when no adapter is registered.

The maintainer needs to provide or approve:

- A private object bucket and authenticated host/broker adapter implementing the contract. It must verify organization membership, room membership, draft ownership and message deletion before every operation, including after a long upload.
- Upload completion under server authority only, after actual size/type validation and malware scanning. Authenticated clients must never update `status=ready` directly. Reject executable/active formats and do not trust filename or Content-Type alone.
- Private downloads via the broker, or short-lived signed links with an explicitly approved revocation window. No public bucket. Use attachment disposition and nosniff headers.
- Per-user/org storage quotas, request rate limits, retention/export/deletion policy, monitoring and scheduled orphan cleanup. Pending uploads expire after 24 hours in the reference local adapter.
- Safe save-file overwrite confirmation in the host picker, and broker cancellation support. In-flight upload progress is currently an indeterminate status until completion; no fabricated percentage is shown.

These are required integration items, not completed production deployment. The prototype is functional against the included fictional-account backend. The Node local server must not be deployed as a production authentication server.

## Reference adapter and validation

The local test adapter authenticates each transfer with its existing loopback session, serializes final access checks with metadata publication, writes private UUID-named files, rejects size/signature mismatches, and removes cancelled/deleted/abandoned bytes on a periodic cleanup. Its small signature checks are not a production malware scanner.

SQL and HTTP tests cover direct-table/internal-RPC denial, private drafts, oversized uploads, nonmembers, publication, immutable posted bytes and deletion access. The opt-in `ROOMS_ATTACHMENT_SMOKE=1` fixture exercises the native controller's upload, attachment-only send, refresh and download using a temporary text file and fictional room. It does not invoke AI or external tools.

Native smoke passed in the BOSS trial app on 2026-09-19. Kotlin/Compose regression tests include narrow and short layouts. Cancellation removes a draft from the composer; interrupted or inaccessible drafts are subsequently cleaned by retention. Staged upload selections are not restored after app restart; posted messages/files persist.
