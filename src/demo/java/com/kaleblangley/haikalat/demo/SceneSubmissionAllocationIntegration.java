package com.kaleblangley.haikalat.demo;

/** v0.17 两个 10k allocation hard gate 的串行桌面入口。 */
public final class SceneSubmissionAllocationIntegration {
    private SceneSubmissionAllocationIntegration() {
    }

    public static void main(String[] arguments) {
        run("all-visible");
        run("mostly-hidden");
    }

    private static void run(String layout) {
        GltfSceneScalabilityDemo.main(new String[]{
                "--renderers=10000", "--layout=" + layout, "--visibility=enabled",
                "--static-cache=enabled", "--queue-cache=enabled",
                "--command-matrix-arena=enabled", "--frames=12", "--warmup=4",
                "--rounds=1", "--size=640x360", "--deterministic", "--verify"
        });
    }
}
