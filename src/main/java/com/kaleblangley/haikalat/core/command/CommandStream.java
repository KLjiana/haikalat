package com.kaleblangley.haikalat.core.command;

import com.kaleblangley.haikalat.backend.sync.GpuFenceTarget;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.Arrays;

/** 可复用的 SoA 命令存储；记录纯基础类型命令时不分配对象。 */
final class CommandStream {
    private byte[] opcodes = new byte[64];
    private int[] integers = new int[256];
    private long[] longs = new long[32];
    private Object[] objects = new Object[64];
    private float[] matrices = new float[256];
    private GpuFenceTarget[] gpuFenceTargets = new GpuFenceTarget[8];
    private int commandCount;
    private int integerCount;
    private int longCount;
    private int objectCount;
    private int matrixFloatCount;
    private int matrixCount;
    private int gpuFenceTargetCount;

    void opcode(byte opcode) {
        if (commandCount == opcodes.length) opcodes = Arrays.copyOf(opcodes, opcodes.length * 2);
        opcodes[commandCount++] = opcode;
    }

    void integer(int value) {
        if (integerCount == integers.length) integers = Arrays.copyOf(integers, integers.length * 2);
        integers[integerCount++] = value;
    }

    void longValue(long value) {
        if (longCount == longs.length) longs = Arrays.copyOf(longs, longs.length * 2);
        longs[longCount++] = value;
    }

    void object(Object value) {
        if (objectCount == objects.length) objects = Arrays.copyOf(objects, objects.length * 2);
        objects[objectCount++] = value;
    }

    /** 原子记录一条 primitive mat4 命令；所有容量就绪后才发布 opcode。 */
    void matrixCommand(byte opcode, int location, Object shader, Matrix4fc value) {
        float m00 = value.m00(), m01 = value.m01(), m02 = value.m02(), m03 = value.m03();
        float m10 = value.m10(), m11 = value.m11(), m12 = value.m12(), m13 = value.m13();
        float m20 = value.m20(), m21 = value.m21(), m22 = value.m22(), m23 = value.m23();
        float m30 = value.m30(), m31 = value.m31(), m32 = value.m32(), m33 = value.m33();
        int offset = matrixFloatCount;
        int required = Math.addExact(offset, 16);
        ensureOpcodeCapacity(Math.addExact(commandCount, 1));
        ensureIntegerCapacity(Math.addExact(integerCount, 2));
        ensureObjectCapacity(Math.addExact(objectCount, 1));
        ensureMatrixCapacity(required);
        integers[integerCount++] = location;
        integers[integerCount++] = offset;
        objects[objectCount++] = shader;
        matrices[offset] = m00; matrices[offset + 1] = m01;
        matrices[offset + 2] = m02; matrices[offset + 3] = m03;
        matrices[offset + 4] = m10; matrices[offset + 5] = m11;
        matrices[offset + 6] = m12; matrices[offset + 7] = m13;
        matrices[offset + 8] = m20; matrices[offset + 9] = m21;
        matrices[offset + 10] = m22; matrices[offset + 11] = m23;
        matrices[offset + 12] = m30; matrices[offset + 13] = m31;
        matrices[offset + 14] = m32; matrices[offset + 15] = m33;
        matrixFloatCount = required;
        matrixCount++;
        opcodes[commandCount++] = opcode;
    }

    void gpuFenceTarget(GpuFenceTarget value) {
        if (gpuFenceTargetCount == gpuFenceTargets.length) {
            gpuFenceTargets = Arrays.copyOf(gpuFenceTargets, gpuFenceTargets.length * 2);
        }
        gpuFenceTargets[gpuFenceTargetCount++] = value;
    }

    byte opcodeAt(int index) {
        return opcodes[index];
    }

    int integerAt(int index) {
        return integers[index];
    }

    long longAt(int index) {
        return longs[index];
    }

    Object objectAt(int index) {
        return objects[index];
    }

    int commandCount() {
        return commandCount;
    }

    int objectCount() {
        return objectCount;
    }

    int matrixCount() {
        return matrixCount;
    }

    Matrix4f loadMatrix(int offset, Matrix4f destination) {
        if (offset < 0 || offset > matrixFloatCount - 16 || (offset & 15) != 0) {
            throw new IllegalStateException("invalid matrix arena offset: " + offset);
        }
        return destination.set(
                matrices[offset], matrices[offset + 1], matrices[offset + 2], matrices[offset + 3],
                matrices[offset + 4], matrices[offset + 5], matrices[offset + 6], matrices[offset + 7],
                matrices[offset + 8], matrices[offset + 9], matrices[offset + 10], matrices[offset + 11],
                matrices[offset + 12], matrices[offset + 13], matrices[offset + 14], matrices[offset + 15]);
    }

    int gpuFenceTargetCount() {
        return gpuFenceTargetCount;
    }

    GpuFenceTarget gpuFenceTargetAt(int index) {
        return gpuFenceTargets[index];
    }

    void reset() {
        Arrays.fill(objects, 0, objectCount, null);
        Arrays.fill(gpuFenceTargets, 0, gpuFenceTargetCount, null);
        commandCount = 0;
        integerCount = 0;
        longCount = 0;
        objectCount = 0;
        matrixFloatCount = 0;
        matrixCount = 0;
        gpuFenceTargetCount = 0;
    }

    private void ensureOpcodeCapacity(int required) {
        if (required > opcodes.length) opcodes = Arrays.copyOf(opcodes, grown(opcodes.length, required));
    }

    private void ensureIntegerCapacity(int required) {
        if (required > integers.length) integers = Arrays.copyOf(integers, grown(integers.length, required));
    }

    private void ensureObjectCapacity(int required) {
        if (required > objects.length) objects = Arrays.copyOf(objects, grown(objects.length, required));
    }

    private void ensureMatrixCapacity(int required) {
        if (required > matrices.length) matrices = Arrays.copyOf(matrices, grown(matrices.length, required));
    }

    private static int grown(int current, int required) {
        int capacity = current;
        while (capacity < required) capacity = Math.multiplyExact(capacity, 2);
        return capacity;
    }
}
