# MarkdownTextDemo

Run the standalone showcase with:

```powershell
.\gradlew.bat runMarkdownTextDemo
```

The deterministic integration path runs a hidden window, renders 30 fixed-step frames,
cycles through all three bundled primary fonts, and checks that the framebuffer contains
UI pixels:

```powershell
.\gradlew.bat runMarkdownTextIntegration
```

The shared text subsystem provides a **CommonMark parser-driven controlled display subset**.
`MarkdownParser` hides `commonmark-java` and emits the renderer-neutral `MarkdownDocument` model;
neither that model nor the UI exposes CommonMark AST types.

The supported display contract is:

- H1 through H3, paragraph and quote;
- ordered and unordered list text;
- fenced and indented code;
- strong, emphasis and inline code;
- soft and hard line breaks.

The deliberate limits are:

- H4 through H6 map to H3;
- links retain display text but not the URL, and images are not rendered;
- nested lists are flattened, ordered-list numbers and fence languages are not retained;
- HTML, tables, thematic breaks and other unsupported top-level nodes are ignored;
- one input is limited to 1,048,576 UTF-16 code units and larger input fails before parsing.

The demo maps supported blocks to ordinary retained UI nodes. It is not a complete rich-text or
Markdown widget contract, and unsupported nodes must not be presented as such.

The lower gallery exercises the existing `TextEffect` values: gradient, outline, drop
shadow, outer glow, and inner glow. Its header slider adjusts the sample font size from 18
to 40 pixels, with a 28-pixel default. The `CYCLE FONT` button switches the bundled primary
font family while keeping the retained tree unchanged. The title gradient is updated once
per frame to make the effect path observable during a normal run.

The demo's monospace code labels now use the same family-aware layout path as every other
UI label; changing the selected primary family only changes labels that inherit that family.

The v0.22.1 performance matrix is available through:

```powershell
.\gradlew.bat runTextBenchmarks
```

It reports Markdown parse p50/p95, 1,000/10,000 glyph cold and cache-hit layout, font-switch
remeasurement, atlas requests/bytes/pages, CPU/GPU p50/p95, allocation, batches/draws, state
breaks and GL messages for ordinary text and every existing effect at 1080p and 4K.
