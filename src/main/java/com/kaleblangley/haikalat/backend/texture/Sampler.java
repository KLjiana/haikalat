package com.kaleblangley.haikalat.backend.texture;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlResource;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL12.GL_TEXTURE_WRAP_R;
import static org.lwjgl.opengl.GL33.glDeleteSamplers;
import static org.lwjgl.opengl.GL33.glGenSamplers;
import static org.lwjgl.opengl.GL33.glSamplerParameteri;

public final class Sampler implements GlResource {
    private final int id;
    private final long resourceSequence;
    private boolean closed;

    private Sampler(int id) {
        this.id = id;
        resourceSequence = GlDebug.trackResource("SAMPLER", id, "Sampler", 0L);
    }

    private Sampler(Descriptor descriptor) {
        this.id = glGenSamplers();
        resourceSequence = GlDebug.trackResource("SAMPLER", id, "Sampler", 0L);
        glSamplerParameteri(id, GL_TEXTURE_MIN_FILTER, descriptor.minFilter);
        glSamplerParameteri(id, GL_TEXTURE_MAG_FILTER, descriptor.magFilter);
        glSamplerParameteri(id, GL_TEXTURE_WRAP_S, descriptor.wrapS);
        glSamplerParameteri(id, GL_TEXTURE_WRAP_T, descriptor.wrapT);
        glSamplerParameteri(id, GL_TEXTURE_WRAP_R, descriptor.wrapR);
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
        GlDebug.closeResource(resourceSequence);
        closed = true;
    }

    public void ensureOpen() {
        if (closed) {
            throw new GlException("Sampler is closed");
        }
    }

    public record Descriptor(int minFilter, int magFilter, int wrapS, int wrapT, int wrapR) {
        public Descriptor(int minFilter, int magFilter, int wrapS, int wrapT) {
            this(minFilter, magFilter, wrapS, wrapT, wrapT);
        }

        public static Descriptor linearRepeat() {
            return new Descriptor(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_REPEAT, GL_REPEAT, GL_REPEAT);
        }

        public static Descriptor nearestRepeat() {
            return new Descriptor(GL_NEAREST, GL_NEAREST, GL_REPEAT, GL_REPEAT, GL_REPEAT);
        }
    }
}
