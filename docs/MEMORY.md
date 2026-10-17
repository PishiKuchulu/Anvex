# Long-term memory (v4.2)

## What it does
The assistant keeps a small list of durable facts about the user (preferences, stack, language, workflows) and injects them into the system prompt of every chat, in every project.

## Data flow
1. `SessionExtras.memoryBlock()` renders up to 3.5 k chars (pinned first, then newest) into `<user_memory>`.
2. The model may append hidden tags at the end of a reply: `<boltMemory op="add|update|forget" …>`.
3. `ChatSession.finalize` parses them (`MemoryTags`), filters them (`MemorySafety`) and applies them (`MemoryEngine`) through `MemoryRepository`. Tags are stripped from the visible/persisted message and never flash during streaming.
4. The Memory tab lists everything; the user can add, edit, pin, delete, clear or copy as JSON. Settings has the master switch.

## Safety rules (enforced in code, tested in `MemoryTest`)
- Secret-looking text (API keys, tokens, passwords, card-like numbers) is rejected.
- Max 300 chars per item, 200 items (oldest unpinned evicted).
- In a turn where web tools ran, only `forget` directives are honoured (prompt-injection guard).
- The prompt tells the model not to save sensitive personal data unless explicitly asked.
- Storage: one app-private JSON file (`files/memory/memories.json`); Room schema stays at v12. Memory leaves the device only inside the prompt sent to the user's own provider.

## Layering (PFP)
`core:model` (MemoryItem) → `core:engine` (pure-JVM logic + tests) → `core:android` (repository, session wiring) → `app` (screen). The engine has no Android or vendor dependency.
