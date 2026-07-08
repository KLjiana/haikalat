package com.kaleblangley.haikalat.backend.texture;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL33.glDeleteSamplers;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;

public final class Sampler implements GlResource {
    private final int id;
    private boolean closed;

    private Sampler(int id) {
        this.id = id;
    }

    private Sampler(Descriptor descriptor) {
        this.id = glGenSamplers();
        glSamplerParameteri(id, GL_TEXTURE_MIN_FILTER, descriptor.minFilter);
        glSamplerParameteri(id, GL_TEXTURE_MAG_FILTER, descriptor.magFilter);
        glSamplerParameteri(id, GL_TEXTURE_WRAP_S, descriptor.wrapS);
        glSamplerParameteri(id, GL_TEXTURE_WRAP_T, descriptor.wrapT);
    }

    public static Sampler create(Descriptor descriptor) {
        return new Sampler(descriptor);
    }

    public static Sampler linearRepeat() {
        return create(Descriptor.linearRepeat());
    }

    public static Sampler nearestRepeat() {
        return create(Descriptor.nearestRepeat());
    }

    @Override
    public int id() {
        return id;
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        glDeleteSamplers(id);
        closed = true;
    }

    public void ensureOpen() {
        if (closed) {
            throw new GlException("Sampler is closed");
        }
    }

    public record Descriptor(int minFilter, int magFilter, int wrapS, int wrapT) {
        public static Descriptor linearRepeat() {
            return new Descriptor(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT);
        }

        public static Descriptor nearestRepeat() {
            return new Descriptor(GL_NEAREST, GL_NEAREST, GL_REPEAT, GL_REPEAT);
        }
    }
}
