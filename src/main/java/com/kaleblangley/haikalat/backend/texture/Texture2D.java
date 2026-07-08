package com.kaleblangley.haikalat.backend.texture;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.GlFormats;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.core.device.TextureDescriptor;
import com.kaleblangley.haikalat.util.DirectBuffers;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGB;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;

public final class Texture2D implements GlResource {
    private final int id;
    private final int width;
    private final int height;
    private final int format;
    private boolean closed;

    private Texture2D(int id, int width, int height, int format) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.format = format;
        GlDebug.labelObject(org.lwjgl.opengl.GL43.GL_TEXTURE, id, "Texture2D " + width + "x" + height);
    }

    /**
     * 从类路径资源加载纹理，默认垂直翻转。
     *
     * @param anchor       用于定位资源的类
     * @param resourcePath 资源文件路径
     * @return 加载的 Texture2D 实例
     */
    public static Texture2D fromResource(Class<?> anchor, String resourcePath) {
        return fromResource(anchor, resourcePath, true);
    }

    /**
     * 从类路径资源加载纹理。
     *
     * @param anchor         用于定位资源的类
     * @param resourcePath   资源文件路径
     * @param flipVertically 是否垂直翻转
     * @return 加载的 Texture2D 实例
     */
    public static Texture2D fromResource(Class<?> anchor, String resourcePath, boolean flipVertically) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");

        STBImage.stbi_set_flip_vertically_on_load(flipVertically);
        byte[] bytes = DirectBuffers.readResourceBytes(anchor, resourcePath);
        ByteBuffer data = DirectBuffers.copyOf(bytes);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer widthBuffer = stack.mallocInt(1);
            IntBuffer heightBuffer = stack.mallocInt(1);
            IntBuffer channelsBuffer = stack.mallocInt(1);
            ByteBuffer image = STBImage.stbi_load_from_memory(data, widthBuffer, heightBuffer, channelsBuffer, 0);
            if (image == null) {
                throw new GlException("Failed to load image: " + STBImage.stbi_failure_reason());
            }

            int width = widthBuffer.get(0);
            int height = heightBuffer.get(0);
            int channels = channelsBuffer.get(0);
            int format = formatFromChannels(channels);

            int textureId = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureId);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTexImage2D(GL_TEXTURE_2D, 0, format, width, height, 0, format, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, image);
            glGenerateMipmap(GL_TEXTURE_2D);
            STBImage.stbi_image_free(image);
            return new Texture2D(textureId, width, height, format);
        }
    }

    public static Texture2D fromDescriptor(TextureDescriptor descriptor) {
        Objects.requireNonNull(descriptor, "descriptor");
        int textureId = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, textureId);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, descriptor.mipmapped() ? GL_LINEAR_MIPMAP_LINEAR : GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        int format = GlFormats.toGl(descriptor.format());
        glTexImage2D(GL_TEXTURE_2D, 0, format, descriptor.width(), descriptor.height(), 0,
                GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, 0L);
        if (descriptor.mipmapped()) {
            glGenerateMipmap(GL_TEXTURE_2D);
        }
        return new Texture2D(textureId, descriptor.width(), descriptor.height(), format);
    }

    /**
     * 在指定纹理单元上绑定此纹理。
     *
     * @param unit 纹理单元索引
     * @return 自身，支持链式调用
     */
    public Texture2D bind(int unit) {
        ensureOpen();
        glActiveTexture(GL_TEXTURE0 + unit);
        glBindTexture(GL_TEXTURE_2D, id);
        return this;
    }

    /** 解绑当前 2D 纹理。 */
    public Texture2D unbind() {
        glBindTexture(GL_TEXTURE_2D, 0);
        return this;
    }

    /**
     * 设置纹理的 wrap 模式。
     *
     * @param wrapS S 轴 wrap 模式
     * @param wrapT T 轴 wrap 模式
     * @return 自身，支持链式调用
     */
    public Texture2D setWrap(int wrapS, int wrapT) {
        ensureOpen();
        bind(0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, wrapS);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, wrapT);
        return this;
    }

    /**
     * 设置纹理的过滤模式。
     *
     * @param minFilter 缩小过滤模式
     * @param magFilter 放大过滤模式
     * @return 自身，支持链式调用
     */
    public Texture2D setFiltering(int minFilter, int magFilter) {
        ensureOpen();
        bind(0);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, minFilter);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, magFilter);
        return this;
    }

    /** 恢复为默认的线性 Mipmap 过滤模式。 */
    public Texture2D setDefaultFiltering() {
        return setFiltering(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR);
    }

    @Override
    public int id() {
        return id;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int format() {
        return format;
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
        glDeleteTextures(id);
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new GlException("Texture is closed");
        }
    }

    private static int formatFromChannels(int channels) {
        return switch (channels) {
            case 1 -> GL_RED;
            case 2 -> GL_RG;
            case 3 -> GL_RGB;
            case 4 -> GL_RGBA;
            default -> throw new GlException("Unsupported channel count: " + channels);
        };
    }
}
