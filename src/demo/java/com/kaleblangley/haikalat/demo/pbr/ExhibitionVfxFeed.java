package com.kaleblangley.haikalat.demo.pbr;

import com.kaleblangley.haikalat.core.command.CommandBuffer;
import org.joml.Matrix4f;

/**
 * Station-owned HDR VFX draw hook.  The exhibition registers one HDR VFX
 * composite pass for its whole lifetime and forwards each frame to the active
 * station feed, so a station can record real VFX meshes/particles into the
 * production pass without owning pipeline topology.
 */
interface ExhibitionVfxFeed {
    /**
     * @param commands         command buffer of the HDR VFX composite pass
     * @param projection       frozen frame projection
     * @param view             frozen frame view
     * @param sceneDepthTexture single-sample scene depth attachment
     * @param width            output width
     * @param height           output height
     * @param frameIndex       station-local frame index (reset on phase change)
     * @param deltaSeconds     fixed station timestep
     */
    void record(CommandBuffer commands, Matrix4f projection, Matrix4f view,
                int sceneDepthTexture, int width, int height, int frameIndex,
                float deltaSeconds);
}
