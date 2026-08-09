# Text subsystem

`com.kaleblangley.haikalat.subsystems.text` is the shared, renderer-neutral text layer.
It owns the reusable CPU-side pipeline:

- bundled and runtime font registration through `BundledFonts` and `FontManager`;
- FreeType rasterization and HarfBuzz shaping;
- Unicode boundaries, fallback, layout and glyph atlas preparation;
- CommonMark parsing into the renderer-neutral `MarkdownDocument` model.

The subsystem does not depend on UI, backend rendering, or OpenGL. Consumers decide how
to turn `TextLayout` and `GlyphUploadRequest` values into GPU resources and draw commands.
The UI-specific `UiTextEngine` is therefore an adapter: it translates `Label` and
`TextField` state into shared text calls and translates prepared glyphs into UI display
list commands. `TextEffect` remains in UI because it describes presentation rather than
text parsing or layout.

`BundledFonts` is the single catalog for classpath font paths, family names and fallback
registration order. `MarkdownParser` is the single CommonMark adapter; demos and other
subsystems should consume its document model instead of traversing CommonMark nodes or
reimplementing inline-run merging.

The Markdown contract is a controlled display subset rather than complete rich text. It covers
H1-H3, paragraphs, quotes, flat list text, fenced/indented code, strong/emphasis/inline code and
line breaks. H4-H6 map to H3; links keep only display text; images, URL behavior, list hierarchy,
ordered numbers, fence languages, HTML, tables and thematic breaks are not promised. The parser
rejects sources above its configured capacity before invoking CommonMark.

`TextSystem.layout(...)` accepts an optional family name and builds a family-specific
fallback chain, so UI styles such as the bundled JetBrains Mono code face are honored
without each consumer maintaining its own font selection logic.

The architecture tests enforce that the text package cannot import UI/backend types or
issue OpenGL calls, that `MarkdownDocument` does not expose CommonMark types, and that UI creates
the shared service only through `UiTextEngine`. Font registration uses rollback on face failure;
`TextSystem.close()` is idempotent and remains retryable while an atlas generation lease is live.
Public types are classified in
`docs/architecture/public-api/text.allowlist`.
