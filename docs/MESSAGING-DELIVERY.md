# Unread tracking and notifications

Apply backend/002_delivery.sql after 001 through the database owner's migration process. It adds per-user, per-thread monotonic read positions, per-conversation notification preferences and explicit message mention recipients. Direct table access and the internal base RPC remain denied to clients. Local test databases apply this additive migration without resetting messages.

Unread counts include replies and assistant responses, exclude deleted messages and the user's own human messages. Viewing a conversation only advances that conversation's visible thread when the window is focused and the list is at the latest message. Other threads stay unread. Read positions survive restart.

Choose people with the composer @ button. Mentions are explicit user IDs, validated against current room and organization membership. Editing text does not generate new mention notifications. DMs, assistant responses and mentions notify by default; conversation options allow all messages or mute. Muting suppresses alerts, not unread counts.

The SDK supports clickable in-app toasts, not background push. Rooms polls the selected organization every five seconds while its controller is alive. Alerts omit message content. Clicking rechecks membership and opens the message/thread. Account changes dismiss this controller's alerts. This is not closed-app or cross-organization delivery; unread counts remain durable independently.

Production follow-up required from maintainers: decide server event transport, host OS notification/click integration, cross-device delivery deduplication, user preference policy, rate limits and retention. Sound is deferred until a host notification preference API exists. No provider key or service-role key belongs in the plugin.

Validation: SQL/HTTP tests cover monotonic and thread-scoped watermarks, mute, mention retry identity, nonmember denial and persistence. Controller tests enforce visible-only read advancement. Existing UI checks cover narrow/short panes, scrolling and recovery.
