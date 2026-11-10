package com.kaleblangley.haikalat.subsystems.render3d.lighting;

import com.kaleblangley.haikalat.subsystems.render3d.FrameLightTable;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

/** Borrowed lighting capability. No allocation, mutation or disposal of the producer is exposed. */
public interface VolumetricLightingInputs {
    FrameLightTable frameTable(long frameSequence);
    void bindCompute(CommandBuffer commands, ShaderProgram shader);
    void bindFine(CommandBuffer commands);
}
