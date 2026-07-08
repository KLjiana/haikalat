# Changelog

## Unreleased

### Stabilization

- Documented the project goal, current capabilities, target direction, and non-goals in `docs/project-goals.md`.
- Audited abstraction density and documented the current keep/remove decisions in `docs/abstraction-audit.md`.
- Removed over-broad resource factory and future-only descriptor abstractions that had no production behavior.
- Annotated all `cmd.custom()` call sites with replacement direction and whether a formal command API is needed.
- Defined the minimal `RenderDevice` contract and kept OpenGL `StateCache` access on `GlRenderDevice`.
- Documented `RenderGraph` pass/resource/profile boundaries in `docs/render-boundaries.md`.
- Added standard Java `.properties` scene asset config parsing and switched `LearnOpenGlDemo` to that format.
- Kept `LearnOpenGlDemo` as the combined scene, shadow, and postprocess proof demo.
- Added an opt-in hidden-window GL smoke test with pixel readback verification.
- Removed deprecated or unused thin abstractions: `UploadQueue`, `AntiAliasPipeline`, `DefaultAssetManagers`, and `DeferredPipelinePlan`.

### Completed Roadmap Work

The completed items previously listed in `docs/future-plans.md` have been migrated here so the roadmap can focus on next work instead of historical checklists.

#### v0.1-stable-demo

- Trimmed LWJGL dependencies and selected natives by operating system.
- Split demo sources into `src/demo/java` and kept demo entry points runnable from the IDE.
- Documented runtime requirements in the README.
- Stabilized demo resize, zero-height projection handling, empty-scene clear/present, async initialization, and resource cleanup.

#### v0.2-tested-core

- Added tests for `VertexPacking`, `RenderGraph`, `UploadSystem`, and `TripleBuffer`.
- Added CI coverage for compilation and unit tests that do not require an OpenGL context.
- Tightened RenderGraph pass dependencies and command capture rules.
- Added a formal framebuffer blit command to reduce custom command usage.

#### v0.3-materials

- Split material template and material instance state.
- Added typed `UniformValue` handling, UBO support, sampler configuration, and clearer resource ownership rules.

#### v0.4-postprocess-graph

- Integrated FXAA and TAA as RenderGraph passes.
- Added postprocess mode selection, texture naming conventions, TAA jitter/history validation, and resize history reset.

#### v0.5-instancing-framebuffer

- Extended instanced batching for grouped mesh submission, synchronization, persistent mapping evaluation, statistics, and replaceable instance layouts.
- Added MRT, depth texture support, framebuffer descriptors, render target lifecycle management, and resize rebuild flow.

#### v0.6-engine-surface

- Introduced a minimal backend boundary with `RenderDevice` and resource barrier concepts.
- Added scene data structures, forward pipeline, basic lighting, deferred pipeline evaluation, and directional shadow map support.
- Added model loading, texture asset cache, shader hot reload, resource locator support, and configurable demo scene data.
- Added pass-level GPU timing, CPU/GPU frame profiles, debug overlay data, GL object labels, and render error categories.
