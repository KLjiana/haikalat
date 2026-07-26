package com.kaleblangley.haikalat.subsystems.animation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 使用调用方显式三角形的二维 Blend Tree definition。 */
public final class BlendTree2D implements AnimationMotion {
    private static final float AREA_EPSILON = 1.0e-7f;

    private final String xParameter;
    private final String yParameter;
    private final List<Child> children;
    private final List<Triangle> triangles;
    private final List<Edge> hullEdges;
    private final Skeleton skeleton;
    private final float durationSeconds;

    private BlendTree2D(String xParameter, String yParameter, List<Child> children,
                        List<Triangle> triangles) {
        this.xParameter = BlendTree1D.requireName(xParameter, "xParameter");
        this.yParameter = BlendTree1D.requireName(yParameter, "yParameter");
        if (this.xParameter.equals(this.yParameter)) {
            throw new IllegalArgumentException("BlendTree2D parameters must be different");
        }
        if (children.size() < 3) {
            throw new IllegalArgumentException("BlendTree2D requires at least three children");
        }
        if (triangles.isEmpty()) {
            throw new IllegalArgumentException("BlendTree2D requires at least one triangle");
        }
        Skeleton owner = children.getFirst().motion().skeleton();
        float duration = 0.0f;
        for (int index = 0; index < children.size(); index++) {
            Child child = children.get(index);
            if (!Float.isFinite(child.x()) || !Float.isFinite(child.y())) {
                throw new IllegalArgumentException("BlendTree2D child coordinates must be finite");
            }
            if (child.motion().skeleton() != owner) {
                throw new IllegalArgumentException("BlendTree2D children use different skeletons");
            }
            for (int previous = 0; previous < index; previous++) {
                Child other = children.get(previous);
                if (child.x() == other.x() && child.y() == other.y()) {
                    throw new IllegalArgumentException("BlendTree2D child coordinates must be unique");
                }
            }
            duration = Math.max(duration, child.motion().durationSeconds());
        }

        boolean[] referenced = new boolean[children.size()];
        Map<Edge, Integer> edgeCounts = new HashMap<>();
        for (Triangle triangle : triangles) {
            validateChildIndex(triangle.a(), children.size());
            validateChildIndex(triangle.b(), children.size());
            validateChildIndex(triangle.c(), children.size());
            if (triangle.a() == triangle.b() || triangle.b() == triangle.c()
                    || triangle.c() == triangle.a()) {
                throw new IllegalArgumentException("BlendTree2D triangle indices must be unique");
            }
            float area = signedArea(children.get(triangle.a()), children.get(triangle.b()),
                    children.get(triangle.c()));
            if (!Float.isFinite(area) || Math.abs(area) <= AREA_EPSILON) {
                throw new IllegalArgumentException("BlendTree2D triangle area must be non-zero");
            }
            referenced[triangle.a()] = referenced[triangle.b()] = referenced[triangle.c()] = true;
            increment(edgeCounts, new Edge(triangle.a(), triangle.b()));
            increment(edgeCounts, new Edge(triangle.b(), triangle.c()));
            increment(edgeCounts, new Edge(triangle.c(), triangle.a()));
        }
        for (int index = 0; index < referenced.length; index++) {
            if (!referenced[index]) {
                throw new IllegalArgumentException("BlendTree2D child[" + index + "] is isolated");
            }
        }
        this.children = List.copyOf(children);
        this.triangles = List.copyOf(triangles);
        hullEdges = edgeCounts.entrySet().stream().filter(entry -> entry.getValue() == 1)
                .map(Map.Entry::getKey).toList();
        if (hullEdges.isEmpty()) {
            throw new IllegalArgumentException("BlendTree2D has no hull edges");
        }
        skeleton = owner;
        durationSeconds = duration;
    }

    public static Builder builder(String xParameter, String yParameter) {
        return new Builder(xParameter, yParameter);
    }

    public String xParameter() {
        return xParameter;
    }

    public String yParameter() {
        return yParameter;
    }

    public List<Child> children() {
        return children;
    }

    public List<Triangle> triangles() {
        return triangles;
    }

    @Override
    public Skeleton skeleton() {
        return skeleton;
    }

    @Override
    public float durationSeconds() {
        return durationSeconds;
    }

    List<Edge> hullEdges() {
        return hullEdges;
    }

    static float signedArea(Child first, Child second, Child third) {
        return (second.x() - first.x()) * (third.y() - first.y())
                - (second.y() - first.y()) * (third.x() - first.x());
    }

    public record Child(float x, float y, AnimationMotion motion) {
        public Child {
            motion = Objects.requireNonNull(motion, "motion");
        }
    }

    public record Triangle(int a, int b, int c) {
    }

    record Edge(int first, int second) {
        Edge {
            if (first > second) {
                int swap = first;
                first = second;
                second = swap;
            }
        }
    }

    public static final class Builder {
        private final String xParameter;
        private final String yParameter;
        private final List<Child> children = new ArrayList<>();
        private final List<Triangle> triangles = new ArrayList<>();

        private Builder(String xParameter, String yParameter) {
            this.xParameter = BlendTree1D.requireName(xParameter, "xParameter");
            this.yParameter = BlendTree1D.requireName(yParameter, "yParameter");
        }

        public Builder child(float x, float y, AnimationMotion motion) {
            children.add(new Child(x, y, motion));
            return this;
        }

        public Builder triangle(int a, int b, int c) {
            triangles.add(new Triangle(a, b, c));
            return this;
        }

        public BlendTree2D build() {
            return new BlendTree2D(xParameter, yParameter, children, triangles);
        }
    }

    private static void validateChildIndex(int index, int size) {
        if (index < 0 || index >= size) {
            throw new IllegalArgumentException("BlendTree2D triangle child index out of range: "
                    + index);
        }
    }

    private static void increment(Map<Edge, Integer> counts, Edge edge) {
        counts.merge(edge, 1, Integer::sum);
    }
}
