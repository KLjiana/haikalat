package com.kaleblangley.haikalat.subsystems.render3d;

/** SceneFrame arena 中复用的世界空间 AABB 槽。 */
final class WorldBounds {
    boolean unbounded;
    float minX;
    float minY;
    float minZ;
    float maxX;
    float maxY;
    float maxZ;

    void unbounded() {
        unbounded = true;
        minX = minY = minZ = maxX = maxY = maxZ = 0.0f;
    }

    void set(WorldBounds source) {
        unbounded = source.unbounded;
        minX = source.minX; minY = source.minY; minZ = source.minZ;
        maxX = source.maxX; maxY = source.maxY; maxZ = source.maxZ;
    }
}
