package com.kaleblangley.haikalat.demo.gltf;

import com.kaleblangley.haikalat.core.assets.AssetRef;
import com.kaleblangley.haikalat.core.assets.ResourceLocator;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLoader;
import com.kaleblangley.haikalat.core.assets.gltf.GltfAssetLimits;
import com.kaleblangley.haikalat.core.assets.gltf.GltfLoadOptions;
import com.kaleblangley.haikalat.core.assets.gltf.GltfSceneStatistics;
import com.kaleblangley.haikalat.core.assets.gltf.LoadedGltfScene;
import com.kaleblangley.haikalat.core.assets.gltf.SceneSelection;
import com.kaleblangley.haikalat.subsystems.animation.AnimationPlayer;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfRuntimeLibrary;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneAsset;
import com.kaleblangley.haikalat.subsystems.render3d.gltf.GltfSceneInstance;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** GltfDemo 独占的场景资产及其 GPU 生命周期。 */
final class GltfDemoAssets implements AutoCloseable {
    private static final float CROUCH_WALK_TIME_SCALE = 0.35f;
    private static final float PLAYER_SLIE_TIME_SCALE = 0.75f;
    private static final float PLAYER_WILD_TIME_SCALE = 0.65f;
    private static final float PLAYER_WILD_TRANSITION_SECONDS = 0.22f;

    private final GltfRuntimeLibrary library;
    private final List<GltfSceneAsset> assets;
    private final List<GltfSceneInstance> animatedInstances;
    private final List<SceneObject> objects;
    private final List<String> inspectionLines;
    private final AnimationProbe animationProbe;
    private final float animationTimeScale;
    private final AnimationPlaylist animationPlaylist;
    private boolean closed;

    private GltfDemoAssets(GltfRuntimeLibrary library, List<GltfSceneAsset> assets,
                           List<GltfSceneInstance> animatedInstances,
                           List<SceneObject> objects, List<String> inspectionLines,
                           AnimationProbe animationProbe, float animationTimeScale,
                           AnimationPlaylist animationPlaylist) {
        this.library = library;
        this.assets = List.copyOf(assets);
        this.animatedInstances = List.copyOf(animatedInstances);
        this.objects = List.copyOf(objects);
        this.inspectionLines = List.copyOf(inspectionLines);
        this.animationProbe = animationProbe;
        this.animationTimeScale = animationTimeScale;
        this.animationPlaylist = animationPlaylist;
    }

    static GltfDemoAssets load(GltfDemo.Asset assetMode) {
        return load(assetMode, false);
    }

    static GltfDemoAssets loadShadowBudget() {
        return load(GltfDemo.Asset.DEFAULT, true);
    }

    private static GltfDemoAssets load(GltfDemo.Asset assetMode, boolean includeMorphCaster) {
        ResourceLocator resources = ResourceLocator.classpath(GltfDemo.class);
        GltfAssetLoader loader = new GltfAssetLoader(resources);
        GltfRuntimeLibrary library = GltfRuntimeLibrary.create();
        List<GltfSceneAsset> assets = new ArrayList<>();
        List<GltfSceneInstance> animatedInstances = new ArrayList<>();
        List<SceneObject> objects = new ArrayList<>();
        List<String> lines = new ArrayList<>();
        try {
            AnimationProbe probe;
            float timeScale;
            AnimationPlaylist playlist = null;
            if (assetMode == GltfDemo.Asset.CROUCH_WALK) {
                LoadedGltfScene crouchWalk = loader.loadWithSidecar(
                        AssetRef.of("/scenes/gltf/crouch_walk.glb"),
                        new GltfLoadOptions(new SceneSelection.Default(), false,
                                GltfAssetLimits.defaults()));
                GltfSceneAsset crouchWalkGpu = GltfSceneAsset.upload(crouchWalk, library);
                assets.add(crouchWalkGpu);
                GltfSceneInstance crouchWalkInstance = crouchWalkGpu.instantiateAnimated(
                        new Matrix4f().scale(0.55f), true);
                crouchWalkInstance.playCombined(
                        animationIndices(crouchWalkInstance.animationCount()),
                        AnimationPlayer.LoopMode.LOOP);
                animatedInstances.add(crouchWalkInstance);
                objects.addAll(crouchWalkInstance.objects());
                appendInspection(lines, "crouch_walk.glb", crouchWalk, crouchWalkGpu, false);
                lines.add("  material fallback | optional specular/ior extensions ignored");
                lines.add("  animation playback | 0.35x");
                probe = new AnimationProbe(crouchWalkInstance, 1);
                timeScale = CROUCH_WALK_TIME_SCALE;
            } else if (assetMode == GltfDemo.Asset.ZOMBIE) {
                LoadedGltfScene zombie = loader.loadWithSidecar(
                        AssetRef.of("/scenes/gltf/zombie.gltf"));
                GltfSceneAsset zombieGpu = GltfSceneAsset.upload(zombie, library);
                assets.add(zombieGpu);
                GltfSceneInstance zombieInstance = zombieGpu.instantiateAnimated(
                        new Matrix4f().translation(0.0f, -1.3f, 0.0f).scale(2.4f), false);
                int runAnimation = zombieInstance.animationNames().indexOf("run");
                if (runAnimation < 0) {
                    throw new IllegalStateException("zombie.gltf has no run animation");
                }
                zombieInstance.play(runAnimation, AnimationPlayer.LoopMode.LOOP);
                animatedInstances.add(zombieInstance);
                objects.addAll(zombieInstance.objects());
                appendInspection(lines, "zombie.gltf", zombie, zombieGpu, true);
                // GeckoLib/Blockbench exports rigid mesh nodes below animated pivot nodes.
                probe = new AnimationProbe(zombieInstance, 9);
                timeScale = 1.0f;
            } else if (assetMode == GltfDemo.Asset.PLAYER_SLIE) {
                LoadedGltfScene playerSlie = loader.loadWithSidecar(
                        AssetRef.of("/scenes/gltf/player_slie.gltf"));
                GltfSceneAsset playerSlieGpu = GltfSceneAsset.upload(playerSlie, library);
                assets.add(playerSlieGpu);
                GltfSceneInstance playerSlieInstance = playerSlieGpu.instantiateAnimated(
                        new Matrix4f().translation(0.0f, -1.0f, 0.0f).scale(2.0f), false);
                // The exporter stores the visible lower-body movement in animation2;
                // animation is a static pose clip with the same leg channels.
                playerSlieInstance.play(1, AnimationPlayer.LoopMode.LOOP);
                animatedInstances.add(playerSlieInstance);
                objects.addAll(playerSlieInstance.objects());
                appendInspection(lines, "player_slie.gltf", playerSlie, playerSlieGpu, true);
                lines.add("  animation playback | animation2 | 0.75x (animation is the static pose)");
                probe = new AnimationProbe(playerSlieInstance, 8);
                timeScale = PLAYER_SLIE_TIME_SCALE;
            } else if (assetMode == GltfDemo.Asset.PLAYER_WILD) {
                LoadedGltfScene playerWild = loader.loadAnimationLibrary(
                        AssetRef.of("/scenes/gltf/player_wild/animation-library.json"));
                GltfSceneAsset playerWildGpu = GltfSceneAsset.upload(playerWild, library);
                assets.add(playerWildGpu);
                GltfSceneInstance playerWildInstance = playerWildGpu.instantiateAnimated(
                        new Matrix4f().translation(0.0f, -1.0f, 0.0f).scale(2.0f), false);
                playlist = AnimationPlaylist.create(playerWildInstance, List.of(
                        new PlaylistClip("stand", AnimationPlayer.LoopMode.LOOP, 2.5f),
                        new PlaylistClip("move", AnimationPlayer.LoopMode.LOOP, 2.5f),
                        new PlaylistClip("run", AnimationPlayer.LoopMode.LOOP, 2.5f),
                        new PlaylistClip("idle_sword", AnimationPlayer.LoopMode.LOOP, 2.0f),
                        new PlaylistClip("attack_light", AnimationPlayer.LoopMode.ONCE, 1.5f),
                        new PlaylistClip("start", AnimationPlayer.LoopMode.ONCE, 1.0f),
                        new PlaylistClip("idle_dash", AnimationPlayer.LoopMode.ONCE, 1.0f),
                        new PlaylistClip("end", AnimationPlayer.LoopMode.ONCE, 1.0f)));
                animatedInstances.add(playerWildInstance);
                objects.addAll(playerWildInstance.objects());
                appendInspection(lines, "player_wild animation library",
                        playerWild, playerWildGpu, true);
                lines.add("  compact external clips | stand, move, run, idle_sword, "
                        + "attack_light, start, idle_dash, end | 0.65x playlist");
                lines.add("  generated transitions | 0.22s pose cross-fade");
                lines.add("  texture | steve.png | nearest sampling");
                probe = new AnimationProbe(playerWildInstance, 2);
                timeScale = PLAYER_WILD_TIME_SCALE;
            } else {
                if (includeMorphCaster) {
                    LoadedGltfScene shadowRoom = loader.load(
                            AssetRef.of("/scenes/gltf/shadow-room.gltf"));
                    GltfSceneAsset shadowRoomGpu = GltfSceneAsset.upload(shadowRoom, library);
                    assets.add(shadowRoomGpu);
                    objects.addAll(shadowRoomGpu.instantiate(new Matrix4f(), false));
                    appendInspection(lines, "shadow-room.gltf", shadowRoom, shadowRoomGpu, true);
                }
                LoadedGltfScene showcase = loader.load(AssetRef.of("/scenes/gltf/showcase.gltf"));
                GltfSceneAsset showcaseGpu = GltfSceneAsset.upload(showcase, library);
                assets.add(showcaseGpu);
                Matrix4f showcaseRoot = new Matrix4f().translation(-2.8f, -0.8f, 0.0f)
                        .scale(1.5f).translate(-0.5f, -0.5f, 0.0f);
                objects.addAll(showcaseGpu.instantiate(showcaseRoot, false));
                appendInspection(lines, "showcase.gltf", showcase, showcaseGpu, true);

                LoadedGltfScene radio = loader.load(AssetRef.of("/scenes/gltf/radio.gltf"));
                GltfSceneAsset radioGpu = GltfSceneAsset.upload(radio, library);
                assets.add(radioGpu);
                Matrix4f radioRoot = new Matrix4f().translation(1.5f, -0.8f, 0.0f)
                        .scale(1.8f).translate(-0.81f, -0.42f, -0.15f);
                objects.addAll(radioGpu.instantiate(radioRoot, false));
                appendInspection(lines, "radio.gltf", radio, radioGpu, true);

                LoadedGltfScene creeper = loader.load(AssetRef.of("/scenes/gltf/creeper.gltf"));
                GltfSceneAsset creeperGpu = GltfSceneAsset.upload(creeper, library);
                assets.add(creeperGpu);
                Matrix4f creeperRoot = new Matrix4f().translation(1.5f, -0.8f, 0.0f)
                        .scale(1.8f).translate(-0.81f, -0.42f, -0.15f);
                objects.addAll(creeperGpu.instantiate(creeperRoot, false));
                appendInspection(lines, "creeper.gltf", creeper, creeperGpu, true);

                LoadedGltfScene animated = loader.load(
                        AssetRef.of("/scenes/gltf/animated-two-joint.gltf"));
                GltfSceneAsset animatedGpu = GltfSceneAsset.upload(animated, library);
                assets.add(animatedGpu);
                GltfSceneInstance animatedInstance = animatedGpu.instantiateAnimated(
                        new Matrix4f().translation(-0.45f, -0.7f, 1.0f).scale(1.25f), true);
                animatedInstances.add(animatedInstance);
                objects.addAll(animatedInstance.objects());
                appendInspection(lines, "animated-two-joint.gltf", animated, animatedGpu, true);
                if (includeMorphCaster) {
                    LoadedGltfScene morph = loader.load(
                            AssetRef.of("/scenes/gltf/morph-shadow.gltf"));
                    GltfSceneAsset morphGpu = GltfSceneAsset.upload(morph, library);
                    assets.add(morphGpu);
                    GltfSceneInstance morphInstance = morphGpu.instantiateAnimated(
                            new Matrix4f().translation(1.1f, -0.65f, 0.7f).scale(0.8f), true);
                    animatedInstances.add(morphInstance);
                    objects.addAll(morphInstance.objects());
                    appendInspection(lines, "morph-shadow.gltf", morph, morphGpu, true);
                }
                probe = new AnimationProbe(animatedInstance, 2);
                timeScale = 1.0f;
            }
            return new GltfDemoAssets(library, assets, animatedInstances, objects, lines,
                    probe, timeScale, playlist);
        } catch (RuntimeException failure) {
            RuntimeException primary = closeInstances(animatedInstances, failure);
            primary = closeAssets(assets, primary);
            if (library.activeAssetCount() == 0) {
                try {
                    library.close();
                } catch (RuntimeException cleanup) {
                    primary.addSuppressed(cleanup);
                }
            }
            throw primary;
        }
    }

    private static List<Integer> animationIndices(int count) {
        List<Integer> indices = new ArrayList<>(count);
        for (int index = 0; index < count; index++) indices.add(index);
        return List.copyOf(indices);
    }

    List<SceneObject> objects() {
        ensureOpen();
        return objects;
    }

    List<String> inspectionLines() {
        ensureOpen();
        return inspectionLines;
    }

    void update(float deltaSeconds) {
        ensureOpen();
        float animationDelta = deltaSeconds * animationTimeScale;
        if (animationPlaylist != null) animationPlaylist.update(animationDelta);
        animatedInstances.forEach(instance -> instance.update(animationDelta));
    }

    /** Move the first shadow-casting animated asset for deterministic culling proofs. */
    void translateShadowCaster(float x, float y, float z) {
        ensureOpen();
        if (animatedInstances.isEmpty()) return;
        animatedInstances.getFirst().translateRoot(new Vector3f(x, y, z));
    }

    float animationMotion() {
        ensureOpen();
        return animationProbe.movement();
    }

    String animationDebugText() {
        ensureOpen();
        GltfSceneInstance instance = animationProbe.instance;
        String target = instance.animationTransitionTarget();
        return String.format(Locale.ROOT,
                "Animation | state %s | time %.3fs | phase %.3f | windows %s | "
                        + "transition %s%.0f%% | %s",
                instance.currentAnimationState(), instance.animationTimeSeconds(),
                instance.currentAnimationNormalizedTime(),
                instance.activeAnimationWindows().isEmpty()
                        ? "-" : instance.activeAnimationWindows(),
                target.isEmpty() ? "-" : target + " ",
                instance.transitionWeight() * 100.0f,
                instance.animationTransitionReason());
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException failure = closeInstances(animatedInstances, null);
        failure = closeAssets(assets, failure);
        try {
            library.close();
        } catch (RuntimeException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
    }

    private static void appendInspection(List<String> lines, String name,
                                         LoadedGltfScene scene, GltfSceneAsset gpu,
                                         boolean includeWarnings) {
        GltfSceneStatistics stats = scene.statistics();
        lines.add(String.format(Locale.ROOT,
                "%s | scene %d:%s | node %d/%d | primitive %d",
                name, scene.selectedSceneIndex(), scene.selectedSceneName(),
                stats.reachableNodeCount(), stats.nodeCount(), stats.primitiveCount()));
        lines.add(String.format(Locale.ROOT,
                "  material %d image %d | GPU mesh %d texture %d sampler %d",
                stats.materialCount(), stats.imageCount(), gpu.uniqueMeshCount(),
                gpu.uniqueTextureCount(), gpu.uniqueSamplerCount()));
        lines.add(String.format(Locale.ROOT,
                "  decoded %.1f KiB | vertex %.1f KiB | index %.1f KiB",
                stats.decodedBufferBytes() / 1024.0, stats.vertexBytes() / 1024.0,
                stats.indexBytes() / 1024.0));
        if (stats.skinCount() > 0 || stats.animationCount() > 0) {
            lines.add(String.format(Locale.ROOT,
                    "  skin %d | animation %d | channel %d",
                    stats.skinCount(), stats.animationCount(), stats.animationChannelCount()));
        }
        scene.materials().forEach(material -> lines.add(String.format(Locale.ROOT,
                "  material[%d] %s | %s cutoff %.2f | textures %s",
                material.index(), material.name().isBlank() ? "unnamed" : material.name(),
                material.alphaMode(), material.alphaCutoff(), material.textureIndices().keySet())));
        scene.rootNodeIndices().forEach(root -> appendNode(lines, scene, root, 1));
        if (includeWarnings) {
            scene.warnings().forEach(warning -> lines.add("  warning: " + warning));
        }
    }

    private static void appendNode(List<String> lines, LoadedGltfScene scene,
                                   int nodeIndex, int depth) {
        LoadedGltfScene.Node node = scene.nodes().get(nodeIndex);
        lines.add("  ".repeat(depth) + "node[" + node.index() + "] "
                + (node.name().isBlank() ? "unnamed" : node.name()) + " mesh=" + node.meshIndex());
        node.children().forEach(child -> appendNode(lines, scene, child, depth + 1));
    }

    private static RuntimeException closeAssets(List<GltfSceneAsset> assets,
                                                RuntimeException primary) {
        RuntimeException failure = primary;
        for (int index = assets.size() - 1; index >= 0; index--) {
            try {
                assets.get(index).close();
            } catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        return failure;
    }

    private static RuntimeException closeInstances(List<GltfSceneInstance> instances,
                                                    RuntimeException primary) {
        RuntimeException failure = primary;
        for (int index = instances.size() - 1; index >= 0; index--) {
            try {
                instances.get(index).close();
            } catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        return failure;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("GltfDemo assets are closed");
    }

    private static final class AnimationProbe {
        private final GltfSceneInstance instance;
        private final int nodeIndex;
        private final Matrix4f initial;

        private AnimationProbe(GltfSceneInstance instance, int nodeIndex) {
            this.instance = instance;
            this.nodeIndex = nodeIndex;
            this.initial = new Matrix4f(instance.nodeModelMatrix(nodeIndex));
        }

        private float movement() {
            Matrix4fc current = instance.nodeModelMatrix(nodeIndex);
            return Math.abs(current.m00() - initial.m00())
                    + Math.abs(current.m01() - initial.m01())
                    + Math.abs(current.m02() - initial.m02())
                    + Math.abs(current.m10() - initial.m10())
                    + Math.abs(current.m11() - initial.m11())
                    + Math.abs(current.m12() - initial.m12())
                    + Math.abs(current.m20() - initial.m20())
                    + Math.abs(current.m21() - initial.m21())
                    + Math.abs(current.m22() - initial.m22())
                    + Math.abs(current.m30() - initial.m30())
                    + Math.abs(current.m31() - initial.m31())
                    + Math.abs(current.m32() - initial.m32());
        }
    }

    private record PlaylistClip(String name, AnimationPlayer.LoopMode loopMode,
                                float displaySeconds) {
        private PlaylistClip {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("playlist clip name must not be blank");
            }
            if (!Float.isFinite(displaySeconds) || displaySeconds <= 0.0f) {
                throw new IllegalArgumentException("playlist displaySeconds must be positive");
            }
        }
    }

    private static final class AnimationPlaylist {
        private final GltfSceneInstance instance;
        private final List<ResolvedPlaylistClip> clips;
        private int current;
        private float elapsedSeconds;

        private AnimationPlaylist(GltfSceneInstance instance,
                                  List<ResolvedPlaylistClip> clips) {
            this.instance = instance;
            this.clips = List.copyOf(clips);
            playCurrent(false);
        }

        private static AnimationPlaylist create(GltfSceneInstance instance,
                                                List<PlaylistClip> clips) {
            List<String> names = instance.animationNames();
            List<ResolvedPlaylistClip> resolved = new ArrayList<>(clips.size());
            for (PlaylistClip clip : clips) {
                int index = names.indexOf(clip.name());
                if (index < 0) {
                    throw new IllegalStateException(
                            "player_wild animation library has no '" + clip.name() + "' clip");
                }
                resolved.add(new ResolvedPlaylistClip(index, clip.loopMode(),
                        clip.displaySeconds()));
            }
            return new AnimationPlaylist(instance, resolved);
        }

        private void update(float deltaSeconds) {
            elapsedSeconds += deltaSeconds;
            while (elapsedSeconds >= clips.get(current).displaySeconds()) {
                elapsedSeconds -= clips.get(current).displaySeconds();
                current = (current + 1) % clips.size();
                playCurrent(true);
            }
        }

        private void playCurrent(boolean transition) {
            ResolvedPlaylistClip clip = clips.get(current);
            if (transition) {
                instance.transitionTo(clip.animationIndex(), clip.loopMode(),
                        PLAYER_WILD_TRANSITION_SECONDS);
            } else {
                instance.play(clip.animationIndex(), clip.loopMode());
            }
        }
    }

    private record ResolvedPlaylistClip(int animationIndex,
                                        AnimationPlayer.LoopMode loopMode,
                                        float displaySeconds) {
    }
}
