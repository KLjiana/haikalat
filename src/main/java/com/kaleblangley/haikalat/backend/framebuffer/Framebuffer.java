package com.kaleblangley.haikalat.backend.framebuffer;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlResource;

import java.util.Arrays;

import static org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT;
import static org.lwjgl.opengl.GL13.GL_CLAMP_TO_BORDER;
import static org.lwjgl.opengl.GL11.GL_DEPTH_BUFFER_BIT;
import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_NEAREST;
import static org.lwjgl.opengl.GL11.GL_NONE;
import static org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_BORDER_COLOR;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glClear;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glDrawBuffer;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glReadBuffer;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL11.glTexParameterfv;
import static org.lwjgl.opengl.GL11.glViewport;
import static org.lwjgl.opengl.GL20.glDrawBuffers;
import static org.lwjgl.opengl.GL30.GL_COLOR_ATTACHMENT0;
import static org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_FRAMEBUFFER_COMPLETE;
import static org.lwjgl.opengl.GL30.GL_READ_FRAMEBUFFER;
import static org.lwjgl.opengl.GL30.GL_RENDERBUFFER;
import static org.lwjgl.opengl.GL30.GL_RGBA8;
import static org.lwjgl.opengl.GL30.glBindFramebuffer;
import static org.lwjgl.opengl.GL30.glBindRenderbuffer;
import static org.lwjgl.opengl.GL30.glBlitFramebuffer;
import static org.lwjgl.opengl.GL30.glCheckFramebufferStatus;
import static org.lwjgl.opengl.GL30.glDeleteFramebuffers;
import static org.lwjgl.opengl.GL30.glDeleteRenderbuffers;
import static org.lwjgl.opengl.GL30.glFramebufferRenderbuffer;
import static org.lwjgl.opengl.GL30.glFramebufferTexture2D;
import static org.lwjgl.opengl.GL30.glGenFramebuffers;
import static org.lwjgl.opengl.GL30.glGenRenderbuffers;
import static org.lwjgl.opengl.GL30.glRenderbufferStorage;
import static org.lwjgl.opengl.GL30.glRenderbufferStorageMultisample;

import static org.lwjgl.opengl.GL32.GL_TEXTURE_2D_MULTISAMPLE;
import static org.lwjgl.opengl.GL32.glTexImage2DMultisample;

public final class Framebuffer implements GlResource {
    private final int id;
    private final int[] colorAttachments;
    private final FramebufferDescriptor.AttachmentStorage[] colorAttachmentStorage;
    private final int depthAttachment;
    private final FramebufferDescriptor.AttachmentStorage depthAttachmentStorage;
    private final boolean ownsDepthAttachment;
    private final boolean ownsColorAttachments;
    private final FramebufferDescriptor descriptor;
    private final long resourceSequence;
    private final long[] colorResourceSequences;
    private final long depthResourceSequence;
    private boolean closed;

    public Framebuffer(int id, int colorAttachment, int depthAttachment, int width, int height,
                       int samples, boolean multisampled) {
        this(id, new int[]{colorAttachment}, depthAttachment, width, height, samples, multisampled);
    }

    public Framebuffer(int id, int[] colorAttachments, int depthAttachment, int width, int height,
                       int samples, boolean multisampled) {
        this(
                id,
                colorAttachments,
                defaultColorStorage(colorAttachments.length, multisampled),
                depthAttachment,
                depthAttachment == 0 ? FramebufferDescriptor.AttachmentStorage.NONE
                        : FramebufferDescriptor.AttachmentStorage.RENDERBUFFER,
                true,
                legacyDescriptor(width, height, samples, multisampled, colorAttachments.length, depthAttachment != 0)
        );
    }

    private Framebuffer(int id, int[] colorAttachments,
                        FramebufferDescriptor.AttachmentStorage[] colorAttachmentStorage,
                        int depthAttachment,
                        FramebufferDescriptor.AttachmentStorage depthAttachmentStorage,
                        boolean ownsDepthAttachment,
                        FramebufferDescriptor descriptor) {
        this(id, colorAttachments, colorAttachmentStorage, depthAttachment,
                depthAttachmentStorage, ownsDepthAttachment, true, descriptor);
    }

    private Framebuffer(int id, int[] colorAttachments,
                        FramebufferDescriptor.AttachmentStorage[] colorAttachmentStorage,
                        int depthAttachment, FramebufferDescriptor.AttachmentStorage depthAttachmentStorage,
                        boolean ownsDepthAttachment, boolean ownsColorAttachments,
                        FramebufferDescriptor descriptor) {
        this.id = id;
        this.colorAttachments = colorAttachments.clone();
        this.colorAttachmentStorage = colorAttachmentStorage.clone();
        this.depthAttachment = depthAttachment;
        this.depthAttachmentStorage = depthAttachmentStorage;
        this.ownsDepthAttachment = ownsDepthAttachment;
        this.ownsColorAttachments = ownsColorAttachments;
        this.descriptor = descriptor;
        resourceSequence = GlDebug.trackResource("FRAMEBUFFER", id,
                "Framebuffer " + descriptor.width() + "x" + descriptor.height(), 0L);
        colorResourceSequences = new long[colorAttachments.length];
        long pixels = (long) descriptor.width() * descriptor.height() * Math.max(1, descriptor.samples());
        for (int index = 0; index < colorAttachments.length; index++) {
            if (!ownsColorAttachments) { colorResourceSequences[index] = -1L; continue; }
            colorResourceSequences[index] = GlDebug.trackResource(
                    colorAttachmentStorage[index] != FramebufferDescriptor.AttachmentStorage.RENDERBUFFER
                            ? "TEXTURE" : "RENDERBUFFER",
                    colorAttachments[index], "Framebuffer color[" + index + "]",
                    pixels * attachmentBytes(descriptor.colorAttachments().get(index).internalFormat()));
        }
        depthResourceSequence = depthAttachment == 0 || !ownsDepthAttachment ? -1L
                : GlDebug.trackResource(
                depthAttachmentStorage != FramebufferDescriptor.AttachmentStorage.RENDERBUFFER
                        ? "TEXTURE" : "RENDERBUFFER", depthAttachment,
                "Framebuffer depth", pixels * 4L);
    }

    private static int attachmentBytes(int format) {
        return switch (format) {
            case org.lwjgl.opengl.GL30.GL_R8 -> 1;
            case org.lwjgl.opengl.GL30.GL_R16F -> 2;
            case org.lwjgl.opengl.GL30.GL_RGBA16F, org.lwjgl.opengl.GL30.GL_RG32F -> 8;
            case org.lwjgl.opengl.GL30.GL_RGBA32F -> 16;
            default -> 4;
        };
    }

    public static Framebuffer singleSampled(int width, int height) {
        return fromDescriptor(FramebufferDescriptor.singleColorDepthRenderbuffer(width, height));
    }

    public static Framebuffer colorOnly(int width, int height) {
        return fromDescriptor(FramebufferDescriptor.colorOnly(width, height, GL_RGBA8));
    }

    public static Framebuffer withDepthTexture(int width, int height) {
        return fromDescriptor(FramebufferDescriptor.builder(width, height)
                .colorTexture(GL_RGBA8)
                .depthTexture()
                .build());
    }

    public static Framebuffer multiSampled(int width, int height, int samples) {
        if (samples < 2) {
            throw new IllegalArgumentException("samples must be >= 2");
        }
        return fromDescriptor(FramebufferDescriptor.multisampledColorDepthRenderbuffer(width, height, samples));
    }

    public static Framebuffer mrt(int width, int height, int... colorFormats) {
        return fromDescriptor(FramebufferDescriptor.mrt(width, height, colorFormats));
    }

    public static Framebuffer fromDescriptor(FramebufferDescriptor descriptor) {
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        GlDebug.labelObject(org.lwjgl.opengl.GL43.GL_FRAMEBUFFER, fbo,
                "Framebuffer " + descriptor.width() + "x" + descriptor.height());

        int[] colors = new int[descriptor.colorAttachments().size()];
        FramebufferDescriptor.AttachmentStorage[] colorStorage =
                new FramebufferDescriptor.AttachmentStorage[colors.length];
        int depth = 0;
        try {
        for (int i = 0; i < colors.length; i++) {
            FramebufferDescriptor.ColorAttachment color = descriptor.colorAttachments().get(i);
            int attachmentPoint = GL_COLOR_ATTACHMENT0 + i;
            colors[i] = createColorAttachment(descriptor, color, attachmentPoint);
            colorStorage[i] = color.storage();
            labelAttachment(colors[i], color.storage(), "Framebuffer color[" + i + "]");
        }

        depth = createDepthAttachment(descriptor);
        labelAttachment(depth, descriptor.depthAttachment().storage(), "Framebuffer depth");

        configureDrawBuffers(colors.length);
        ensureComplete();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new Framebuffer(fbo, colors, colorStorage, depth,
                descriptor.depthAttachment().storage(), true, descriptor);
        } catch (RuntimeException | Error failure) {
            for (int i=0;i<colors.length;i++) if (colors[i]!=0) deleteAttachment(colors[i],colorStorage[i]);
            if (depth!=0) deleteAttachment(depth,descriptor.depthAttachment().storage());
            glDeleteFramebuffers(fbo);
            throw failure;
        } finally { glBindFramebuffer(GL_FRAMEBUFFER,0); }
    }

    /**
     * Creates a framebuffer that owns its color attachments but borrows the
     * depth texture of {@code depthSource}.  The borrowed depth is never
     * deleted by this framebuffer and must outlive it.
     */
    public static Framebuffer fromDescriptorSharingDepth(FramebufferDescriptor descriptor,
                                                         Framebuffer depthSource) {
        return fromDescriptorSharingAttachments(descriptor, null, depthSource);
    }

    /** Borrows graph-owned textures without acquiring their storage ownership. */
    public static Framebuffer fromDescriptorSharingAttachments(FramebufferDescriptor descriptor,
                                                               Framebuffer colorSource, Framebuffer depthSource) {
        return fromDescriptorSharingAttachments(descriptor, colorSource, depthSource, null);
    }

    /** Borrows selected color textures in source order, with an optional shared depth texture. */
    public static Framebuffer fromDescriptorSharingAttachments(FramebufferDescriptor descriptor,
                                                               Framebuffer colorSource, Framebuffer depthSource,
                                                               java.util.List<Integer> colorIndices) {
        java.util.Objects.requireNonNull(descriptor, "descriptor");
        if (colorSource == null && depthSource == null)
            throw new IllegalArgumentException("a shared attachment source is required");
        if (depthSource == null && descriptor.depthAttachment().storage()
                != FramebufferDescriptor.AttachmentStorage.NONE)
            throw new IllegalArgumentException("color-only borrowing requires a descriptor without depth");
        if (colorIndices != null && colorSource == null)
            throw new IllegalArgumentException("color indices require a color source");
        if (depthSource != null) {
        depthSource.ensureOpen();
        if (!depthSource.depthAttachmentIsTexture() || depthSource.depthAttachment() == 0) {
            throw new IllegalArgumentException(
                    "shared depth source must expose a depth texture attachment");
        }
        if (depthSource.width() != descriptor.width() || depthSource.height() != descriptor.height()) {
            throw new IllegalArgumentException("shared depth extent "
                    + depthSource.width() + "x" + depthSource.height()
                    + " does not match framebuffer extent "
                    + descriptor.width() + "x" + descriptor.height());
        }
        if (depthSource.samples() != descriptor.samples()) {
            throw new IllegalArgumentException("shared depth sample count "
                    + depthSource.samples() + " does not match framebuffer samples "
                    + descriptor.samples());
        }
        }
        int count = descriptor.colorAttachments().size();
        int[] indices = new int[count];
        if (colorIndices != null && colorIndices.size() != count)
            throw new IllegalArgumentException("borrowed color index count must match the descriptor");
        java.util.Set<Integer> selected = new java.util.HashSet<>();
        for (int index = 0; index < count; index++) {
            indices[index] = colorIndices == null ? index
                    : java.util.Objects.requireNonNull(colorIndices.get(index), "color index");
            if (indices[index] < 0 || !selected.add(indices[index]))
                throw new IllegalArgumentException("borrowed color indices must be nonnegative and unique");
        }
        if (colorSource != null) {
            colorSource.ensureOpen();
            if (colorSource.width() != descriptor.width() || colorSource.height() != descriptor.height()
                    || colorSource.samples() != descriptor.samples()) {
                throw new IllegalArgumentException("borrowed color extent/samples must match");
            }
            for (int index = 0; index < count; index++) {
                int sourceIndex = indices[index];
                if (sourceIndex >= colorSource.colorAttachments.length
                        || !colorSource.colorAttachmentIsTexture(sourceIndex)
                        || colorSource.descriptor.colorAttachments().get(sourceIndex).internalFormat()
                        != descriptor.colorAttachments().get(index).internalFormat()) {
                    throw new IllegalArgumentException("borrowed color texture format must match");
                }
            }
        }
        int fbo = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
        GlDebug.labelObject(org.lwjgl.opengl.GL43.GL_FRAMEBUFFER, fbo,
                "Framebuffer " + descriptor.width() + "x" + descriptor.height() + " shared-attachments");

        int[] colors = new int[descriptor.colorAttachments().size()];
        FramebufferDescriptor.AttachmentStorage[] colorStorage =
                new FramebufferDescriptor.AttachmentStorage[colors.length];
        try {
        for (int i = 0; i < colors.length; i++) {
            FramebufferDescriptor.ColorAttachment color = descriptor.colorAttachments().get(i);
            if (colorSource == null) {
                colors[i] = createColorAttachment(descriptor, color, GL_COLOR_ATTACHMENT0 + i);
                colorStorage[i] = color.storage();
                labelAttachment(colors[i], color.storage(), "Framebuffer color[" + i + "]");
            } else {
                colors[i] = colorSource.colorAttachments[indices[i]];
                colorStorage[i] = colorSource.colorAttachmentStorage[indices[i]];
                glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0 + i,
                        descriptor.samples() > 1 ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D, colors[i], 0);
            }
        }
        if (depthSource != null) {
        int depthTarget = depthSource.descriptor().depthAttachment().storage()
                == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D_MULTISAMPLE
                ? GL_TEXTURE_2D_MULTISAMPLE : GL_TEXTURE_2D;
        glFramebufferTexture2D(GL_FRAMEBUFFER, org.lwjgl.opengl.GL30.GL_DEPTH_ATTACHMENT,
                depthTarget, depthSource.depthAttachment(), 0);
        }
        configureDrawBuffers(colors.length);
        ensureComplete();
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        return new Framebuffer(fbo, colors, colorStorage, depthSource == null ? 0 : depthSource.depthAttachment(),
                depthSource == null ? FramebufferDescriptor.AttachmentStorage.NONE : depthSource.depthAttachmentStorage,
                false, colorSource == null, descriptor);
        } catch (RuntimeException | Error failure) {
            if (colorSource==null) for (int i=0;i<colors.length;i++)
                if (colors[i]!=0) deleteAttachment(colors[i],colorStorage[i]);
            glDeleteFramebuffers(fbo);
            throw failure;
        } finally { glBindFramebuffer(GL_FRAMEBUFFER,0); }
    }

    public Framebuffer bind() {
        ensureOpen();
        glBindFramebuffer(GL_FRAMEBUFFER, id);
        glViewport(0, 0, width(), height());
        return this;
    }

    public Framebuffer unbind(int defaultWidth, int defaultHeight) {
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glViewport(0, 0, defaultWidth, defaultHeight);
        return this;
    }

    public Framebuffer clear(boolean color, boolean depth) {
        int mask = 0;
        if (color) {
            mask |= GL_COLOR_BUFFER_BIT;
        }
        if (depth) {
            mask |= GL_DEPTH_BUFFER_BIT;
        }
        glClear(mask);
        return this;
    }

    public Framebuffer blitToDefault(int targetWidth, int targetHeight) {
        ensureOpen();
        glBindFramebuffer(GL_READ_FRAMEBUFFER, id);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(0, 0, width(), height(), 0, 0, targetWidth, targetHeight,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return this;
    }

    public Framebuffer blitColorTo(Framebuffer target) {
        ensureOpen();
        target.ensureOpen();
        glBindFramebuffer(GL_READ_FRAMEBUFFER, id);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, target.id);
        glBlitFramebuffer(0, 0, width(), height(), 0, 0, target.width(), target.height(),
                GL_COLOR_BUFFER_BIT, GL_NEAREST);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        return this;
    }

    public int colorAttachment(int index) {
        ensureOpen();
        if (index < 0 || index >= colorAttachments.length) {
            throw new IndexOutOfBoundsException("Color attachment index out of range: " + index);
        }
        return colorAttachments[index];
    }

    public int colorAttachment() {
        return colorAttachment(0);
    }

    public int colorAttachmentCount() {
        return colorAttachments.length;
    }

    public boolean colorAttachmentIsTexture(int index) {
        if (index < 0 || index >= colorAttachmentStorage.length) {
            throw new IndexOutOfBoundsException("Color attachment index out of range: " + index);
        }
        return colorAttachmentStorage[index] == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D
                || colorAttachmentStorage[index]
                == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D_MULTISAMPLE;
    }

    public int depthAttachment() {
        ensureOpen();
        return depthAttachment;
    }

    public boolean depthAttachmentIsTexture() {
        return depthAttachmentStorage == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D
                || depthAttachmentStorage
                == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D_MULTISAMPLE;
    }

    public FramebufferDescriptor descriptor() {
        return descriptor;
    }

    public int width() {
        return descriptor.width();
    }

    public int height() {
        return descriptor.height();
    }

    public int samples() {
        return descriptor.samples();
    }

    public boolean multisampled() {
        return descriptor.multisampled();
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
        for (int i = 0; i < colorAttachments.length; i++) {
            if (!ownsColorAttachments) continue;
            deleteAttachment(colorAttachments[i], colorAttachmentStorage[i]);
            GlDebug.closeResource(colorResourceSequences[i]);
        }
        if (ownsDepthAttachment) {
            deleteAttachment(depthAttachment, depthAttachmentStorage);
            GlDebug.closeResource(depthResourceSequence);
        }
        glDeleteFramebuffers(id);
        GlDebug.closeResource(resourceSequence);
        closed = true;
    }

    public void ensureOpen() {
        if (closed) {
            throw new GlException("Framebuffer is closed");
        }
    }

    private static int createColorAttachment(FramebufferDescriptor descriptor,
                                             FramebufferDescriptor.ColorAttachment attachment,
                                             int attachmentPoint) {
        if (attachment.storage() == FramebufferDescriptor.AttachmentStorage.RENDERBUFFER) {
            int rbo = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, rbo);
            if (descriptor.multisampled()) {
                glRenderbufferStorageMultisample(GL_RENDERBUFFER, descriptor.samples(),
                        attachment.internalFormat(), descriptor.width(), descriptor.height());
            } else {
                glRenderbufferStorage(GL_RENDERBUFFER, attachment.internalFormat(),
                        descriptor.width(), descriptor.height());
            }
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, attachmentPoint, GL_RENDERBUFFER, rbo);
            return rbo;
        }

        int texture = glGenTextures();
        if (attachment.storage() == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D_MULTISAMPLE) {
            glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, texture);
            glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, descriptor.samples(),
                    attachment.internalFormat(), descriptor.width(), descriptor.height(), true);
            glFramebufferTexture2D(GL_FRAMEBUFFER, attachmentPoint, GL_TEXTURE_2D_MULTISAMPLE, texture, 0);
            return texture;
        }
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        glTexImage2D(GL_TEXTURE_2D, 0, attachment.internalFormat(), descriptor.width(), descriptor.height(),
                0, attachment.externalFormat(), attachment.dataType(), 0L);
        glFramebufferTexture2D(GL_FRAMEBUFFER, attachmentPoint, GL_TEXTURE_2D, texture, 0);
        return texture;
    }

    private static int createDepthAttachment(FramebufferDescriptor descriptor) {
        FramebufferDescriptor.DepthAttachment depth = descriptor.depthAttachment();
        if (depth.storage() == FramebufferDescriptor.AttachmentStorage.NONE) {
            return 0;
        }
        if (depth.storage() == FramebufferDescriptor.AttachmentStorage.RENDERBUFFER) {
            int rbo = glGenRenderbuffers();
            glBindRenderbuffer(GL_RENDERBUFFER, rbo);
            if (descriptor.multisampled()) {
                glRenderbufferStorageMultisample(GL_RENDERBUFFER, descriptor.samples(),
                        depth.internalFormat(), descriptor.width(), descriptor.height());
            } else {
                glRenderbufferStorage(GL_RENDERBUFFER, depth.internalFormat(), descriptor.width(), descriptor.height());
            }
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, depth.attachmentPoint(), GL_RENDERBUFFER, rbo);
            return rbo;
        }

        int texture = glGenTextures();
        if (depth.storage() == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D_MULTISAMPLE) {
            glBindTexture(GL_TEXTURE_2D_MULTISAMPLE, texture);
            glTexImage2DMultisample(GL_TEXTURE_2D_MULTISAMPLE, descriptor.samples(),
                    depth.internalFormat(), descriptor.width(), descriptor.height(), true);
            glFramebufferTexture2D(GL_FRAMEBUFFER, depth.attachmentPoint(),
                    GL_TEXTURE_2D_MULTISAMPLE, texture, 0);
            return texture;
        }
        glBindTexture(GL_TEXTURE_2D, texture);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_BORDER);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_BORDER);
        glTexParameterfv(GL_TEXTURE_2D, GL_TEXTURE_BORDER_COLOR, new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        glTexImage2D(GL_TEXTURE_2D, 0, depth.internalFormat(), descriptor.width(), descriptor.height(),
                0, depth.externalFormat(), depth.dataType(), 0L);
        glFramebufferTexture2D(GL_FRAMEBUFFER, depth.attachmentPoint(), GL_TEXTURE_2D, texture, 0);
        return texture;
    }

    private static void configureDrawBuffers(int colorCount) {
        if (colorCount == 0) {
            glDrawBuffer(GL_NONE);
            glReadBuffer(GL_NONE);
            return;
        }
        if (colorCount == 1) {
            glDrawBuffer(GL_COLOR_ATTACHMENT0);
            glReadBuffer(GL_COLOR_ATTACHMENT0);
            return;
        }
        int[] drawBuffers = new int[colorCount];
        for (int i = 0; i < colorCount; i++) {
            drawBuffers[i] = GL_COLOR_ATTACHMENT0 + i;
        }
        glDrawBuffers(drawBuffers);
        glReadBuffer(GL_NONE);
    }

    private static void ensureComplete() {
        int status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (status != GL_FRAMEBUFFER_COMPLETE) {
            throw new GlException("Framebuffer incomplete: " + status);
        }
    }

    private static void deleteAttachment(int attachment, FramebufferDescriptor.AttachmentStorage storage) {
        if (attachment == 0 || storage == FramebufferDescriptor.AttachmentStorage.NONE) {
            return;
        }
        if (storage == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D
                || storage == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D_MULTISAMPLE) {
            glDeleteTextures(attachment);
        } else {
            glDeleteRenderbuffers(attachment);
        }
    }

    private static void labelAttachment(int attachment, FramebufferDescriptor.AttachmentStorage storage, String label) {
        if (storage == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D
                || storage == FramebufferDescriptor.AttachmentStorage.TEXTURE_2D_MULTISAMPLE) {
            GlDebug.labelObject(org.lwjgl.opengl.GL43.GL_TEXTURE, attachment, label);
        } else if (storage == FramebufferDescriptor.AttachmentStorage.RENDERBUFFER) {
            GlDebug.labelObject(org.lwjgl.opengl.GL43.GL_RENDERBUFFER, attachment, label);
        }
    }

    private static FramebufferDescriptor.AttachmentStorage[] defaultColorStorage(int count, boolean multisampled) {
        FramebufferDescriptor.AttachmentStorage storage = multisampled
                ? FramebufferDescriptor.AttachmentStorage.RENDERBUFFER
                : FramebufferDescriptor.AttachmentStorage.TEXTURE_2D;
        FramebufferDescriptor.AttachmentStorage[] result = new FramebufferDescriptor.AttachmentStorage[count];
        Arrays.fill(result, storage);
        return result;
    }

    private static FramebufferDescriptor legacyDescriptor(int width, int height, int samples, boolean multisampled,
                                                         int colorCount, boolean hasDepth) {
        FramebufferDescriptor.Builder builder = FramebufferDescriptor.builder(width, height).samples(samples);
        for (int i = 0; i < colorCount; i++) {
            if (multisampled) {
                builder.colorRenderbuffer(GL_RGBA8);
            } else {
                builder.colorTexture(GL_RGBA8);
            }
        }
        if (hasDepth) {
            builder.depthStencilRenderbuffer();
        }
        return builder.build();
    }
}
