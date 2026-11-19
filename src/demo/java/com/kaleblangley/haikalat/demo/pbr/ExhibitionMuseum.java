package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.texture.Texture2D;
import com.kaleblangley.haikalat.backend.texture.TextureColorSpace;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.assets.PbrTextureRole;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.subsystems.render3d.Camera;
import com.kaleblangley.haikalat.subsystems.render3d.LightType;
import com.kaleblangley.haikalat.subsystems.render3d.RenderFeatureSettings;
import com.kaleblangley.haikalat.subsystems.render3d.RenderPipeline;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.SceneLight;
import com.kaleblangley.haikalat.subsystems.render3d.SceneObject;
import com.kaleblangley.haikalat.subsystems.render3d.geometry.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecal;
import com.kaleblangley.haikalat.subsystems.render3d.vfx.ProjectedDecalSettings;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Walkable gallery whose exhibits share the visitor's camera and presentation. */
final class ExhibitionMuseum {
    private static final double EMISSIVE_GAIN = 8.0;
    private static final int[] COMPENSATED = java.util.stream.IntStream.range(0, 256)
            .map(ExhibitionMuseum::compensateChannel).toArray();
    private static final float PLAQUE_WIDTH = 2.6f;
    private static final float WALL_Z = -38f;
    private static final float ROOM_HALF_X = 27.5f;
    private static final float ROOM_HALF_DEPTH = 38f;
    private static final float ROOM_HEIGHT = 5.5f;
    private static final float VISITOR_HEIGHT = 1.75f;
    private static final float PEDESTAL_HALF_WIDTH = 1.49f;
    private static final float PEDESTAL_HALF_DEPTH = 1.12f;
    private static final float FIRST_COLUMN_X = 5.6f;
    private static final float COLUMN_SPACING = 5.8f;
    private static final float ROW_SPACING = 6f;
    private static final float WING_SPACING = 16f;
    private static final float ENTER_DISTANCE = 3.3f;
    private static final float LEAVE_DISTANCE = 4.2f;
    private static final Set<Integer> SHARED_EFFECTS = Set.of(1, 2, 3, 5, 6, 7, 16, 17, 31, 32, 46);
    private static final Set<Integer> BASIN_EXHIBITS = Set.of(11, 14, 30, 36);
    private static final Set<Integer> GI_ROOMS = Set.of(39, 44, 45);

    private final Scene scene;
    private final Camera camera;
    private final List<Exhibit> exhibits = new ArrayList<>();
    private final Stage[] stages = new Stage[47];
    private final List<ExhibitionVfxFeed> floorFeeds = new ArrayList<>();
    private final List<ExhibitionStationDriver> floorDrivers = new ArrayList<>();
    private final ExhibitionStationBuildContext context;
    private final RenderFeatureSettings.Builder hallSettings;
    private List<ProjectedDecal> floorDecals = List.of();
    private Matrix4f hairModel;
    private final Vector3f walkingPosition = new Vector3f(0, VISITOR_HEIGHT, 35f);
    private float walkingYaw = -90f;
    private float walkingPitch = -9f;
    private boolean overview;

    record Exhibit(int stationId, Vector3f center, float halfDepth, int firstModel, int endModel) {}

    private record Stage(Exhibit exhibit, Matrix4f place, ExhibitionStationView view) {}

    record Visit(Scene scene, Matrix4f worldToStage, ExhibitionStationView view) {}

    private ExhibitionMuseum(ExhibitionSession session) {
        context = new ExhibitionStationBuildContext(session.assets(), session.renderSettings());
        hallSettings = ExhibitionSettings.base(session.renderSettings()).toBuilder();
        camera = new Camera(walkingPosition, new Vector3f(0, 1, 0), walkingYaw, walkingPitch);
        camera.setMovementSpeed(6f);
        scene = new Scene(camera);
    }

    static ExhibitionMuseum open(ExhibitionSession session) {
        ExhibitionMuseum museum = new ExhibitionMuseum(session);
        museum.build();
        return museum;
    }

    static boolean supportsGiComparison(int stationId) {
        return GI_ROOMS.contains(stationId);
    }

    private void build() {
        ExhibitionAssets assets = context.assets();
        addRoom(scene, assets);
        addBanner(scene, assets);
        addLights(scene);
        for (ExhibitionWing wing : ExhibitionWing.values()) {
            int side = wing.ordinal() % 2 == 0 ? -1 : 1;
            int wingRow = wing.ordinal() / 2;
            Vector3f accent = wingColor(wing);
            Material inlay = assets.pbr(properties(accent.x * 0.35f, accent.y * 0.35f, accent.z * 0.35f, 0.95f, 0));
            float firstRowZ = 24f - wingRow * WING_SPACING;
            float centerX = side * (FIRST_COLUMN_X + 1.5f * COLUMN_SPACING);
            scene.add(SceneObject.fixed(assets.quad, inlay,
                    new Matrix4f().translation(centerX, 0.014f, firstRowZ - ROW_SPACING / 2)
                            .rotateX((float) (-Math.PI / 2)).scale(22f, 11f, 1), false));
            addWingSign(assets, wing, side, firstRowZ + 1.5f);
            int index = 0;
            for (int id : wing.stationIds()) {
                float x = side * (FIRST_COLUMN_X + index % 4 * COLUMN_SPACING);
                float z = firstRowZ - index / 4 * ROW_SPACING;
                ExhibitionStation station = ExhibitionCatalog.station(id);
                Vector3f center = new Vector3f(x, 0.8f, z);
                float halfDepth = GI_ROOMS.contains(id) ? 1.85f : PEDESTAL_HALF_DEPTH;
                scene.add(SceneObject.fixed(assets.cube, assets.darkMaterial,
                        new Matrix4f().translation(x, 0.22f, z)
                                .scale(PEDESTAL_HALF_WIDTH, 0.22f, halfDepth)));
                int first = scene.rendererCount();
                Matrix4f place = placement(id, center);
                ExhibitionStationView view;
                if (SHARED_EFFECTS.contains(id)) {
                    view = switch (id) {
                        case 7 -> ExhibitionWingAStations.hallLight(context, place);
                        case 32 -> ExhibitionWingGStations.hallSubsurface(context);
                        default -> buildView(station, ExhibitionPhase.EFFECT);
                    };
                    addSubjects(scene, view.scene(), place, id, true);
                    configureSharedEffect(id, view, place);
                } else {
                    boolean reference = id == 13 || id == 18 || id == 28;
                    var model = buildView(station, reference ? ExhibitionPhase.BASELINE : ExhibitionPhase.EFFECT);
                    addSubjects(scene, model.scene(), place, id, false);
                    if (id == 37) ExhibitionWingHStations.addGalleryPanels(scene, assets, place);
                    view = reference ? null : model;
                }
                Exhibit exhibit = new Exhibit(id, center, halfDepth, first, scene.rendererCount());
                exhibits.add(exhibit);
                stages[id] = new Stage(exhibit, place, view);
                Material caption = assets.pbr(emissiveProperties(), java.util.Map.of(
                        PbrTextureRole.EMISSIVE, plaqueTexture(assets, station)));
                scene.add(SceneObject.fixed(assets.quad, caption,
                        new Matrix4f().translation(x, 0.57f, z + halfDepth - 0.14f)
                                .rotateX((float) Math.toRadians(-65)).scale(PLAQUE_WIDTH, 0.44f, 1), false));
                index++;
            }
        }
    }

    private static Matrix4f placement(int id, Vector3f center) {
        float scale = switch (id) {
            case 1, 2, 3, 5, 46 -> 0.60f;
            case 6, 7, 8, 16, 17, 31, 32 -> 0.53f;
            case 13 -> 0.42f;
            case 11, 14, 30, 36 -> 0.35f;
            case 12, 20, 21, 34, 39, 41, 44, 45 -> 0.33f;
            default -> 0.42f;
        };
        float offsetZ = switch (id) {
            case 1, 2, 3, 5, 6, 7 -> 1.5f;
            case 13 -> 0.8f;
            case 11, 14, 30, 36 -> 0;
            case 19 -> 5.1f;
            case 29, 40 -> 8f;
            case 12, 21 -> 1.6f;
            case 39, 44, 45 -> 1.4f;
            default -> 2.7f;
        };
        float baseY = BASIN_EXHIBITS.contains(id) ? 0.44f + scale : 0.44f;
        return new Matrix4f().translation(center.x, baseY, center.z)
                .scale(scale).translate(0, 0, offsetZ);
    }

    private void configureSharedEffect(int id, ExhibitionStationView view, Matrix4f placement) {
        float scale = placement.m00();
        if (view.driver() != null) {
            floorDrivers.add(view.driver());
            ExhibitionVfxFeed feed = view.driver().vfxFeed();
            if (feed != null) {
                Matrix4f localView = new Matrix4f();
                floorFeeds.add((commands, projection, cameraView, depth, w, h, frame, delta) ->
                        feed.record(commands, projection, cameraView.mul(placement, localView),
                                depth, w, h, frame, delta));
            }
        }
        switch (id) {
            case 6 -> {
                ProjectedDecalSettings decals = view.settings().projectedDecals();
                hallSettings.projectedDecals(new ProjectedDecalSettings(true, decals.thickness() * scale,
                        decals.surfaceOffset() * scale, decals.facingCosine(), decals.receiveBackFaces(),
                        decals.alphaMask(), decals.maximumDecals()));
                floorDecals = view.driver().projectedDecals(0, 0f, ExhibitionPhase.EFFECT).stream()
                        .map(decal -> new ProjectedDecal(decal.sequence(), decal.texture(),
                                placement.transformPosition(decal.center()), decal.normal(), decal.up(),
                                decal.size().mul(scale), decal.color(), decal.emissiveScale()))
                        .toList();
            }
            case 32 -> {
                for (SceneLight light : view.scene().lights()) {
                    scene.addLight(SceneLight.point(placement.transformPosition(light.position()),
                            light.color(), light.intensity() * scale * scale, light.range() * scale));
                }
            }
            case 46 -> {
                hallSettings.hairStrands(view.settings().hairStrands());
                hairModel = ExhibitionWingGStations.placeHair(placement);
            }
            default -> { }
        }
    }

    private ExhibitionStationView buildView(ExhibitionStation station, ExhibitionPhase phase) {
        return ExhibitionStationWiring.builderFor(station.id()).build(context, station, phase);
    }

    private void addSubjects(Scene destination, Scene subject, Matrix4f placement, int id, boolean shadows) {
        ExhibitionAssets assets = context.assets();
        for (var mesh : subject.renderers()) {
            Material material = mesh.material().material();
            Matrix4f model = mesh.modelMatrix(new Matrix4f(), 0);
            if (mesh.mesh() == assets.groundQuad) continue;
            if (id != 19 && mesh.mesh() == assets.quad && (material == assets.wallMaterial
                    || (model.m32() == -6f && material == assets.darkMaterial))) continue;
            if (BASIN_EXHIBITS.contains(id) && mesh.mesh() == assets.quad && model.m31() >= 0f) continue;
            // The hall itself supplies the visible horizon around the local landmarks.
            if ((id == 29 || id == 40) && model.m32() < -12f) continue;
            // The pedestal supplies scale, so omit the studio's two scale props.
            if (mesh.mesh() == assets.cube && Math.abs(model.m30()) == 2.9f && model.m32() == -3.2f) continue;
            addPlaced(destination, mesh, placement, shadows && mesh.castShadows());
        }
    }

    private static void addPlaced(Scene destination, MeshRenderer mesh, Matrix4f place, boolean shadows) {
        Material material = mesh.material().material();
        if (mesh.immutableModel()) {
            Matrix4f model = mesh.modelMatrix(new Matrix4f(), 0);
            destination.add(SceneObject.fixed(mesh.mesh(), material, place.mul(model, new Matrix4f()),
                    shadows, mesh.drawBinding()));
        } else {
            destination.add(new SceneObject(mesh.mesh(), material,
                    (out, frame) -> place.mul(mesh.modelMatrix(out, frame), out), shadows, mesh.drawBinding()));
        }
    }

    Visit visit(int stationId) {
        Stage stage = stages[stationId];
        ExhibitionStationView view = stage.view();
        if (view == null) {
            view = buildView(ExhibitionCatalog.station(stationId), ExhibitionPhase.EFFECT);
            stages[stationId] = new Stage(stage.exhibit(), stage.place(), view);
        }
        var visit = new Visit(new Scene(new Camera()), stage.place().invert(new Matrix4f()), view);
        // Preserve both clip distances in hall metres after the unit change.
        float units = visit.worldToStage().m00();
        visit.scene().camera().setClipPlanes(camera.nearPlane() * units, camera.farPlane() * units);
        var active = stage.exhibit();
        // Keep the view and every exhibit in the same scene, expressed in the
        // selected exhibit's units so its existing world-space inputs still align.
        for (int index = 0; index < scene.rendererCount(); index++) {
            if (index >= active.firstModel() && index < active.endModel()) continue;
            var mesh = scene.rendererAt(index);
            addPlaced(visit.scene(), mesh, visit.worldToStage(), false);
        }
        addSubjects(visit.scene(), view.scene(), new Matrix4f(), stationId, true);
        for (SceneLight light : view.scene().lights()) visit.scene().addLight(light);
        for (SceneLight light : scene.lights()) {
            if (light.type() != LightType.POINT) continue;
            visit.scene().addLight(SceneLight.point(visit.worldToStage().transformPosition(light.position()),
                    light.color(), light.intensity() * units * units, light.range() * units));
        }
        updateVisitCamera(visit);
        return visit;
    }

    void updateVisitCamera(Visit visit) {
        Camera active = visit.scene().camera();
        active.setPosition(visit.worldToStage().transformPosition(camera.position()));
        active.setYaw(camera.yaw());
        active.setPitch(camera.pitch());
        active.setZoom(camera.zoom());
    }

    int effectStationId(int currentId) {
        if (overview) return -1;
        Vector3f eye = camera.position();
        Vector3f front = camera.front();
        int closest = -1;
        float bestDistance = ENTER_DISTANCE * ENTER_DISTANCE;
        for (Exhibit exhibit : exhibits) {
            Vector3f center = exhibit.center();
            float distance = horizontalDistanceSquared(eye, center);
            float facing = (center.x - eye.x) * front.x + (center.z - eye.z) * front.z;
            if (distance < bestDistance && facing > 0.25f) {
                closest = exhibit.stationId();
                bestDistance = distance;
            }
        }
        if (currentId > 0 && closest != currentId) {
            float currentDistance = horizontalDistanceSquared(eye, stages[currentId].exhibit().center());
            if (currentDistance <= LEAVE_DISTANCE * LEAVE_DISTANCE
                    && (closest < 0 || currentDistance <= bestDistance + 1f)) return currentId;
        }
        return SHARED_EFFECTS.contains(closest) ? -1 : closest;
    }

    private static float horizontalDistanceSquared(Vector3f a, Vector3f b) {
        float dx = a.x - b.x;
        float dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    void configure(RenderPipeline pipeline) {
        pipeline.hairStrandModel(hairModel);
        pipeline.features().submit(hallSettings.build());
    }

    List<ProjectedDecal> floorDecals() {
        return floorDecals;
    }

    ExhibitionVfxFeed floorVfx() {
        return (commands, projection, view, depth, w, h, frame, delta) -> {
            for (ExhibitionVfxFeed feed : floorFeeds) {
                feed.record(commands, projection, view, depth, w, h, frame, delta);
            }
        };
    }

    void updateSharedEffects(RenderPipeline pipeline, int frameIndex, float delta) {
        for (ExhibitionStationDriver driver : floorDrivers) {
            driver.onFrame(pipeline, frameIndex, delta, ExhibitionPhase.EFFECT);
        }
    }

    private static void addRoom(Scene scene, ExhibitionAssets assets) {
        Material floorMaterial = assets.pbr(properties(0.24f, 0.26f, 0.28f, 0.92f, 0.0f));
        Material wallMaterial = assets.pbr(properties(0.075f, 0.095f, 0.13f, 0.9f, 0.0f));
        Material aisleMaterial = assets.pbr(properties(0.31f, 0.34f, 0.36f, 0.92f, 0));
        float centerZ = WALL_Z + ROOM_HALF_DEPTH;
        Matrix4f floor = new Matrix4f().identity()
                .translation(0.0f, 0.0f, centerZ)
                .rotateX((float) (-Math.PI / 2.0))
                .scale(2 * (ROOM_HALF_X + 0.4f), 2 * ROOM_HALF_DEPTH, 1.0f);
        scene.add(SceneObject.fixed(assets.quad, floorMaterial, floor, false));
        scene.add(SceneObject.fixed(assets.quad, aisleMaterial,
                new Matrix4f().translation(0, 0.014f, centerZ)
                        .rotateX((float) (-Math.PI / 2)).scale(6.4f, 2 * ROOM_HALF_DEPTH, 1), false));
        Matrix4f front = new Matrix4f().identity()
                .translation(0.0f, ROOM_HEIGHT / 2.0f, WALL_Z - 0.06f)
                .scale(2 * (ROOM_HALF_X + 0.4f), ROOM_HEIGHT, 1.0f);
        scene.add(SceneObject.fixed(assets.quad, wallMaterial, front, false));
        Matrix4f back = new Matrix4f().identity()
                .translation(0.0f, ROOM_HEIGHT / 2.0f, WALL_Z + 2.0f * ROOM_HALF_DEPTH + 0.06f)
                .rotateY((float) Math.PI)
                .scale(2 * (ROOM_HALF_X + 0.4f), ROOM_HEIGHT, 1.0f);
        scene.add(SceneObject.fixed(assets.quad, wallMaterial, back, false));
        for (int side = -1; side <= 1; side += 2) {
            Matrix4f sideWall = new Matrix4f().identity()
                    .translation(side * (ROOM_HALF_X + 0.06f), ROOM_HEIGHT / 2.0f, centerZ)
                    .rotateY((float) (-side * Math.PI / 2.0))
                    .scale(2 * ROOM_HALF_DEPTH, ROOM_HEIGHT, 1.0f);
            scene.add(SceneObject.fixed(assets.quad, wallMaterial, sideWall, false));
        }
    }

    private void addWingSign(ExhibitionAssets assets, ExhibitionWing wing, int side, float z) {
        BufferedImage image = new BufferedImage(512, 112, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(19, 35, 43));
        graphics.fillRect(0, 0, 512, 112);
        Vector3f accent = wingColor(wing);
        graphics.setColor(new Color(clampColor(accent.x), clampColor(accent.y), clampColor(accent.z)));
        graphics.fillRect(0, 0, 12, 112);
        graphics.setColor(new Color(244, 238, 219));
        graphics.setFont(cjkFont(Font.BOLD, 52));
        graphics.drawString(wing.displayName(), 32, 74);
        graphics.dispose();
        Material sign = assets.pbr(emissiveProperties(), java.util.Map.of(PbrTextureRole.EMISSIVE,
                assets.ownTexture(uploadDisplayImage(image))));
        scene.add(SceneObject.fixed(assets.quad, sign,
                new Matrix4f().translation(side * 5.6f, 0.025f, z)
                        .rotateX((float) (-Math.PI / 2)).scale(4.1f, 0.9f, 1), false));
    }

    private static void addBanner(Scene scene, ExhibitionAssets assets) {
        BufferedImage image = new BufferedImage(1200, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(19, 35, 43));
        graphics.fillRect(0, 0, 1200, 120);
        graphics.setColor(new Color(225, 180, 109));
        graphics.fillRect(0, 0, 12, 120);
        graphics.setFont(cjkFont(Font.BOLD, 53));
        graphics.setColor(new Color(244, 238, 219));
        graphics.drawString("实时渲染展览馆", 40, 78);
        graphics.setColor(new Color(158, 199, 197));
        graphics.setFont(cjkFont(Font.PLAIN, 30));
        graphics.drawString("8 个主题展翼   /   46 个交互展位", 680, 73);
        graphics.dispose();
        Texture2D banner = assets.ownTexture(uploadDisplayImage(image));
        Material material = assets.pbr(emissiveProperties(),
                java.util.Map.of(PbrTextureRole.EMISSIVE, banner));
        Matrix4f transform = new Matrix4f().identity()
                .translation(0.0f, 3.2f, WALL_Z + 0.04f)
                .scale(17.0f, 1.7f, 1.0f);
        scene.add(SceneObject.fixed(assets.quad, material, transform, false));
    }

    private static void addLights(Scene scene) {
        scene.addLight(SceneLight.shadowedDirectional(new Vector3f(-0.35f, -1.0f, -0.5f),
                new Vector3f(1.0f, 0.97f, 0.92f), 1.8f));
        for (float z = 24f; z >= -24f; z -= WING_SPACING) {
            scene.addLight(SceneLight.point(new Vector3f(0f, 5.6f, z - 3f),
                    new Vector3f(1.0f, 0.93f, 0.85f), 6.0f, 28.0f));
        }
    }

    private static PbrMaterialProperties properties(float red, float green, float blue,
                                                    float roughness, float emissive) {
        return new PbrMaterialProperties(new org.joml.Vector4f(red, green, blue, 1.0f),
                0.0f, roughness, 1.0f, 1.0f,
                new Vector3f(emissive, emissive, emissive), java.util.Map.of());
    }

    private static PbrMaterialProperties emissiveProperties() {
        return new PbrMaterialProperties(new org.joml.Vector4f(0.0f, 0.0f, 0.0f, 1.0f),
                0.0f, 1.0f, 1.0f, 1.0f,
                new Vector3f((float) EMISSIVE_GAIN, (float) EMISSIVE_GAIN,
                        (float) EMISSIVE_GAIN), java.util.Map.of());
    }

    private static Texture2D plaqueTexture(ExhibitionAssets assets, ExhibitionStation station) {
        BufferedImage image = new BufferedImage(512, 96, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setColor(new Color(14, 18, 26));
        graphics.fillRect(0, 0, 512, 96);
        Vector3f accent = wingColor(station.wing());
        graphics.setColor(new Color(clampColor(accent.x), clampColor(accent.y),
                clampColor(accent.z)));
        graphics.fillRect(0, 0, 86, 96);
        graphics.setColor(new Color(10, 16, 24));
        graphics.setFont(cjkFont(Font.BOLD, 23));
        graphics.drawString(station.wing().label(), 13, 30);
        graphics.setFont(cjkFont(Font.BOLD, 45));
        graphics.drawString(String.format(java.util.Locale.ROOT, "%02d", station.id()), 12, 79);
        graphics.setColor(new Color(240, 241, 230));
        graphics.setFont(cjkFont(Font.BOLD, 28));
        graphics.drawString(station.nameZh(), 102, 43);
        graphics.setColor(new Color(182, 196, 199));
        graphics.setFont(cjkFont(Font.PLAIN, 17));
        String subtitle = switch (station.id()) {
            case 32 -> "左 关闭　／　中 厚样　／　右 薄样";
            case 39 -> "SSGI  ·  双色反弹　／　B 对照";
            case 44 -> "DDGI  ·  左 探针范围内　／　右 范围外";
            case 45 -> "VCT  ·  左 开放光路　／　右 有遮挡";
            default -> station.nameEn();
        };
        graphics.drawString(subtitle, 102, 75);
        graphics.dispose();
        return assets.ownTexture(uploadDisplayImage(image));
    }

    private static int clampColor(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255.0f)));
    }

    private static Vector3f wingColor(ExhibitionWing wing) {
        return switch (wing) {
            case A -> new Vector3f(0.85f, 0.32f, 0.24f);
            case B -> new Vector3f(0.92f, 0.55f, 0.18f);
            case C -> new Vector3f(0.62f, 0.78f, 0.3f);
            case D -> new Vector3f(0.2f, 0.62f, 0.85f);
            case E -> new Vector3f(0.35f, 0.4f, 0.92f);
            case F -> new Vector3f(0.7f, 0.42f, 0.88f);
            case G -> new Vector3f(0.88f, 0.36f, 0.68f);
            case H -> new Vector3f(0.55f, 0.7f, 0.72f);
        };
    }

    private static Font cjkFont(int style, int size) {
        Font font = new Font("Microsoft YaHei", style, size);
        if (font.getFamily().equalsIgnoreCase("Dialog")) {
            font = new Font(Font.SANS_SERIF, style, size);
        }
        return font;
    }

    /** Uploads top-down display pixels as a compensated SRGB texture. */
    private static Texture2D uploadDisplayImage(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        byte[] rgba = new byte[width * height * 4];
        for (int y = 0; y < height; y++) {
            int sourceY = height - 1 - y;
            for (int x = 0; x < width; x++) {
                int rgb = image.getRGB(x, sourceY);
                int offset = (y * width + x) * 4;
                rgba[offset] = (byte) COMPENSATED[(rgb >> 16) & 0xFF];
                rgba[offset + 1] = (byte) COMPENSATED[(rgb >> 8) & 0xFF];
                rgba[offset + 2] = (byte) COMPENSATED[rgb & 0xFF];
                rgba[offset + 3] = (byte) 255;
            }
        }
        return Texture2D.fromRgba8(width, height, rgba, TextureColorSpace.SRGB);
    }

    /**
     * Maps one display-space sRGB channel to the SRGB texel that, emitted as
     * emissive radiance, is tonemapped back to the same display value by the
     * hall's HDR + ACES pipeline.
     */
    private static int compensateChannel(int displayChannel) {
        double display = displayChannel / 255.0;
        double afterAces = Math.min(1.0, Math.pow(display, 2.2));
        double hdr = inverseAces(afterAces);
        double texel = Math.min(1.0, hdr / EMISSIVE_GAIN);
        double encoded = texel <= 0.0031308
                ? 12.92 * texel
                : 1.055 * Math.pow(texel, 1.0 / 2.4) - 0.055;
        return (int) Math.round(Math.max(0.0, Math.min(1.0, encoded)) * 255.0);
    }

    private static double inverseAces(double target) {
        if (target <= 0.0) {
            return 0.0;
        }
        double low = 0.0;
        double high = 64.0;
        for (int iteration = 0; iteration < 48; iteration++) {
            double middle = (low + high) * 0.5;
            if (aces(middle) < target) {
                low = middle;
            } else {
                high = middle;
            }
        }
        return (low + high) * 0.5;
    }

    private static double aces(double value) {
        return (value * (2.51 * value + 0.03))
                / (value * (2.43 * value + 0.59) + 0.14);
    }

    Scene scene() {
        return scene;
    }

    Camera camera() {
        return camera;
    }

    List<Exhibit> exhibits() {
        return exhibits;
    }

    /** Returns whether the selected viewpoint uses captured mouse look. */
    boolean toggleViewpoint() {
        if (overview) {
            camera.setPosition(walkingPosition);
            camera.setYaw(walkingYaw);
            camera.setPitch(walkingPitch);
        } else {
            walkingPosition.set(camera.position());
            walkingYaw = camera.yaw();
            walkingPitch = camera.pitch();
            camera.setPosition(new Vector3f(0, 70f, 38f));
            camera.setYaw(-90);
            camera.setPitch(-63);
        }
        overview = !overview;
        return !overview;
    }

    void move(int forward, int right, int vertical, float delta) {
        int axesSquared = forward * forward + right * right + vertical * vertical;
        if (overview || axesSquared == 0) return;
        float yaw = (float) Math.toRadians(camera.yaw());
        float cos = (float) Math.cos(yaw);
        float sin = (float) Math.sin(yaw);
        float step = camera.movementSpeed() * delta / (float) Math.sqrt(axesSquared);
        Vector3f eye = camera.position();
        eye.x += step * (forward * cos - right * sin);
        eye.z += step * (forward * sin + right * cos);
        eye.y += step * vertical;
        camera.setPosition(eye);
    }

    int aimedStationId() {
        Vector3f eye = camera.position();
        Vector3f front = camera.front();
        if (front.y >= -0.01f) return -1;
        float distance = (0.8f - eye.y) / front.y;
        if (distance < 0 || distance > 7f) return -1;
        float x = eye.x + distance * front.x;
        float z = eye.z + distance * front.z;
        for (Exhibit exhibit : exhibits) {
            if (Math.abs(x - exhibit.center().x) <= PEDESTAL_HALF_WIDTH
                    && Math.abs(z - exhibit.center().z) <= exhibit.halfDepth()) {
                return exhibit.stationId();
            }
        }
        return -1;
    }
}
