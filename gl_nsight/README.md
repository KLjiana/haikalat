# NVIDIA Nsight Graphics launchers

Run one BAT file, wait for its OpenGL window to appear, then use **Nsight Graphics > Attach to Process** and select the corresponding `java.exe` process. The scripts use the Gradle Java 21 toolchain and compile the demo before launch.

- `01_learnopengl_demo.bat` — combined lighting, shadow, instancing, and postprocess demo.
- `02_minimal_demo.bat` — minimal rendering and command-flow demo.
- `03_async_demo.bat` — asynchronous update/upload/render-thread demo.

The existing `learnopengl.ngfx-proj` file is preserved. Close a demo with Escape or its window close button. A console stays open automatically when launch fails so the error can be inspected.
