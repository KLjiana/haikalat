package com.kaleblangley.haikalat.demo.pbr;
import org.lwjgl.BufferUtils;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.io.IOException;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.lwjgl.opengl.GL46.*;
/** Shared explicit display/HDR capture; never called during benchmark measurement. */
final class SceneCaptureIO {
    private SceneCaptureIO() { }
    static void display(int width, int height, String capturePath) {
        ByteBuffer pixels = BufferUtils.createByteBuffer(Math.multiplyExact(
                Math.multiplyExact(width, height), 4));
        glReadBuffer(GL_BACK);
        glReadPixels(0, 0, width, height, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int offset = (y * width + x) * 4;
                int red = Byte.toUnsignedInt(pixels.get(offset));
                int green = Byte.toUnsignedInt(pixels.get(offset + 1));
                int blue = Byte.toUnsignedInt(pixels.get(offset + 2));
                int alpha = Byte.toUnsignedInt(pixels.get(offset + 3));
                image.setRGB(x, height - 1 - y,
                        alpha << 24 | red << 16 | green << 8 | blue);
            }
        }
        Path output = Path.of(capturePath).toAbsolutePath().normalize();
        try {
            Path parent = output.getParent();
            if (parent != null) Files.createDirectories(parent);
            if (!ImageIO.write(image, "png", output.toFile())) {
                throw new IllegalStateException("PNG writer is unavailable");
            }
            System.out.println("Scene capture: " + output);
        } catch (IOException failure) {
            throw new IllegalStateException("failed to capture outdoor environment to " + output,
                    failure);
        }
    }

    static void linear(Path path,float[] rgba,int width,int height) {
        float[] rgb=new float[Math.multiplyExact(Math.multiplyExact(width,height),3)];
        for(int p=0;p<width*height;p++) for(int c=0;c<3;c++) {
            float value=rgba[p*4+c];
            if(!Float.isFinite(value)) throw new IllegalStateException("nonfinite linear HDR capture at "+p);
            rgb[p*3+c]=value;
        }
        VisualBaselineHdr.save(path,new VisualBaselineHdr.Image(width,height,rgb));
    }
}
