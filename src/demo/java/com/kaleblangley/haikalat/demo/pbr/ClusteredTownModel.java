package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.assets.PbrMaterialProperties;
import com.kaleblangley.haikalat.core.material.Material;
import com.kaleblangley.haikalat.core.mesh.BuiltinMeshData;
import com.kaleblangley.haikalat.core.mesh.Mesh;
import com.kaleblangley.haikalat.core.mesh.MeshData;
import com.kaleblangley.haikalat.core.mesh.TangentGenerator;
import com.kaleblangley.haikalat.subsystems.render3d.MeshRenderer;
import com.kaleblangley.haikalat.subsystems.render3d.Scene;
import com.kaleblangley.haikalat.subsystems.render3d.Transform;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrFallbackTextures;
import com.kaleblangley.haikalat.subsystems.render3d.pbr.PbrMaterials;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Procedural, metre-scaled town. Static details are baked into one mesh per
 * material, rather than issuing a draw for every paving stone/window mullion. */
final class ClusteredTownModel {
    enum Surface {
        EARTH(.065f, .095f, .11f), PAVING(.26f, .31f, .34f), PAVING_LIGHT(.36f, .39f, .40f),
        STONE(.43f, .46f, .47f), PLASTER(.72f, .57f, .37f), PLASTER_BLUE(.36f, .53f, .58f),
        TIMBER(.13f, .065f, .032f), WOOD(.32f, .16f, .065f), ROOF(.22f, .075f, .042f),
        ROOF_BLUE(.065f, .17f, .23f), METAL(.08f, .11f, .13f), LEAF(.085f, .23f, .12f),
        LEAF_LIGHT(.17f, .34f, .15f), CLOTH(.48f, .13f, .085f), CREAM(.85f, .72f, .46f),
        WATER(.035f, .24f, .30f), GLOW(1f, .51f, .13f, 2.3f), BLUE_GLOW(.2f, .65f, 1f, .9f);

        final float r, g, b, emission;
        Surface(float r, float g, float b) { this(r, g, b, 0); }
        Surface(float r, float g, float b, float emission) {
            this.r = r; this.g = g; this.b = b; this.emission = emission;
        }
    }

    record Anchors(List<Vector3f> windows, List<Vector3f> lamps, List<Vector3f> shops,
                   List<Vector3f> torches, List<Vector3f> accents) { }

    private final EnumMap<Surface, Vertices> batches = new EnumMap<>(Surface.class);
    private final Matrix4f pose = new Matrix4f();
    private final List<Vector3f> windows = new ArrayList<>(), lamps = new ArrayList<>(),
            shops = new ArrayList<>(), torches = new ArrayList<>(), accents = new ArrayList<>();

    static Anchors build(Scene scene, ShaderProgram shader, PbrFallbackTextures fallbacks,
                         List<Material> materials, List<Mesh> meshes, boolean grayModel) {
        return build(scene, shader, fallbacks, materials, meshes, grayModel, false);
    }

    static Anchors build(Scene scene, ShaderProgram shader, PbrFallbackTextures fallbacks,
                         List<Material> materials, List<Mesh> meshes, boolean grayModel, boolean openTavern) {
        return build(scene, shader, fallbacks, materials, meshes, grayModel, openTavern, false);
    }

    static Anchors build(Scene scene, ShaderProgram shader, PbrFallbackTextures fallbacks,
                         List<Material> materials, List<Mesh> meshes, boolean grayModel,
                         boolean openTavern, boolean openStreetLanterns) {
        ClusteredTownModel model = new ClusteredTownModel();
        model.streets();
        for (int side : new int[]{-1, 1}) {
            for (int row = 0; row < 4; row++) {
                model.pose.translation(side * 12.2f, 0, 12 - row * 11)
                        .rotateY(-side * (float) Math.PI / 2);
                model.house(row, side, openTavern && side == -1 && row == 0);
            }
        }
        model.pose.identity();
        model.clockTower();
        model.fountain();
        for (int side : new int[]{-1, 1}) {
            for (int i = 0; i < 8; i++) model.lamp(side * 5.7f, 20 - i * 6, openStreetLanterns);
            for (int i = 0; i < 5; i++) {
                float z = 18 - i * 11;
                model.tree(side * 19.3f, z, 1 + (i % 2) * .2f);
            }
            model.bench(side * 3.4f, 5.0f);
            model.bench(side * 3.4f, -11f);
        }
        for (int i = 0; i < 32; i++) {
            double angle = i * Math.PI * 2 / 32;
            float x = (float) Math.cos(angle) * 2.45f;
            float z = -3 + (float) Math.sin(angle) * 2.45f;
            Vector3f p = new Vector3f(x, .65f, z);
            model.accents.add(p);
            if (i % 4 == 0) model.box(Surface.BLUE_GLOW, x, .57f, z, .1f, .1f, .1f);
        }
        model.upload(scene, shader, fallbacks, materials, meshes, grayModel);
        return new Anchors(List.copyOf(model.windows), List.copyOf(model.lamps),
                List.copyOf(model.shops), List.copyOf(model.torches), List.copyOf(model.accents));
    }

    private void streets() {
        box(Surface.EARTH, 0, -.35f, -4, 54, .6f, 72);
        box(Surface.PAVING, 0, -.035f, -4, 16, .15f, 62);
        Random random = new Random(242);
        // Broken-joint paving; true XZ geometry, not an incorrectly rotated XY quad.
        for (int row = 0; row < 67; row++) {
            for (int col = 0; col < 18; col++) {
                float x = -6.9f + col * .79f + (row % 2) * .38f;
                box(random.nextInt(4) == 0 ? Surface.PAVING_LIGHT : Surface.PAVING,
                        x, .065f, 26 - row * .9f, .74f, .095f, .84f);
            }
        }
        for (int side : new int[]{-1, 1}) {
            box(Surface.STONE, side * 7.8f, .15f, -4, 1.1f, .3f, 62);
            for (int i = 0; i < 61; i++) {
                box(Surface.PAVING_LIGHT, side * 7.15f, .16f, 26 - i, .24f, .32f, .94f);
            }
        }
    }

    private void house(int row, int side, boolean tavern) {
        float h = 5.7f + (row % 2) * .85f;
        Surface wall = (row + (side > 0 ? 1 : 0)) % 2 == 0 ? Surface.PLASTER : Surface.PLASTER_BLUE;
        Surface roof = side < 0 ? Surface.ROOF_BLUE : Surface.ROOF;
        if (tavern) {
            // A shell with inward-facing surfaces; walking through a removed door
            // must not put the camera inside the old solid wall/body box.
            box(Surface.STONE, 0, .14f, 0, 7.7f, .28f, 7.1f);
            float wallHeight = h + .22f, wallY = .28f + wallHeight / 2;
            box(wall, -3.43f, wallY, 0, .24f, wallHeight, 6.5f);
            box(wall, 3.43f, wallY, 0, .24f, wallHeight, 6.5f);
            box(wall, 0, wallY, -3.13f, 7.1f, wallHeight, .24f);
            for (int s : new int[]{-1, 1})
                box(wall, s * 2.325f, wallY, 3.13f, 2.45f, wallHeight, .24f);
            box(wall, 0, 3 + (h - 2.5f) / 2, 3.13f, 2.2f, h - 2.5f, .24f);
            box(Surface.TIMBER, 0, h + .38f, 0, 6.8f, .24f, 6.2f);
            tavernInterior();
        } else {
            box(Surface.STONE, 0, .32f, 0, 7.7f, .64f, 7.1f);
            box(wall, 0, h / 2 + .5f, 0, 7.1f, h, 6.5f);
        }
        float eaves = h + .5f;
        roof(wall, 3.55f, eaves, 2.7f, 3.25f);
        roof(roof, 3.95f, eaves + .04f, 2.85f, 3.75f);
        // Visible roof courses and standing seams follow the actual roof slope.
        for (int s : new int[]{-1, 1}) {
            for (int i = 1; i < 8; i++) {
                float x = s * i * .47f;
                float y = eaves + .1f + 2.85f * (1 - Math.abs(x) / 3.95f);
                box(roof, x, y, 0, .07f, .075f, 7.55f);
            }
            beam(Surface.TIMBER, v(0, eaves + 2.94f, 3.79f), v(s * 4, eaves, 3.79f), .16f);
        }
        box(Surface.TIMBER, 0, eaves + 2.94f, 0, .22f, .2f, 7.7f);
        for (float x : new float[]{-3.5f, 0, 3.5f}) {
            if (!tavern || x != 0)
                box(Surface.TIMBER, x, (h + .5f) / 2, 3.31f, .19f, h + .5f, .18f);
        }
        for (float y : new float[]{.72f, 3.3f, eaves}) {
            box(Surface.TIMBER, 0, y, 3.31f, 7.2f, .19f, .2f);
            for (int s : new int[]{-1, 1}) box(Surface.TIMBER, s * 3.59f, y, 0, .18f, .19f, 6.6f);
        }
        beam(Surface.TIMBER, v(-3.4f, 3.45f, 3.34f), v(-1.1f, eaves - .12f, 3.34f), .11f);
        beam(Surface.TIMBER, v(3.4f, 3.45f, 3.34f), v(1.1f, eaves - .12f, 3.34f), .11f);
        // Six street-facing windows per shop are actual point-light anchors.
        for (float x : new float[]{-2.25f, 0, 2.25f}) window(x, h - .9f, 3.4f, 1.05f, 1.4f);
        for (float x : new float[]{-2.25f, 2.25f}) window(x, 2.05f, 3.4f, 1.3f, 1.65f);
        window(0, eaves + 1.0f, 3.43f, .65f, .8f);
        // Rear/side elevations need modelling too: the overview sees these
        // faces, whereas the street route sees the shops' front elevations.
        Matrix4f front = new Matrix4f(pose);
        pose.rotateY((float) Math.PI);
        for (float x : new float[]{-1.9f, 1.9f}) {
            windowDetail(x, h - .9f, 3.4f, .95f, 1.3f);
            windowDetail(x, 2.1f, 3.4f, .95f, 1.3f);
            box(Surface.TIMBER, x, eaves / 2, 3.32f, .16f, eaves, .16f);
        }
        for (float y : new float[]{.72f, 3.3f, eaves}) box(Surface.TIMBER, 0, y, 3.32f, 7.2f, .18f, .18f);
        pose.set(front).rotateY((float) Math.PI / 2);
        for (float x : new float[]{-1.7f, 1.7f}) windowDetail(x, h - .9f, 3.67f, .85f, 1.3f);
        pose.set(front).rotateY(-(float) Math.PI / 2);
        for (float x : new float[]{-1.7f, 1.7f}) windowDetail(x, h - .9f, 3.67f, .85f, 1.3f);
        pose.set(front);
        if (tavern) {
            for (int s : new int[]{-1, 1})
                box(Surface.TIMBER, s * 1.1f, 1.64f, 3.39f, .18f, 2.72f, .2f);
            box(Surface.TIMBER, 0, 3.04f, 3.39f, 2.38f, .2f, .2f);
            // Door leaf is opened beside the passage, away from the camera route.
            box(Surface.WOOD, 1.05f, 1.62f, 2.38f, .13f, 2.6f, 1.85f);
            box(Surface.STONE, 0, .15f, 3.9f, 2.2f, .3f, 1.0f);
        } else {
            box(Surface.TIMBER, 0, 1.52f, 3.39f, 1.35f, 2.4f, .15f);
            for (int i = 0; i < 6; i++) box(Surface.WOOD, -.5f + i * .2f, 1.5f, 3.49f, .17f, 2.2f, .07f);
            box(Surface.CREAM, .4f, 1.48f, 3.56f, .09f, .09f, .07f);
            box(Surface.STONE, 0, .25f, 3.9f, 1.9f, .4f, 1.0f);
        }
        // Striped shop awnings and projecting signs make the ground floor readable.
        for (int i = 0; i < 10; i++) {
            Surface stripe = i % 2 == 0 ? Surface.CLOTH : Surface.CREAM;
            float x = -1.4f + i * .3f;
            quad(stripe, v(x, 3.1f, 3.45f), v(x, 2.82f, 4.6f),
                    v(x + .29f, 2.82f, 4.6f), v(x + .29f, 3.1f, 3.45f));
            box(stripe, x + .145f, 2.7f, 4.6f, .29f, .25f, .07f);
        }
        box(Surface.METAL, 3.1f, 3.45f, 4.0f, .08f, .08f, 1.4f);
        box(Surface.WOOD, 3.1f, 2.98f, 4.55f, .16f, .7f, 1.05f);
        // A cross on each hanging sign reads as a crafted shop emblem from either side.
        for (int s : new int[]{-1, 1}) {
            box(Surface.CREAM, 3.1f + s * .1f, 2.98f, 4.55f, .03f, .12f, .65f);
            box(Surface.CREAM, 3.1f + s * .1f, 2.98f, 4.55f, .03f, .45f, .1f);
        }
        for (float x : new float[]{-3.0f, 3.0f}) {
            float torchX = tavern ? x * .63f : x;
            float torchY = tavern ? 2.4f : 2.5f;
            float torchZ = tavern ? -.7f : 3.95f;
            torches.add(point(torchX, torchY, torchZ));
            lantern(torchX, torchY, torchZ, .23f);
            shops.add(point(x * .7f, 1.6f, 4.4f));
        }
        box(Surface.STONE, -2.3f, eaves + 2.05f, -.8f, .7f, 2.5f, .8f);
        box(Surface.PAVING_LIGHT, -2.3f, eaves + 3.28f, -.8f, .94f, .22f, 1.04f);
        for (int i = 0; i < 3; i++) {
            box(Surface.WOOD, -2.8f + i * .62f, .73f, 4.0f, .54f, .8f, .65f);
            box(Surface.TIMBER, -2.8f + i * .62f, .8f, 4.35f, .55f, .09f, .04f);
        }
    }

    private void tavernInterior() {
        // Low counter, wall shelves and a side table leave the centre aisle clear.
        box(Surface.TIMBER, 0, .76f, -2.1f, 5.2f, .96f, .75f);
        box(Surface.WOOD, 0, 1.28f, -2.1f, 5.4f, .16f, 1.0f);
        for (int i = 0; i < 9; i++)
            box(Surface.WOOD, -2.36f + i * .59f, .78f, -1.69f, .53f, .8f, .055f);
        for (float y : new float[]{1.9f, 2.8f}) {
            box(Surface.WOOD, 0, y, -2.87f, 4.8f, .12f, .45f);
            for (int i = 0; i < 7; i++) {
                float x = -1.9f + i * .63f;
                column(i % 2 == 0 ? Surface.LEAF : Surface.WATER,
                        x, y + .06f, -2.86f, .095f, .055f, .33f, 8);
                column(Surface.CREAM, x, y + .39f, -2.86f, .04f, .04f, .09f, 8);
            }
        }
        box(Surface.WOOD, -1.9f, 1.03f, .15f, 1.55f, .16f, 1.45f);
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1})
            box(Surface.TIMBER, -1.9f + sx * .58f, .61f, .15f + sz * .53f, .15f, .82f, .15f);
        for (float z : new float[]{-1.05f, 1.35f}) {
            column(Surface.WOOD, -1.9f, .73f, z, .35f, .35f, .13f, 10);
            column(Surface.TIMBER, -1.9f, .28f, z, .16f, .12f, .45f, 8);
        }
        column(Surface.CREAM, -.7f, 1.37f, -1.98f, .09f, .09f, .22f, 10);
        box(Surface.CLOTH, 2.7f, 2.5f, -3.0f, .7f, 1.2f, .03f);
        for (float z : new float[]{-2.3f, 0, 2.3f})
            box(Surface.TIMBER, 0, 4.3f, z, 6.8f, .19f, .22f);
    }

    private void window(float x, float y, float z, float w, float h) {
        windowDetail(x, y, z, w, h);
        windows.add(point(x, y, z + .5f));
    }

    private void windowDetail(float x, float y, float z, float w, float h) {
        box(Surface.TIMBER, x, y, z, w + .25f, h + .25f, .14f);
        box(Surface.GLOW, x, y, z + .09f, w, h, .04f);
        box(Surface.TIMBER, x, y, z + .13f, .07f, h, .045f);
        box(Surface.TIMBER, x, y + .05f, z + .13f, w, .075f, .045f);
        box(Surface.STONE, x, y - h / 2 - .14f, z + .13f, w + .4f, .13f, .42f);
        for (int s : new int[]{-1, 1}) {
            box(Surface.WOOD, x + s * (w / 2 + .28f), y, z, .27f, h + .1f, .1f);
        }
    }

    private void roof(Surface surface, float halfWidth, float base, float rise, float halfDepth) {
        Vector3f a = v(-halfWidth, base, halfDepth), b = v(halfWidth, base, halfDepth);
        Vector3f c = v(0, base + rise, halfDepth), d = v(-halfWidth, base, -halfDepth);
        Vector3f e = v(halfWidth, base, -halfDepth), f = v(0, base + rise, -halfDepth);
        // The roof is two slopes. Capping its front with roof material would
        // hide the plaster gable and the attic window behind the overhang.
        if (surface != Surface.ROOF && surface != Surface.ROOF_BLUE) {
            triangle(surface, a, b, c); triangle(surface, e, d, f);
        }
        quad(surface, a, c, f, d); quad(surface, c, b, e, f);
    }

    private void lamp(float x, float z, boolean openAperture) {
        float supportX = openAperture ? x + Math.copySign(.75f, x) : x;
        box(Surface.STONE, supportX, .23f, z, .65f, .46f, .65f);
        column(Surface.METAL, supportX, .4f, z, .15f, .085f, openAperture ? 4.5f : 3.8f, 8);
        if (openAperture) {
            box(Surface.METAL, (x + supportX) / 2, 4.9f, z, .95f, .12f, .12f);
        } else {
            box(Surface.METAL, x, 4.28f, z, .7f, .12f, .12f);
        }
        lantern(x, 4.45f, z, .3f, openAperture);
        lamps.add(v(x, 4.36f, z));
    }

    private void lantern(float x, float y, float z, float r) {
        lantern(x, y, z, r, false);
    }

    private void lantern(float x, float y, float z, float r, boolean openAperture) {
        box(Surface.GLOW, x, y, z, r * 1.3f, r * 1.9f, r * 1.3f);
        for (int sx : new int[]{-1, 1}) for (int sz : new int[]{-1, 1}) {
            box(Surface.METAL, x + sx * r * .7f, y, z + sz * r * .7f, .045f, r * 2.1f, .045f);
        }
        if (openAperture) {
            for (int side : new int[]{-1, 1}) {
                box(Surface.METAL, x + side * r * .8f, y - r, z, .045f, .1f, r * 1.8f);
                box(Surface.METAL, x, y - r, z + side * r * .8f, r * 1.8f, .1f, .045f);
            }
        } else {
            box(Surface.METAL, x, y - r, z, r * 1.8f, .1f, r * 1.8f);
        }
        column(Surface.METAL, x, y + r, z, r * 1.2f, .02f, r, 4);
    }

    private void fountain() {
        column(Surface.STONE, 0, .16f, -3, 2.7f, 2.7f, .28f, 16);
        column(Surface.WATER, 0, .44f, -3, 2.15f, 2.15f, .06f, 32);
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            box(Surface.PAVING_LIGHT, (float) Math.cos(a) * 2.35f, .64f,
                    -3 + (float) Math.sin(a) * 2.35f, .52f, .55f, .52f);
        }
        column(Surface.STONE, 0, .48f, -3, .48f, .26f, 1.65f, 10);
        column(Surface.PAVING_LIGHT, 0, 1.65f, -3, .55f, 1.0f, .33f, 12);
        column(Surface.WATER, 0, 1.99f, -3, .89f, .89f, .04f, 16);
        column(Surface.STONE, 0, 2.01f, -3, .2f, .13f, .8f, 8);
        column(Surface.BLUE_GLOW, 0, 2.81f, -3, .16f, 0, .32f, 6);
    }

    private void clockTower() {
        box(Surface.STONE, 0, 4.7f, -28, 5.4f, 9.4f, 4.8f);
        box(Surface.PLASTER, 0, 10.8f, -28, 4.4f, 3.0f, 4.0f);
        for (float y : new float[]{.6f, 3.8f, 8.8f, 9.4f, 12.3f}) {
            box(Surface.PAVING_LIGHT, 0, y, -28, 5.65f, .24f, 5.05f);
        }
        for (int s : new int[]{-1, 1}) {
            for (int i = 0; i < 11; i++) {
                box(Surface.PAVING_LIGHT, s * 2.5f, .9f + i * .72f, -25.55f,
                        i % 2 == 0 ? .5f : .75f, .42f, .18f);
            }
            box(Surface.TIMBER, s * 1.15f, 6.3f, -25.54f, .68f, 2.5f, .12f);
            for (int i = 0; i < 8; i++) box(Surface.WOOD, s * 1.15f, 5.3f + i * .28f,
                    -25.43f, .6f, .12f, .16f);
            box(Surface.PAVING_LIGHT, s * 1.15f, 5.0f, -25.38f, .9f, .18f, .4f);
        }
        for (int s : new int[]{-1, 1}) {
            box(Surface.TIMBER, s * 2.1f, 10.7f, -25.96f, .2f, 3.2f, .2f);
            box(Surface.TIMBER, s * 2.1f, 10.7f, -30, .2f, 3.2f, .2f);
        }
        // Octagonal spire and a backlit clock with real hands and hour markers.
        column(Surface.ROOF_BLUE, 0, 12.5f, -28, 3.65f, 0, 4.7f, 4);
        column(Surface.METAL, 0, 17.15f, -28, .09f, .035f, 1.1f, 6);
        for (int i = 0; i < 32; i++) {
            double a = i * Math.PI / 16, b = (i + 1) * Math.PI / 16;
            triangle(Surface.GLOW, v(0, 10.85f, -25.88f),
                    v((float) Math.cos(a), 10.85f + (float) Math.sin(a), -25.88f),
                    v((float) Math.cos(b), 10.85f + (float) Math.sin(b), -25.88f));
        }
        for (int i = 0; i < 12; i++) {
            double a = i * Math.PI / 6;
            box(Surface.TIMBER, (float) Math.sin(a) * .84f,
                    10.85f + (float) Math.cos(a) * .84f, -25.82f, .08f, .13f, .05f);
        }
        beam(Surface.TIMBER, v(0, 10.85f, -25.77f), v(-.5f, 11.1f, -25.77f), .09f);
        beam(Surface.TIMBER, v(0, 10.85f, -25.76f), v(.18f, 11.58f, -25.76f), .065f);
        box(Surface.TIMBER, 0, 1.9f, -25.52f, 1.8f, 3.6f, .15f);
        for (int i = 0; i < 7; i++) box(Surface.WOOD, -.72f + i * .24f, 1.9f, -25.4f, .21f, 3.4f, .1f);
    }

    private void tree(float x, float z, float scale) {
        column(Surface.STONE, x, .1f, z, 1.0f, 1.0f, .45f, 8);
        column(Surface.TIMBER, x, .5f, z, .24f, .13f, 3.2f * scale, 7);
        for (int i = 0; i < 3; i++) column(i % 2 == 0 ? Surface.LEAF : Surface.LEAF_LIGHT,
                x, 1.8f * scale + i * 1.05f * scale, z,
                (2.0f - i * .48f) * scale, .1f, 2.4f * scale, 7);
    }

    private void bench(float x, float z) {
        for (int i = 0; i < 4; i++) box(Surface.WOOD, x, .65f, z - .3f + i * .19f, 2.1f, .12f, .16f);
        for (int s : new int[]{-1, 1}) box(Surface.METAL, x + s * .75f, .38f, z, .12f, .7f, .65f);
        for (int i = 0; i < 3; i++) box(Surface.WOOD, x, .95f + i * .18f, z - .38f, 2.1f, .14f, .1f);
    }

    /** All dimensions are full extents, in world units. */
    private void box(Surface s, float x, float y, float z, float width, float height, float depth) {
        float a = width / 2, b = height / 2, c = depth / 2;
        Vector3f p0 = v(x-a,y-b,z+c), p1 = v(x+a,y-b,z+c), p2 = v(x+a,y+b,z+c), p3 = v(x-a,y+b,z+c);
        Vector3f p4 = v(x-a,y-b,z-c), p5 = v(x+a,y-b,z-c), p6 = v(x+a,y+b,z-c), p7 = v(x-a,y+b,z-c);
        quad(s,p0,p1,p2,p3); quad(s,p5,p4,p7,p6); quad(s,p1,p5,p6,p2);
        quad(s,p4,p0,p3,p7); quad(s,p3,p2,p6,p7); quad(s,p4,p5,p1,p0);
    }

    private void column(Surface s, float x, float y, float z, float r0, float r1, float height, int sides) {
        for (int i = 0; i < sides; i++) {
            double a = i * Math.PI * 2 / sides, b = (i + 1) * Math.PI * 2 / sides;
            Vector3f p = v(x + (float)Math.cos(a)*r0,y,z+(float)Math.sin(a)*r0);
            Vector3f q = v(x + (float)Math.cos(b)*r0,y,z+(float)Math.sin(b)*r0);
            Vector3f r = v(x + (float)Math.cos(b)*r1,y+height,z+(float)Math.sin(b)*r1);
            Vector3f t = v(x + (float)Math.cos(a)*r1,y+height,z+(float)Math.sin(a)*r1);
            quad(s,q,p,t,r);
            triangle(s,v(x,y,z),p,q); triangle(s,v(x,y+height,z),r,t);
        }
    }

    private void beam(Surface s, Vector3f a, Vector3f b, float thickness) {
        Vector3f axis = new Vector3f(b).sub(a).normalize();
        Vector3f right = new Vector3f(axis).cross(Math.abs(axis.y) > .95f ? v(1,0,0) : v(0,1,0))
                .normalize().mul(thickness / 2);
        Vector3f up = new Vector3f(axis).cross(right).normalize().mul(thickness / 2);
        Vector3f[] p = new Vector3f[8];
        for (int end = 0; end < 2; end++) for (int i = 0; i < 4; i++) {
            p[end*4+i] = new Vector3f(end == 0 ? a : b)
                    .fma(i == 0 || i == 3 ? -1 : 1, right).fma(i < 2 ? -1 : 1, up);
        }
        quad(s,p[3],p[2],p[1],p[0]); quad(s,p[4],p[5],p[6],p[7]);
        for (int i = 0; i < 4; i++) quad(s,p[i],p[(i+1)%4],p[(i+1)%4+4],p[i+4]);
    }

    private void quad(Surface s, Vector3f a, Vector3f b, Vector3f c, Vector3f d) {
        triangle(s,a,b,c); triangle(s,a,c,d);
    }

    private void triangle(Surface s, Vector3f a, Vector3f b, Vector3f c) {
        a = pose.transformPosition(new Vector3f(a));
        b = pose.transformPosition(new Vector3f(b));
        c = pose.transformPosition(new Vector3f(c));
        Vector3f normal = new Vector3f(b).sub(a).cross(new Vector3f(c).sub(a));
        if (normal.lengthSquared() < 1e-12f) return;
        normal.normalize();
        Vertices vertices = batches.computeIfAbsent(s, ignored -> new Vertices());
        vertices.add(a, normal, 0, 0); vertices.add(b, normal, 1, 0); vertices.add(c, normal, 1, 1);
    }

    private Vector3f point(float x, float y, float z) { return pose.transformPosition(v(x,y,z)); }
    private static Vector3f v(float x, float y, float z) { return new Vector3f(x,y,z); }

    private void upload(Scene scene, ShaderProgram shader, PbrFallbackTextures fallbacks,
                        List<Material> materials, List<Mesh> meshes, boolean grayModel) {
        for (Map.Entry<Surface, Vertices> entry : batches.entrySet()) {
            Surface s = entry.getKey();
            Mesh mesh = Mesh.from(TangentGenerator.generate(MeshData.of("town-" + s.name().toLowerCase(java.util.Locale.ROOT),
                    entry.getValue().array(), BuiltinMeshData.positionUvLayout())).mesh());
            meshes.add(mesh);
            Material material = PbrMaterials.create(shader, new PbrMaterialProperties(
                    grayModel ? new Vector4f(.5f, .5f, .5f, 1) : new Vector4f(s.r, s.g, s.b, 1),
                    grayModel ? 0 : s == Surface.METAL ? .6f : 0,
                    grayModel ? 1 : s == Surface.WATER ? .2f : .8f, 1, 1,
                    grayModel ? new Vector3f() : new Vector3f(s.r,s.g,s.b).mul(s.emission),
                    Map.of()), Map.of(), fallbacks);
            materials.add(material);
            MeshRenderer renderer = MeshRenderer.of(mesh, material, Transform.identity());
            scene.add(s.emission > 0 ? renderer.withoutShadows() : renderer);
        }
    }

    private static final class Vertices {
        private float[] data = new float[4096];
        private int size;
        void add(Vector3f p, Vector3f n, float u, float v) {
            if (size + 8 > data.length) data = Arrays.copyOf(data, data.length * 2);
            data[size++] = p.x; data[size++] = p.y; data[size++] = p.z;
            data[size++] = u; data[size++] = v;
            data[size++] = n.x; data[size++] = n.y; data[size++] = n.z;
        }
        float[] array() { return Arrays.copyOf(data, size); }
    }
}
