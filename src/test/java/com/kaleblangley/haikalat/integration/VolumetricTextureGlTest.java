package com.kaleblangley.haikalat.integration;

import com.kaleblangley.haikalat.backend.GlDebug;
import com.kaleblangley.haikalat.backend.GlException;
import com.kaleblangley.haikalat.backend.RenderFormat;
import com.kaleblangley.haikalat.backend.buffer.GlBuffer;
import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.backend.state.HostGlState;
import com.kaleblangley.haikalat.backend.texture.ImageAccess;
import com.kaleblangley.haikalat.backend.texture.Sampler;
import com.kaleblangley.haikalat.backend.texture.Texture3D;
import com.kaleblangley.haikalat.core.device.GlRenderDevice;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.opengl.GL46.*;

@EnabledIfSystemProperty(named = "haikalat.glSmoke", matches = "true")
class VolumetricTextureGlTest {
    @Test void explicitSliceExportPreservesHostPackBufferAndPackingAndRejectsInvalidLifecycle() {
        try(var window=GlTestSupport.hiddenWindow()) {
            window.bindContext();GL.createCapabilities();
            try(var texture=Texture3D.create(3,2,4,RenderFormat.RGBA16F);
                var pbo=new GlBuffer(GL_PIXEL_PACK_BUFFER,GL_STATIC_DRAW).allocate(4096)) {
                glClearTexImage(texture.id(),0,GL_RGBA,GL_FLOAT,new float[]{.25f,.5f,.75f,1});
                glBindBuffer(GL_PIXEL_PACK_BUFFER,pbo.id());
                glPixelStorei(GL_PACK_ALIGNMENT,8);glPixelStorei(GL_PACK_ROW_LENGTH,13);glPixelStorei(GL_PACK_IMAGE_HEIGHT,17);
                glPixelStorei(GL_PACK_SKIP_PIXELS,2);glPixelStorei(GL_PACK_SKIP_ROWS,3);glPixelStorei(GL_PACK_SKIP_IMAGES,1);
                glPixelStorei(GL_PACK_SWAP_BYTES,1);
                try {
                    float[] slice=texture.readLayerRgbaFloat(2);assertEquals(24,slice.length);
                    for(int pixel=0;pixel<6;pixel++)for(int component=0;component<4;component++)
                        assertEquals(new float[]{.25f,.5f,.75f,1}[component],slice[pixel*4+component]);
                    assertEquals(pbo.id(),glGetInteger(GL_PIXEL_PACK_BUFFER_BINDING));assertEquals(8,glGetInteger(GL_PACK_ALIGNMENT));
                    assertEquals(13,glGetInteger(GL_PACK_ROW_LENGTH));assertEquals(17,glGetInteger(GL_PACK_IMAGE_HEIGHT));
                    assertEquals(2,glGetInteger(GL_PACK_SKIP_PIXELS));assertEquals(3,glGetInteger(GL_PACK_SKIP_ROWS));
                    assertEquals(1,glGetInteger(GL_PACK_SKIP_IMAGES));assertEquals(1,glGetInteger(GL_PACK_SWAP_BYTES));
                    assertThrows(IllegalArgumentException.class,()->texture.readLayerRgbaFloat(-1));
                    assertThrows(IllegalArgumentException.class,()->texture.readLayerRgbaFloat(4));
                    texture.close();assertThrows(GlException.class,()->texture.readLayerRgbaFloat(0));
                } finally {
                    glBindBuffer(GL_PIXEL_PACK_BUFFER,0);glPixelStorei(GL_PACK_ALIGNMENT,4);
                    for(int name:new int[]{GL_PACK_ROW_LENGTH,GL_PACK_IMAGE_HEIGHT,GL_PACK_SKIP_PIXELS,
                            GL_PACK_SKIP_ROWS,GL_PACK_SKIP_IMAGES,GL_PACK_SWAP_BYTES})glPixelStorei(name,0);
                }
            }
            GlDebug.assertNoError("explicit 3D slice host pack preservation");
        }
    }
    private static final String WRITE = """
            #version 460 core
            layout(local_size_x=4, local_size_y=4, local_size_z=4) in;
            layout(rgba16f, binding=0) writeonly uniform image3D uOutput;
            uniform int uIteration;
            void main() {
                ivec3 p=ivec3(gl_GlobalInvocationID);
                if (any(greaterThanEqual(p,imageSize(uOutput)))) return;
                imageStore(uOutput,p,vec4(vec3(p+ivec3(1)),float(uIteration+1)));
            }
            """;
    private static final String READ = """
            #version 460 core
            layout(local_size_x=4, local_size_y=4, local_size_z=4) in;
            layout(binding=14) uniform sampler3D uSource;
            layout(binding=15) uniform sampler3D uSource2;
            layout(rgba16f, binding=0) readonly uniform image3D uInput;
            layout(std430,binding=0) writeonly buffer Probe { vec4 values[]; };
            void main() {
                ivec3 p=ivec3(gl_GlobalInvocationID), size=imageSize(uInput);
                if (any(greaterThanEqual(p,size))) return;
                int index=(p.z*size.y+p.y)*size.x+p.x;
                vec3 uv=(vec3(p)+0.5)/vec3(size);
                values[index]=0.5*(texture(uSource,uv)+texture(uSource2,uv));
                values[index+size.x*size.y*size.z]=imageLoad(uInput,p);
            }
            """;

    @Test
    void xyzComputeWritesAreVisibleThroughSamplerAndImageIncludingScratchReuse() {
        try (var window = GlTestSupport.hiddenWindow()) {
            window.bindContext(); GL.createCapabilities();
            for (int[] size : new int[][]{{1,1,1}, {5,3,7}, {17,9,5}}) {
                checkWriteRead(size[0], size[1], size[2], RenderFormat.RGBA16F);
            }
            checkWriteRead(5, 3, 7, RenderFormat.R8);
            GlDebug.assertNoError("3D XYZ/image/sampler readback");
        }
    }

    private static void checkWriteRead(int width, int height, int depth, RenderFormat format) {
        String writerSource = WRITE;
        String readerSource = READ;
        if (format == RenderFormat.R8) {
            writerSource = WRITE.replace("rgba16f", "r8").replace(
                    "vec4(vec3(p+ivec3(1)),float(uIteration+1))",
                    "vec4(float((p.z*imageSize(uOutput).y+p.y)*imageSize(uOutput).x+p.x+uIteration)/255.0,0,0,1)");
            readerSource = READ.replace("rgba16f", "r8");
        }
        int count = width * height * depth;
        long bytes = count * 8L * Float.BYTES;
        try (Texture3D texture = Texture3D.create(width, height, depth, format);
             ShaderProgram writer = ShaderProgram.fromComputeSource(writerSource);
             ShaderProgram reader = ShaderProgram.fromComputeSource(readerSource);
             GlBuffer output = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(bytes)) {
            assertEquals(1, glGetTextureParameteri(texture.id(), GL_TEXTURE_IMMUTABLE_LEVELS));
            assertEquals(0, glGetTextureParameteri(texture.id(), GL_TEXTURE_MAX_LEVEL));
            GlRenderDevice device = new GlRenderDevice();
            for (int iteration = 0; iteration < 2; iteration++) {
                var commands = device.createCommandBuffer()
                        .memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                        .bindShader(writer).setUniformInt(writer, "uIteration", iteration)
                        .bindImageTexture(0, texture, 0, ImageAccess.WRITE_ONLY, format)
                        .dispatchCompute((width+3)/4, (height+3)/4, (depth+3)/4)
                        .memoryBarrier(GL_TEXTURE_FETCH_BARRIER_BIT | GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                        .bindShader(reader).bindTexture3D(14, texture).bindTexture3D(15, texture)
                        .bindImageTexture(0, texture, 0, ImageAccess.READ_ONLY, format)
                        .bindStorageBuffer(0, output, 0, bytes)
                        .dispatchCompute((width+3)/4, (height+3)/4, (depth+3)/4)
                        .memoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
                device.execute(commands);
                var data = BufferUtils.createFloatBuffer(count * 8);
                glGetNamedBufferSubData(output.id(), 0, data);
                for (int z=0; z<depth; z++) for (int y=0; y<height; y++) for (int x=0; x<width; x++) {
                    int index=(z*height+y)*width+x;
                    for (int access=0; access<2; access++) {
                        int offset=(access*count+index)*4;
                        float[] expected = format == RenderFormat.R8
                                ? new float[]{(index+iteration)/255f, 0, 0, 1}
                                : new float[]{x+1, y+1, z+1, iteration+1};
                        for (int component=0; component<4; component++) {
                            assertEquals(expected[component], data.get(offset+component), 0.00002f,
                                    "XYZ " + x + "/" + y + "/" + z + ", access " + access + ", component " + component);
                        }
                    }
                }
            }
        }
    }

    @Test
    void singleLayerWritesStayInTheirLayerAndTheCacheTracksLayeredAccess() {
        String layerWriter = """
                #version 460 core
                layout(local_size_x=4,local_size_y=4) in;
                layout(rgba16f,binding=0) writeonly uniform image2D uLayer;
                void main() {
                    ivec2 p=ivec2(gl_GlobalInvocationID);
                    if (any(greaterThanEqual(p,imageSize(uLayer)))) return;
                    imageStore(uLayer,p,vec4(9,8,7,6));
                }
                """;
        try (var window = GlTestSupport.hiddenWindow()) {
            window.bindContext(); GL.createCapabilities();
            try (Texture3D texture = Texture3D.create(5, 3, 7, RenderFormat.RGBA16F);
                 ShaderProgram writer = ShaderProgram.fromComputeSource(WRITE);
                 ShaderProgram layer = ShaderProgram.fromComputeSource(layerWriter)) {
                GlRenderDevice device = new GlRenderDevice();
                device.execute(device.createCommandBuffer().bindShader(writer)
                        .setUniformInt(writer, "uIteration", 0)
                        .bindImageTexture(0, texture, 0, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                        .dispatchCompute(2, 1, 2)
                        .memoryBarrier(GL_SHADER_IMAGE_ACCESS_BARRIER_BIT)
                        .bindShader(layer)
                        .bindImageTextureLayer(0, texture, 0, 3, ImageAccess.WRITE_ONLY, RenderFormat.RGBA16F)
                        .dispatchCompute(2, 1, 1).memoryBarrier(GL_TEXTURE_UPDATE_BARRIER_BIT));
                assertEquals(GL_FALSE, glGetIntegeri(GL_IMAGE_BINDING_LAYERED, 0));
                assertEquals(3, glGetIntegeri(GL_IMAGE_BINDING_LAYER, 0));
                var data = BufferUtils.createFloatBuffer(5*3*7*4);
                glGetTextureImage(texture.id(), 0, GL_RGBA, GL_FLOAT, data);
                for (int z=0; z<7; z++) for (int y=0; y<3; y++) for (int x=0; x<5; x++) {
                    int offset=((z*3+y)*5+x)*4;
                    assertEquals(z == 3 ? 9 : x+1, data.get(offset));
                    assertEquals(z == 3 ? 8 : y+1, data.get(offset+1));
                    assertEquals(z == 3 ? 7 : z+1, data.get(offset+2));
                }
                var binding = device.createCommandBuffer()
                        .bindImageTexture(0, texture, 0, ImageAccess.READ_ONLY, RenderFormat.RGBA16F);
                device.execute(binding);
                assertEquals(GL_TRUE, glGetIntegeri(GL_IMAGE_BINDING_LAYERED, 0));
                assertEquals(GL_READ_ONLY, glGetIntegeri(GL_IMAGE_BINDING_ACCESS, 0));
                long applied = device.stateStatistics().appliedChanges();
                device.execute(binding);
                assertEquals(applied, device.stateStatistics().appliedChanges());
                GlDebug.assertNoError("layered/non-layered image binding");
            }
        }
    }

    @Test
    void host3dSamplerImageAndIndexedBufferStateIsRestored() {
        try (var window = GlTestSupport.hiddenWindow()) {
            window.bindContext(); GL.createCapabilities();
            int alignment = glGetInteger(GL_UNIFORM_BUFFER_OFFSET_ALIGNMENT);
            int storageAlignment = glGetInteger(GL_SHADER_STORAGE_BUFFER_OFFSET_ALIGNMENT);
            try (Texture3D hostTexture = Texture3D.create(3, 4, 5, RenderFormat.RGBA16F);
                 Texture3D engineTexture = Texture3D.create(2, 2, 2, RenderFormat.R8);
                 Sampler hostSampler = Sampler.nearestRepeat(); Sampler engineSampler = Sampler.nearestRepeat();
                 GlBuffer hostUbo = GlBuffer.uniformBuffer(GL_DYNAMIC_DRAW).allocate(alignment*3L);
                 GlBuffer hostSsbo = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(storageAlignment*3L);
                 GlBuffer engineUbo = GlBuffer.uniformBuffer(GL_DYNAMIC_DRAW).allocate(alignment*3L);
                 GlBuffer engineSsbo = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(storageAlignment*3L)) {
                glActiveTexture(GL_TEXTURE0+14); glBindTexture(GL_TEXTURE_3D, hostTexture.id());
                glBindSampler(14, hostSampler.id());
                glBindImageTexture(2, hostTexture.id(), 0, false, 3, GL_READ_WRITE, GL_RGBA16F);
                glBindBufferRange(GL_UNIFORM_BUFFER, 7, hostUbo.id(), alignment, alignment);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 4, hostSsbo.id(), storageAlignment, storageAlignment);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 13, hostSsbo.id(), 2L*storageAlignment, storageAlignment);
                glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 14, hostSsbo.id(), 0, storageAlignment);
                glActiveTexture(GL_TEXTURE0+3);
                GlRenderDevice device = new GlRenderDevice();
                try (HostGlState ignored = HostGlState.capture()) {
                    device.execute(device.createCommandBuffer().bindTexture3D(14, engineTexture, engineSampler)
                            .bindImageTexture(2, engineTexture, 0, ImageAccess.WRITE_ONLY, RenderFormat.R8)
                            .bindUniformBuffer(7, engineUbo, 0, alignment)
                            .bindStorageBuffer(4, engineSsbo, 0, storageAlignment)
                            .bindStorageBuffer(13, engineSsbo, 0, storageAlignment)
                            .bindStorageBuffer(14, engineSsbo, storageAlignment, storageAlignment));
                }
                device.invalidateState();
                assertEquals(GL_TEXTURE0+3, glGetInteger(GL_ACTIVE_TEXTURE));
                glActiveTexture(GL_TEXTURE0+14);
                assertEquals(hostTexture.id(), glGetInteger(GL_TEXTURE_BINDING_3D));
                assertEquals(hostSampler.id(), glGetIntegeri(GL_SAMPLER_BINDING, 14));
                assertEquals(hostTexture.id(), glGetIntegeri(GL_IMAGE_BINDING_NAME, 2));
                assertEquals(0, glGetIntegeri(GL_IMAGE_BINDING_LEVEL, 2));
                assertEquals(GL_FALSE, glGetIntegeri(GL_IMAGE_BINDING_LAYERED, 2));
                assertEquals(3, glGetIntegeri(GL_IMAGE_BINDING_LAYER, 2));
                assertEquals(GL_READ_WRITE, glGetIntegeri(GL_IMAGE_BINDING_ACCESS, 2));
                assertEquals(GL_RGBA16F, glGetIntegeri(GL_IMAGE_BINDING_FORMAT, 2));
                assertEquals(hostUbo.id(), glGetIntegeri(GL_UNIFORM_BUFFER_BINDING, 7));
                assertEquals(alignment, glGetInteger64i(GL_UNIFORM_BUFFER_START, 7));
                assertEquals(alignment, glGetInteger64i(GL_UNIFORM_BUFFER_SIZE, 7));
                assertEquals(hostSsbo.id(), glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 4));
                assertEquals(storageAlignment, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 4));
                assertEquals(storageAlignment, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 4));
                assertEquals(hostSsbo.id(), glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 13));
                assertEquals(2L*storageAlignment, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 13));
                assertEquals(storageAlignment, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 13));
                assertEquals(hostSsbo.id(), glGetIntegeri(GL_SHADER_STORAGE_BUFFER_BINDING, 14));
                assertEquals(0, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_START, 14));
                assertEquals(storageAlignment, glGetInteger64i(GL_SHADER_STORAGE_BUFFER_SIZE, 14));
                GlDebug.assertNoError("volume host restoration");
            }
        }
    }

    @Test
    void invalidBindingsAndClosedResourcesCannotReachGlExecution() {
        try (var window = GlTestSupport.hiddenWindow()) {
            window.bindContext(); GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            Texture3D texture = Texture3D.create(2, 3, 4, RenderFormat.RGBA16F);
            try {
                var sampler = device.createCommandBuffer().bindTexture3D(14, texture);
                var image = device.createCommandBuffer().bindImageTexture(0, texture, 0, ImageAccess.READ_ONLY, RenderFormat.RGBA16F);
                assertThrows(IllegalArgumentException.class, () -> device.createCommandBuffer()
                        .bindImageTexture(0, texture, 0, ImageAccess.READ_ONLY, RenderFormat.R8));
                assertThrows(IllegalArgumentException.class, () -> device.createCommandBuffer()
                        .bindImageTexture(0, texture, 1, ImageAccess.READ_ONLY, RenderFormat.RGBA16F));
                assertThrows(IllegalArgumentException.class, () -> device.createCommandBuffer()
                        .bindImageTextureLayer(0, texture, 0, 4, ImageAccess.READ_ONLY, RenderFormat.RGBA16F));
                assertThrows(IllegalArgumentException.class,
                        () -> device.execute(device.createCommandBuffer().bindTexture3D(glGetInteger(GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS), texture)));
                assertThrows(IllegalArgumentException.class,
                        () -> Texture3D.create(glGetInteger(GL_MAX_3D_TEXTURE_SIZE)+1, 1, 1, RenderFormat.R8));
                texture.close(); texture.close();
                assertThrows(GlException.class, () -> device.createCommandBuffer().bindTexture3D(14, texture));
                assertThrows(GlException.class, () -> device.execute(sampler));
                assertThrows(GlException.class, () -> device.execute(image));
                GlDebug.assertNoError("volume lifecycle rejection");
            } finally { texture.close(); }
        }
    }

    @Test
    void closedVolumeParameterBuffersAreRejectedWhenCommandsExecute() {
        try (var window = GlTestSupport.hiddenWindow()) {
            window.bindContext(); GL.createCapabilities();
            GlRenderDevice device = new GlRenderDevice();
            try (var ubo = GlBuffer.uniformBuffer(GL_DYNAMIC_DRAW).allocate(256);
                 var ssbo = GlBuffer.shaderStorageBuffer(GL_DYNAMIC_DRAW).allocate(256)) {
                var uniform = device.createCommandBuffer().bindUniformBuffer(7,ubo,0,256);
                var storage = device.createCommandBuffer().bindStorageBuffer(4,ssbo,0,256);
                ubo.close(); ssbo.close();
                assertThrows(GlException.class, () -> device.execute(uniform));
                assertThrows(GlException.class, () -> device.execute(storage));
                assertThrows(GlException.class, () -> device.createCommandBuffer().bindUniformBuffer(7,ubo,0,256));
                assertThrows(GlException.class, () -> device.createCommandBuffer().bindStorageBuffer(4,ssbo,0,256));
                GlDebug.assertNoError("closed volume buffers");
            }
        }
    }
}
