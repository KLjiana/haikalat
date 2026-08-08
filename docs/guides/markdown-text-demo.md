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

The shared text subsystem wraps `commonmark-java` behind `MarkdownParser` and emits the
renderer-neutral `MarkdownDocument` model. The demo maps document blocks to ordinary retained
UI nodes, while inline text, strong emphasis, emphasis, and code runs become adjacent `Label`
segments. Strong spans use visible emphasis and inline code uses a bordered monospace treatment
with the bundled JetBrains Mono face. The production UI subsystem still does not expose a
Markdown or rich-text widget contract.

The lower gallery exercises the existing `TextEffect` values: gradient, outline, drop
shadow, outer glow, and inner glow. Its header slider adjusts the sample font size from 18
to 40 pixels, with a 28-pixel default. The `CYCLE FONT` button switches the bundled primary
font family while keeping the retained tree unchanged. The title gradient is updated once
per frame to make the effect path observable during a normal run.

The demo's monospace code labels now use the same family-aware layout path as every other
UI label; changing the selected primary family only changes labels that inherit that family.
