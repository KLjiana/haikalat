package com.kaleblangley.haikalat.subsystems.render3d;

import com.kaleblangley.haikalat.backend.shader.ShaderProgram;
import com.kaleblangley.haikalat.core.command.CommandBuffer;

/** Borrowed lighting capability. No allocation, mutation or disposal of the producer is exposed. */
interface VolumetricLightingInputs {
    FrameLightTable frameTable(long frameSequence);
    void bindCompute(CommandBuffer commands, ShaderProgram shader);
    void bindFine(CommandBuffer commands);
}
