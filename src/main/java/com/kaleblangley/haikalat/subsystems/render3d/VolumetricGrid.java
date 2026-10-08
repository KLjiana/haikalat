package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

/** Stable finite-frustum coordinates. Owns no native resource and never reads a mutable camera. */
final class VolumetricGrid {
    private final int width, height, tilePixels, nx, ny, nz;
    private final float near, far;
    private final boolean perspective;
    private final Matrix4f inverseProjection, inverseView;
    private final float[] boundaries;

    VolumetricGrid(ExternalCamera camera, int width, int height, int tilePixels, int depthSlices, float fogDistance) {
        Objects.requireNonNull(camera, "camera");
        if (width <= 0 || height <= 0 || tilePixels <= 0 || depthSlices < 2 || depthSlices > 256) {
            throw new IllegalArgumentException("invalid volumetric grid extent/quality");
        }
        if (!Float.isFinite(fogDistance) || fogDistance <= camera.nearPlane() || fogDistance > 1024) {
            throw new IllegalArgumentException("fog distance must be finite, above near, and at most 1024");
        }
        this.width = width; this.height = height; this.tilePixels = tilePixels;
        nx = Math.toIntExact(((long)width + tilePixels - 1) / tilePixels);
        ny = Math.toIntExact(((long)height + tilePixels - 1) / tilePixels);
        nz = depthSlices;
        near = camera.nearPlane(); far = Math.min(fogDistance, camera.farPlane());
        Matrix4f projection = camera.projection();
        perspective = Math.abs(projection.m33()) < 0.00001f && Math.abs(projection.m23()+1) < 0.00001f;
        boolean orthographic = Math.abs(projection.m33()-1) < 0.00001f && Math.abs(projection.m23()) < 0.00001f;
        if ((!perspective && !orthographic) || projection.m00() <= 0 || projection.m11() <= 0
                || projection.m22() >= 0 || !zero(projection.m01(),projection.m10(),projection.m02(),projection.m12(),
                projection.m03(),projection.m13())
                || perspective && !zero(projection.m30(),projection.m31())
                || orthographic && !zero(projection.m20(),projection.m21())) {
            throw new IllegalArgumentException("volumetric fog requires a standard perspective or orthographic projection");
        }
        Matrix4f view = camera.view();
        Vector3f x = new Vector3f(view.m00(),view.m01(),view.m02());
        Vector3f y = new Vector3f(view.m10(),view.m11(),view.m12());
        Vector3f z = new Vector3f(view.m20(),view.m21(),view.m22());
        if (!zero(view.m03(),view.m13(),view.m23()) || Math.abs(view.m33()-1) > 0.0001f
                || Math.abs(x.lengthSquared()-1) > 0.0001f || Math.abs(y.lengthSquared()-1) > 0.0001f
                || Math.abs(z.lengthSquared()-1) > 0.0001f || Math.abs(x.dot(y)) > 0.0001f
                || Math.abs(x.dot(z)) > 0.0001f || Math.abs(y.dot(z)) > 0.0001f || view.determinant() <= 0) {
            throw new IllegalArgumentException("volumetric view transform must be rigid without scaling");
        }
        inverseProjection = projection.invert(new Matrix4f());
        inverseView = view.invert(new Matrix4f());
        boundaries = new float[nz+1];
        for (int index=1; index<=nz; index++) {
            boundaries[index] = perspective ? (float)(near * Math.pow((double)far/near,(double)(index-1)/(nz-1)))
                    : far * ((float)index/nz);
            if (!Float.isFinite(boundaries[index]) || boundaries[index] <= boundaries[index-1]) {
                throw new IllegalArgumentException("volumetric depth boundaries are not representable/distinct");
            }
        }
        boundaries[nz] = far;
    }

    int nx() { return nx; } int ny() { return ny; } int nz() { return nz; }
    int width() { return width; } int height() { return height; } int tilePixels() { return tilePixels; }
    float near() { return near; } float far() { return far; } boolean perspective() { return perspective; }
    float boundary(int index) { return boundaries[index]; }
    float center(int layer) {
        if (layer < 0 || layer >= nz) throw new IllegalArgumentException("invalid volume layer");
        return 0.5f*(boundaries[layer]+boundaries[layer+1]);
    }

    int interval(float depth) {
        if (!Float.isFinite(depth)) throw new IllegalArgumentException("query depth must be finite");
        if (depth <= 0) return 0;
        if (depth >= far) return nz-1;
        int low=0, high=nz;
        while (low+1<high) { int middle=(low+high)>>>1; if (boundaries[middle] <= depth) low=middle; else high=middle; }
        return low;
    }

    /** Layer coordinate for interpolation by physical sample-center depth, including the special near interval. */
    float historyLayer(float depth) {
        if (!Float.isFinite(depth) || depth < 0 || depth > far) throw new IllegalArgumentException("history point is outside the volume");
        if (depth <= center(0)) return 0;
        if (depth >= center(nz-1)) return nz-1;
        int low=0, high=nz-1;
        while (low+1<high) { int middle=(low+high)>>>1; if (center(middle) <= depth) low=middle; else high=middle; }
        return low + (depth-center(low))/(center(high)-center(low));
    }

    Vector3f viewRay(int columnX, int columnY, Vector3f destination) {
        Vector4f point = projectionPoint(columnX,columnY);
        if (!perspective) return destination.set(0,0,-1);
        return destination.set(point.x,point.y,point.z).normalize();
    }

    Vector3f worldSample(int columnX, int columnY, int layer, Vector3f destination) {
        Vector4f point = projectionPoint(columnX,columnY);
        float depth = center(layer);
        if (perspective) {
            float scale = depth / -point.z;
            destination.set(point.x*scale,point.y*scale,-depth);
        } else destination.set(point.x,point.y,-depth);
        return inverseView.transformPosition(destination);
    }

    float segmentLength(int columnX, int columnY, int layer, float endpoint) {
        if (!Float.isFinite(endpoint)) throw new IllegalArgumentException("query depth must be finite");
        if (layer < 0 || layer >= nz) throw new IllegalArgumentException("invalid volume layer");
        float dz = Math.max(0,Math.min(endpoint,boundaries[layer+1])-boundaries[layer]);
        return perspective ? dz / Math.abs(viewRay(columnX,columnY,new Vector3f()).z) : dz;
    }

    private Vector4f projectionPoint(int columnX, int columnY) {
        if (columnX < 0 || columnX >= nx || columnY < 0 || columnY >= ny) {
            throw new IllegalArgumentException("volume column is out of bounds");
        }
        float px=0.5f*((long)columnX*tilePixels + Math.min((long)(columnX+1)*tilePixels,width));
        float py=0.5f*((long)columnY*tilePixels + Math.min((long)(columnY+1)*tilePixels,height));
        Vector4f point = inverseProjection.transform(new Vector4f(2*px/width-1,2*py/height-1,-1,1));
        point.div(point.w);
        return point;
    }

    private static boolean zero(float... values) { for (float value : values) if (Math.abs(value)>0.00001f) return false; return true; }
}
