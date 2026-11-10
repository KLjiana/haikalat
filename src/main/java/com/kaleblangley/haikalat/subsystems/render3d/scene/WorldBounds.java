package com.kaleblangley.haikalat.subsystems.render3d.scene;

/** Borrowed read-only view of a world-space AABB slot. */
public final class WorldBounds {
    private boolean unbounded;
    private float minX;
    private float minY;
    private float minZ;
    private float maxX;
    private float maxY;
    private float maxZ;

    private WorldBounds() {}

    public boolean unbounded() { return unbounded; }
    public float minX() { return minX; }
    public float minY() { return minY; }
    public float minZ() { return minZ; }
    public float maxX() { return maxX; }
    public float maxY() { return maxY; }
    public float maxZ() { return maxZ; }

    /** Write capability kept by the scene arena or the instance batch. */
    public static final class Scratch {
        private final WorldBounds bounds = new WorldBounds();

        public WorldBounds view() { return bounds; }

        public void setUnbounded() {
            set(0, 0, 0, 0, 0, 0);
            bounds.unbounded = true;
        }

        public void set(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            bounds.unbounded = false;
            bounds.minX = minX;
            bounds.minY = minY;
            bounds.minZ = minZ;
            bounds.maxX = maxX;
            bounds.maxY = maxY;
            bounds.maxZ = maxZ;
        }

        void copyFrom(Scratch source) {
            WorldBounds view = source.view();
            if (view.unbounded) setUnbounded();
            else set(view.minX, view.minY, view.minZ, view.maxX, view.maxY, view.maxZ);
        }

        public void include(WorldBounds other) {
            if (bounds.unbounded || other.unbounded) {
                setUnbounded();
                return;
            }
            set(Math.min(bounds.minX, other.minX), Math.min(bounds.minY, other.minY), Math.min(bounds.minZ, other.minZ),
                    Math.max(bounds.maxX, other.maxX), Math.max(bounds.maxY, other.maxY), Math.max(bounds.maxZ, other.maxZ));
        }
    }
}
