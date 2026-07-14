package com.kaleblangley.haikalat.core.command;

import java.util.Arrays;

/** 可复用的 SoA 命令存储；记录纯基础类型命令时不分配对象。 */
final class CommandStream {
    private byte[] opcodes = new byte[64];
    private int[] integers = new int[256];
    private long[] longs = new long[32];
    private Object[] objects = new Object[64];
    private int commandCount;
    private int integerCount;
    private int longCount;
    private int objectCount;

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

    void reset() {
        Arrays.fill(objects, 0, objectCount, null);
        commandCount = 0;
        integerCount = 0;
        longCount = 0;
        objectCount = 0;
    }
}
