package com.kaleblangley.haikalat.subsystems.render3d;

/** 对 primitive renderer index arena 执行稳定、无包装对象的 merge sort。 */
final class RenderQueueSorter {
    private RenderQueueSorter() {
    }

    static void forward(int[] indices, int count, int[] scratch,
                        int[] blend, int[] shader, int[] material, int[] mesh) {
        stableSortForward(indices, count, scratch, blend, shader, material, mesh);
    }

    static void shadow(int[] indices, int count, int[] scratch, int[] mesh) {
        stableSortShadow(indices, count, scratch, mesh);
    }

    private static int compareForward(int left, int right, int[] blend, int[] shader,
                                      int[] material, int[] mesh) {
        int compared = Integer.compare(blend[left], blend[right]);
        if (compared != 0) return compared;
        // Alpha 保持用户 insertion order。
        if (blend[left] == 2) return Integer.compare(left, right);
        compared = Integer.compare(shader[left], shader[right]);
        if (compared != 0) return compared;
        compared = Integer.compare(material[left], material[right]);
        if (compared != 0) return compared;
        compared = Integer.compare(mesh[left], mesh[right]);
        return compared != 0 ? compared : Integer.compare(left, right);
    }

    private static void stableSortForward(int[] values, int count, int[] scratch,
                                          int[] blend, int[] shader, int[] material, int[] mesh) {
        if (count < 2) return;
        int[] source = values;
        int[] destination = scratch;
        for (int width = 1; width < count; width = width > count / 2 ? count : width * 2) {
            for (int start = 0; start < count; start += width * 2) {
                int middle = Math.min(start + width, count);
                int end = Math.min(start + width * 2, count);
                int left = start;
                int right = middle;
                int output = start;
                while (left < middle && right < end) {
                    if (compareForward(source[left], source[right], blend, shader, material, mesh) <= 0) {
                        destination[output++] = source[left++];
                    } else {
                        destination[output++] = source[right++];
                    }
                }
                while (left < middle) destination[output++] = source[left++];
                while (right < end) destination[output++] = source[right++];
            }
            int[] swap = source;
            source = destination;
            destination = swap;
        }
        if (source != values) System.arraycopy(source, 0, values, 0, count);
    }

    private static void stableSortShadow(int[] values, int count, int[] scratch, int[] mesh) {
        if (count < 2) return;
        int[] source = values;
        int[] destination = scratch;
        for (int width = 1; width < count; width = width > count / 2 ? count : width * 2) {
            for (int start = 0; start < count; start += width * 2) {
                int middle = Math.min(start + width, count);
                int end = Math.min(start + width * 2, count);
                int left = start;
                int right = middle;
                int output = start;
                while (left < middle && right < end) {
                    int leftIndex = source[left];
                    int rightIndex = source[right];
                    int compared = Integer.compare(mesh[leftIndex], mesh[rightIndex]);
                    if (compared < 0 || compared == 0 && leftIndex <= rightIndex) {
                        destination[output++] = source[left++];
                    } else {
                        destination[output++] = source[right++];
                    }
                }
                while (left < middle) destination[output++] = source[left++];
                while (right < end) destination[output++] = source[right++];
            }
            int[] swap = source;
            source = destination;
            destination = swap;
        }
        if (source != values) System.arraycopy(source, 0, values, 0, count);
    }
}
