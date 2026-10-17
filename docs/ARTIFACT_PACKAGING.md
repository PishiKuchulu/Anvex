# Artifact / Packaging Subsystem

Anvex 3.3.0 adds a controlled generated-file pipeline for the durable Agent.

## Supported formats
- ZIP — source/workspace packages
- PDF — generated documents using Android PdfDocument
- TXT / Markdown / JSON — UTF-8 generated documents

## Security boundaries
- Output is stored under app-private `filesDir/build-artifacts/<projectId>/`.
- ZIP paths are normalized and traversal/absolute paths are rejected.
- Packaging is bounded to 20,000 files / 200 MB total / 20 MB per entry.
- `.git`, `.gradle`, module/root build outputs, `node_modules`, IDE metadata, PFP directories, and common secret files are excluded. Source directories under `src/` are preserved even when a package segment is named `build`.
- Likely credential/token content in text source files stops packaging instead of being copied into the ZIP.
- The Agent cannot use the generic shell as a substitute for the packaging API.
- High-risk/oversized generated-file requests remain behind the existing approval system.

## Agent protocol
```xml
<boltAction type="artifact" format="zip" outputName="source.zip">...</boltAction>
<boltAction type="artifact" format="pdf" outputName="report.pdf" title="Build Report">Report body</boltAction>
<boltAction type="artifact" format="md" outputName="README.md"># Notes</boltAction>
```
For ZIP, `paths="path1|path2"` selects files; omitting it packages the safe project file set.

## Important
A generated file is only considered created after the packaging runtime returns a successful result. Build success, runtime success, and sharing are separate states.

## Agent delivery flow
1. The model emits a bounded `boltAction type="artifact"` request.
2. The durable Agent validates the action through the existing safety/approval boundary.
3. `ArtifactManager` creates the file under app-private `build-artifacts/<projectId>/`.
4. The Agent Workspace lists the generated artifact.
5. Sharing is a separate explicit user action through Android `FileProvider`; the model cannot silently export a file.

ZIP packaging is deterministic (sorted entries and normalized timestamps) and rejects duplicate or traversal paths.
