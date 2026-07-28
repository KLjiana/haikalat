package com.kaleblangley.haikalat.backend.texture;

import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.GlResource;
import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.util.DirectBuffers;
import org.lwjgl.stb.STBImage;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.Objects;

import static org.lwjgl.opengl.GL11.GL_LINEAR;
import static org.lwjgl.opengl.GL11.GL_FLOAT;
import static org.lwjgl.opengl.GL11.GL_LINEAR_MIPMAP_LINEAR;
import static org.lwjgl.opengl.GL11.GL_RED;
import static org.lwjgl.opengl.GL11.GL_RGB;
import static org.lwjgl.opengl.GL11.GL_RGB8;
import static org.lwjgl.opengl.GL11.GL_RGBA;
import static org.lwjgl.opengl.GL11.GL_RGBA8;
import static org.lwjgl.opengl.GL11.GL_REPEAT;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_2D;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MAG_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_MIN_FILTER;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_S;
import static org.lwjgl.opengl.GL11.GL_TEXTURE_WRAP_T;
import static org.lwjgl.opengl.GL11.GL_UNPACK_ALIGNMENT;
import static org.lwjgl.opengl.GL11.GL_UNPACK_ROW_LENGTH;
import static org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_PIXELS;
import static org.lwjgl.opengl.GL11.GL_UNPACK_SKIP_ROWS;
import static org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE;
import static org.lwjgl.opengl.GL11.glBindTexture;
import static org.lwjgl.opengl.GL11.glDeleteTextures;
import static org.lwjgl.opengl.GL11.glGenTextures;
import static org.lwjgl.opengl.GL11.glGetInteger;
import static org.lwjgl.opengl.GL11.glPixelStorei;
import static org.lwjgl.opengl.GL11.glTexImage2D;
import static org.lwjgl.opengl.GL11.glTexParameteri;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.GL_RG;
import static org.lwjgl.opengl.GL30.GL_HALF_FLOAT;
import static org.lwjgl.opengl.GL30.GL_R8;
import static org.lwjgl.opengl.GL30.GL_RG8;
import static org.lwjgl.opengl.GL30.GL_RG16F;
import static org.lwjgl.opengl.GL30.GL_RGBA16F;
import static org.lwjgl.opengl.GL30.glGenerateMipmap;
import static org.lwjgl.opengl.GL21.GL_SRGB8;
import static org.lwjgl.opengl.GL21.GL_SRGB8_ALPHA8;
import static org.lwjgl.opengl.GL45.glCreateTextures;
import static org.lwjgl.opengl.GL45.glTextureParameteri;
import static org.lwjgl.opengl.GL45.glTextureStorage2D;
import static org.lwjgl.opengl.GL45.glTextureSubImage2D;
import static org.lwjgl.opengl.GL45.glGenerateTextureMipmap;

public final class Texture2D implements GlResource {
    private static final UploadFormat R8_UPLOAD = new UploadFormat(
            GL_RED, GL_UNSIGNED_BYTE, 1, TextureColorSpace.LINEAR);
    private static final UploadFormat RG8_UPLOAD = new UploadFormat(
            GL_RG, GL_UNSIGNED_BYTE, 2, TextureColorSpace.LINEAR);
    private static final UploadFormat RGB8_UPLOAD = new UploadFormat(
            GL_RGB, GL_UNSIGNED_BYTE, 3, TextureColorSpace.LINEAR);
    private static final UploadFormat RGBA8_UPLOAD = new UploadFormat(
            GL_RGBA, GL_UNSIGNED_BYTE, 4, TextureColorSpace.LINEAR);
    private static final UploadFormat SRGB8_UPLOAD = new UploadFormat(
            GL_RGB, GL_UNSIGNED_BYTE, 3, TextureColorSpace.SRGB);
    private static final UploadFormat SRGBA8_UPLOAD = new UploadFormat(
            GL_RGBA, GL_UNSIGNED_BYTE, 4, TextureColorSpace.SRGB);
    private static final UploadFormat RG16F_UPLOAD = new UploadFormat(
            GL_RG, GL_HALF_FLOAT, 2 * Short.BYTES, TextureColorSpace.LINEAR);
    private static final UploadFormat RGBA16F_UPLOAD = new UploadFormat(
            GL_RGBA, GL_HALF_FLOAT, 4 * Short.BYTES, TextureColorSpace.LINEAR);

    private final int id;
    private final int width;
    private final int height;
    private final int format;
    private final TextureColorSpace colorSpace;
    private final long resourceSequence;
    private boolean closed;

    private Texture2D(int id, int width, int height, int format) {
        this(id, width, height, format, TextureColorSpace.LINEAR);
    }

    private Texture2D(int id, int width, int height, int format, TextureColorSpace colorSpace) {
        this.id = id;
        this.width = width;
        this.height = height;
        this.format = format;
        this.colorSpace = Objects.requireNonNull(colorSpace, "colorSpace");
        this.resourceSequence = GlDebug.trackResource("TEXTURE", id,
                "Texture2D " + width + "x" + height, (long) width * height * bytesPerPixel(format));
        GlDebug.labelObject(org.lwjgl.opengl.GL43.GL_TEXTURE, id, "Texture2D " + width + "x" + height);
    }

    /**
     * 使用 OpenGL DSA 创建单 mip level 的空纹理存储。
     *
     * <p>当前支持 R8、RG8、RGB8、RGBA8、SRGB8 和 SRGB8_ALPHA8。创建过程不改变
     * active texture 或纹理绑定，因此不会使 {@code StateCache} 失效。</p>
     *
     * @param width 纹理宽度，必须为正
     * @param height 纹理高度，必须为正
     * @param internalFormat 受支持的 sized internal format
     * @return 具有不可变存储的纹理
     */
    public static Texture2D createEmpty(int width, int height, int internalFormat) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture dimensions must be positive");
        }
        UploadFormat uploadFormat = storageUploadFormat(internalFormat);
        int textureId = glCreateTextures(GL_TEXTURE_2D);
        try {
            glTextureStorage2D(textureId, 1, internalFormat, width, height);
            glTextureParameteri(textureId, GL_TEXTURE_WRAP_S,
                    org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            glTextureParameteri(textureId, GL_TEXTURE_WRAP_T,
                    org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            glTextureParameteri(textureId, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTextureParameteri(textureId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            Texture2D texture = new Texture2D(textureId, width, height, internalFormat,
                    uploadFormat.colorSpace());
            textureId = 0;
            return texture;
        } finally {
            if (textureId != 0) {
                glDeleteTextures(textureId);
            }
        }
    }

    /**
     * 创建 glyph atlas 等单通道数据使用的 R8 空纹理。
     *
     * @param width 纹理宽度
     * @param height 纹理高度
     * @return 线性 R8 纹理
     */
    public static Texture2D createR8(int width, int height) {
        return createEmpty(width, height, GL_R8);
    }

    /** Creates an immutable mipmapped R8 texture from tightly packed decoded bytes. */
    public static Texture2D fromR8(int width, int height, ByteBuffer pixels) {
        Objects.requireNonNull(pixels, "pixels");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture dimensions must be positive");
        }
        int required = Math.multiplyExact(width, height);
        if (!pixels.isDirect() || pixels.remaining() < required) {
            throw new IllegalArgumentException("R8 pixels must be a direct buffer with at least "
                    + required + " remaining bytes");
        }
        int levels = 1 + (31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
        int textureId = glCreateTextures(GL_TEXTURE_2D);
        try {
            glTextureStorage2D(textureId, levels, GL_R8, width, height);
            ByteBuffer upload = pixels.duplicate();
            upload.limit(upload.position() + required);
            int previousUnpackAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            try {
                glTextureSubImage2D(textureId, 0, 0, 0, width, height,
                        GL_RED, GL_UNSIGNED_BYTE, upload);
            } finally {
                glPixelStorei(GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
            }
            glGenerateTextureMipmap(textureId);
            glTextureParameteri(textureId, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTextureParameteri(textureId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTextureParameteri(textureId, GL_TEXTURE_WRAP_S,
                    org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            glTextureParameteri(textureId, GL_TEXTURE_WRAP_T,
                    org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
            Texture2D texture = new Texture2D(textureId, width, height, GL_R8,
                    TextureColorSpace.LINEAR);
            textureId = 0;
            return texture;
        } finally {
            if (textureId != 0) glDeleteTextures(textureId);
        }
    }

    /** Creates an immutable mipmapped RGBA8 texture from CPU-decoded pixels. */
    public static Texture2D fromRgba8(int width, int height, byte[] pixels,
                                      TextureColorSpace colorSpace) {
        Objects.requireNonNull(pixels, "pixels");
        Objects.requireNonNull(colorSpace, "colorSpace");
        int required;
        try {
            required = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("RGBA8 dimensions overflow payload", overflow);
        }
        if (pixels.length != required) {
            throw new IllegalArgumentException("RGBA8 payload length must be " + required
                    + ", got " + pixels.length);
        }
        return fromRgba8(width, height, DirectBuffers.copyOf(pixels), colorSpace);
    }

    /** Creates an immutable mipmapped RGBA8 texture from a direct pixel buffer. */
    public static Texture2D fromRgba8(int width, int height, ByteBuffer pixels,
                                      TextureColorSpace colorSpace) {
        Objects.requireNonNull(pixels, "pixels");
        Objects.requireNonNull(colorSpace, "colorSpace");
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("texture dimensions must be positive");
        }
        int required = Math.multiplyExact(Math.multiplyExact(width, height), 4);
        if (!pixels.isDirect() || pixels.remaining() < required) {
            throw new IllegalArgumentException("RGBA8 pixels must be a direct buffer with at least "
                    + required + " remaining bytes");
        }
        int levels = 1 + (31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
        int internalFormat = colorSpace == TextureColorSpace.SRGB
                ? GL_SRGB8_ALPHA8 : GL_RGBA8;
        int textureId = glCreateTextures(GL_TEXTURE_2D);
        try {
            glTextureStorage2D(textureId, levels, internalFormat, width, height);
            ByteBuffer upload = pixels.duplicate();
            upload.limit(upload.position() + required);
            int previousUnpackAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
            glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            try {
                glTextureSubImage2D(textureId, 0, 0, 0, width, height,
                        GL_RGBA, GL_UNSIGNED_BYTE, upload);
            } finally {
                glPixelStorei(GL_UNPACK_ALIGNMENT, previousUnpackAlignment);
            }
            glGenerateTextureMipmap(textureId);
            glTextureParameteri(textureId, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
            glTextureParameteri(textureId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
            glTextureParameteri(textureId, GL_TEXTURE_WRAP_S, GL_REPEAT);
            glTextureParameteri(textureId, GL_TEXTURE_WRAP_T, GL_REPEAT);
            Texture2D texture = new Texture2D(textureId, width, height, internalFormat, colorSpace);
            textureId = 0;
            return texture;
        } finally {
            if (textureId != 0) glDeleteTextures(textureId);
        }
    }

    /**
     * 从类路径资源加载纹理，默认垂直翻转。
     *
     * @param anchor       用于定位资源的类
     * @param resourcePath 资源文件路径
     * @return 加载的 Texture2D 实例
     */
    public static Texture2D fromResource(Class<?> anchor, String resourcePath) {
        return fromResource(anchor, resourcePath, true, TextureColorSpace.LINEAR);
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
        return fromResource(anchor, resourcePath, flipVertically, TextureColorSpace.LINEAR);
    }

    /**
     * 从类路径资源加载纹理，并显式指定采样颜色空间。
     *
     * @param anchor         用于定位资源的类
     * @param resourcePath   资源文件路径
     * @param colorSpace     纹理采样颜色空间
     * @return 加载完成的纹理
     */
    public static Texture2D fromResource(Class<?> anchor, String resourcePath,
                                         TextureColorSpace colorSpace) {
        return fromResource(anchor, resourcePath, true, colorSpace);
    }

    /**
     * 从类路径资源加载纹理，并显式指定翻转方式与采样颜色空间。
     *
     * @param anchor         用于定位资源的类
     * @param resourcePath   资源文件路径
     * @param flipVertically 是否垂直翻转
     * @param colorSpace     纹理采样颜色空间
     * @return 加载完成的纹理
     */
    public static Texture2D fromResource(Class<?> anchor, String resourcePath, boolean flipVertically,
                                         TextureColorSpace colorSpace) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        Objects.requireNonNull(colorSpace, "colorSpace");

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
            int textureId = 0;
            try {
                int width = widthBuffer.get(0);
                int height = heightBuffer.get(0);
                int channels = channelsBuffer.get(0);
                int externalFormat = formatFromChannels(channels);
                int internalFormat = internalFormat(channels, colorSpace);

                textureId = glGenTextures();
                glBindTexture(GL_TEXTURE_2D, textureId);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_REPEAT);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTexImage2D(GL_TEXTURE_2D, 0, internalFormat, width, height, 0, externalFormat,
                        org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, image);
                glGenerateMipmap(GL_TEXTURE_2D);
                Texture2D texture = new Texture2D(textureId, width, height, internalFormat, colorSpace);
                textureId = 0;
                return texture;
            } finally {
                STBImage.stbi_image_free(image);
                if (textureId != 0) {
                    glDeleteTextures(textureId);
                }
            }
        }
    }

    /**
     * 从内存中的 PNG/JPEG 编码字节创建完整 mip 链纹理。该入口使用 STB 线程局部 flip 状态，
     * 不会改变其他图片加载器的全局方向；glTF 调用方应传入 {@code false}。
     */
    public static Texture2D fromEncoded(byte[] encoded, boolean flipVertically,
                                        TextureColorSpace colorSpace) {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(colorSpace, "colorSpace");
        if (encoded.length == 0) throw new IllegalArgumentException("encoded image must not be empty");
        ByteBuffer data = DirectBuffers.copyOf(encoded);
        STBImage.stbi_set_flip_vertically_on_load_thread(flipVertically ? 1 : 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer widthBuffer = stack.mallocInt(1);
            IntBuffer heightBuffer = stack.mallocInt(1);
            IntBuffer channelsBuffer = stack.mallocInt(1);
            ByteBuffer image = STBImage.stbi_load_from_memory(data, widthBuffer, heightBuffer,
                    channelsBuffer, 4);
            if (image == null) throw new GlException("Failed to decode image memory: "
                    + STBImage.stbi_failure_reason());
            int textureId = 0;
            try {
                int width = widthBuffer.get(0);
                int height = heightBuffer.get(0);
                int levels = 1 + (31 - Integer.numberOfLeadingZeros(Math.max(width, height)));
                int internalFormat = colorSpace == TextureColorSpace.SRGB
                        ? GL_SRGB8_ALPHA8 : GL_RGBA8;
                textureId = glCreateTextures(GL_TEXTURE_2D);
                glTextureStorage2D(textureId, levels, internalFormat, width, height);
                glTextureSubImage2D(textureId, 0, 0, 0, width, height,
                        GL_RGBA, GL_UNSIGNED_BYTE, image);
                glGenerateTextureMipmap(textureId);
                glTextureParameteri(textureId, GL_TEXTURE_MIN_FILTER, GL_LINEAR_MIPMAP_LINEAR);
                glTextureParameteri(textureId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                glTextureParameteri(textureId, GL_TEXTURE_WRAP_S, GL_REPEAT);
                glTextureParameteri(textureId, GL_TEXTURE_WRAP_T, GL_REPEAT);
                Texture2D texture = new Texture2D(textureId, width, height, internalFormat, colorSpace);
                textureId = 0;
                return texture;
            } finally {
                STBImage.stbi_image_free(image);
                if (textureId != 0) glDeleteTextures(textureId);
            }
        } finally {
            STBImage.stbi_set_flip_vertically_on_load_thread(0);
        }
    }

    /**
     * 从 classpath 以 float 精度解码线性 HDR image，并上传为 RGBA16F。
     * flip 状态使用 STB 的线程局部入口并在异常路径恢复，不污染其他 loader。
     */
    public static Texture2D fromHdrResource(Class<?> anchor, String resourcePath,
                                            boolean flipVertically) {
        Objects.requireNonNull(anchor, "anchor");
        Objects.requireNonNull(resourcePath, "resourcePath");
        byte[] bytes = DirectBuffers.readResourceBytes(anchor, resourcePath);
        ByteBuffer data = DirectBuffers.copyOf(bytes);
        STBImage.stbi_set_flip_vertically_on_load_thread(flipVertically ? 1 : 0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer widthBuffer = stack.mallocInt(1);
            IntBuffer heightBuffer = stack.mallocInt(1);
            IntBuffer channelsBuffer = stack.mallocInt(1);
            FloatBuffer image = STBImage.stbi_loadf_from_memory(
                    data, widthBuffer, heightBuffer, channelsBuffer, 4);
            if (image == null) {
                throw new GlException("Failed to load HDR image " + resourcePath + ": "
                        + STBImage.stbi_failure_reason());
            }
            int textureId = 0;
            try {
                int width = widthBuffer.get(0);
                int height = heightBuffer.get(0);
                textureId = glCreateTextures(GL_TEXTURE_2D);
                glTextureStorage2D(textureId, 1, GL_RGBA16F, width, height);
                glTextureSubImage2D(textureId, 0, 0, 0, width, height,
                        GL_RGBA, GL_FLOAT, image);
                glTextureParameteri(textureId, GL_TEXTURE_WRAP_S,
                        org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
                glTextureParameteri(textureId, GL_TEXTURE_WRAP_T,
                        org.lwjgl.opengl.GL12.GL_CLAMP_TO_EDGE);
                glTextureParameteri(textureId, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
                glTextureParameteri(textureId, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
                Texture2D texture = new Texture2D(textureId, width, height, GL_RGBA16F,
                        TextureColorSpace.LINEAR);
                textureId = 0;
                return texture;
            } finally {
                STBImage.stbi_image_free(image);
                if (textureId != 0) glDeleteTextures(textureId);
            }
        } finally {
            STBImage.stbi_set_flip_vertically_on_load_thread(0);
        }
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

    /**
     * 返回紧密排列的 region upload 所需字节数，并校验区域边界。
     *
     * @param x 左边界
     * @param y 下边界
     * @param regionWidth 区域宽度，允许为零
     * @param regionHeight 区域高度，允许为零
     * @return 所需 payload 字节数
     */
    public int requiredRegionBytes(int x, int y, int regionWidth, int regionHeight) {
        ensureOpen();
        if (x < 0 || y < 0 || regionWidth < 0 || regionHeight < 0
                || (long) x + regionWidth > width
                || (long) y + regionHeight > height) {
            throw new IllegalArgumentException("texture upload region is outside texture bounds");
        }
        UploadFormat uploadFormat = uploadFormat(format);
        try {
            return Math.multiplyExact(Math.multiplyExact(regionWidth, regionHeight),
                    uploadFormat.bytesPerPixel());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("texture upload byte count overflows int", overflow);
        }
    }

    /**
     * 使用 DSA 上传紧密排列的纹理子区域。
     *
     * <p>调用期间临时使用 alignment=1、rowLength=0、skipRows=0、skipPixels=0 的紧密
     * unpack 布局，并在 finally 中恢复原值；不改变 active texture、纹理绑定或状态缓存
     * 所跟踪的任何状态。输入 position 不移动。</p>
     *
     * @param x 左边界
     * @param y 下边界
     * @param regionWidth 区域宽度
     * @param regionHeight 区域高度
     * @param pixels 至少包含所需字节的 direct buffer
     */
    public void uploadRegion(int x, int y, int regionWidth, int regionHeight,
                             ByteBuffer pixels) {
        Objects.requireNonNull(pixels, "pixels");
        int requiredBytes = requiredRegionBytes(x, y, regionWidth, regionHeight);
        if (pixels.remaining() < requiredBytes) {
            throw new IllegalArgumentException("texture upload payload is too small: required "
                    + requiredBytes + ", remaining " + pixels.remaining());
        }
        if (!pixels.isDirect()) {
            throw new IllegalArgumentException("texture upload payload must be a direct buffer");
        }
        if (requiredBytes == 0) {
            return;
        }

        UploadFormat uploadFormat = uploadFormat(format);
        ByteBuffer region = pixels.duplicate();
        region.limit(region.position() + requiredBytes);
        int previousAlignment = glGetInteger(GL_UNPACK_ALIGNMENT);
        int previousRowLength = glGetInteger(GL_UNPACK_ROW_LENGTH);
        int previousSkipRows = glGetInteger(GL_UNPACK_SKIP_ROWS);
        int previousSkipPixels = glGetInteger(GL_UNPACK_SKIP_PIXELS);
        try {
            if (previousAlignment != 1) {
                glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
            }
            if (previousRowLength != 0) {
                glPixelStorei(GL_UNPACK_ROW_LENGTH, 0);
            }
            if (previousSkipRows != 0) {
                glPixelStorei(GL_UNPACK_SKIP_ROWS, 0);
            }
            if (previousSkipPixels != 0) {
                glPixelStorei(GL_UNPACK_SKIP_PIXELS, 0);
            }
            glTextureSubImage2D(id, 0, x, y, regionWidth, regionHeight,
                    uploadFormat.externalFormat(), uploadFormat.dataType(), region);
        } finally {
            if (previousSkipPixels != 0) {
                glPixelStorei(GL_UNPACK_SKIP_PIXELS, previousSkipPixels);
            }
            if (previousSkipRows != 0) {
                glPixelStorei(GL_UNPACK_SKIP_ROWS, previousSkipRows);
            }
            if (previousRowLength != 0) {
                glPixelStorei(GL_UNPACK_ROW_LENGTH, previousRowLength);
            }
            if (previousAlignment != 1) {
                glPixelStorei(GL_UNPACK_ALIGNMENT, previousAlignment);
            }
        }
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

    /** @return 纹理在采样时使用的颜色空间 */
    public TextureColorSpace colorSpace() {
        return colorSpace;
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
        GlDebug.closeResource(resourceSequence);
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

    private static int bytesPerPixel(int internalFormat) {
        return switch (internalFormat) {
            case GL_R8, GL_RED -> 1;
            case GL_RG8 -> 2;
            case GL_RGB8, GL_SRGB8, GL_RGB -> 3;
            case GL_RG16F -> 4;
            case GL_RGBA16F -> 8;
            default -> 4;
        };
    }

    private static int internalFormat(int channels, TextureColorSpace colorSpace) {
        return switch (channels) {
            case 1 -> GL_R8;
            case 2 -> GL_RG8;
            case 3 -> colorSpace == TextureColorSpace.SRGB ? GL_SRGB8 : org.lwjgl.opengl.GL11.GL_RGB8;
            case 4 -> colorSpace == TextureColorSpace.SRGB ? GL_SRGB8_ALPHA8 : org.lwjgl.opengl.GL11.GL_RGBA8;
            default -> throw new GlException("Unsupported channel count: " + channels);
        };
    }

    private static UploadFormat uploadFormat(int internalFormat) {
        return switch (internalFormat) {
            case GL_R8, GL_RED -> R8_UPLOAD;
            case GL_RG8, GL_RG -> RG8_UPLOAD;
            case GL_RGB8, GL_RGB -> RGB8_UPLOAD;
            case GL_RGBA8, GL_RGBA -> RGBA8_UPLOAD;
            case GL_SRGB8 -> SRGB8_UPLOAD;
            case GL_SRGB8_ALPHA8 -> SRGBA8_UPLOAD;
            case GL_RG16F -> RG16F_UPLOAD;
            case GL_RGBA16F -> RGBA16F_UPLOAD;
            default -> throw new IllegalArgumentException(
                    "unsupported dynamic texture internal format: " + internalFormat);
        };
    }

    private static UploadFormat storageUploadFormat(int internalFormat) {
        return switch (internalFormat) {
            case GL_R8 -> R8_UPLOAD;
            case GL_RG8 -> RG8_UPLOAD;
            case GL_RGB8 -> RGB8_UPLOAD;
            case GL_RGBA8 -> RGBA8_UPLOAD;
            case GL_SRGB8 -> SRGB8_UPLOAD;
            case GL_SRGB8_ALPHA8 -> SRGBA8_UPLOAD;
            case GL_RG16F -> RG16F_UPLOAD;
            case GL_RGBA16F -> RGBA16F_UPLOAD;
            default -> throw new IllegalArgumentException(
                    "unsupported empty texture sized internal format: " + internalFormat);
        };
    }

    private record UploadFormat(int externalFormat, int dataType, int bytesPerPixel,
                                TextureColorSpace colorSpace) {
    }
}
