package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

import static org.junit.jupiter.api.Assertions.*;

class LightVolumeHintsTest {
    @Test void hintsKeepStableIdentityAndSurfaceBytesWhileTheFrozenRevisionChanges() {
        var scene=new Scene(new Camera());
        scene.addLight(SceneLight.point(new Vector3f(1,2,-3),new Vector3f(1),3,10));
        scene.addLight(SceneLight.directional(new Vector3f(0,0,-1),new Vector3f(1),2));
        var before=scene.lightEntries(); var table=FrameLightTable.build(before,new Matrix4f(),ClusteredLightingSettings.defaults());
        long revision=scene.lightingRevision();
        var surface=LightTablePacker.allocate(2); LightTablePacker.pack(table,ShadowFramePlan.EMPTY,surface);
        byte[] original=new byte[surface.remaining()]; surface.get(original);
        var hints=new LightVolumeHints(0,false,false); scene.setLightVolumeHints(0,hints);
        assertEquals(before.getFirst().stableId(),scene.lightEntries().getFirst().stableId());
        assertEquals(before.getFirst().revision()+1,scene.lightEntries().getFirst().revision());
        assertEquals(revision+1,scene.lightingRevision()); assertEquals(LightVolumeHints.DEFAULT,before.getFirst().volumeHints());
        var updated=FrameLightTable.build(scene.lightEntries(),new Matrix4f(),ClusteredLightingSettings.defaults());
        LightTablePacker.pack(updated,ShadowFramePlan.EMPTY,surface); byte[] after=new byte[surface.remaining()]; surface.get(after);
        assertArrayEquals(original,after); assertEquals(80,LightTablePacker.RECORD_BYTES);
        var packed=ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()); LightVolumePacker.pack(updated,packed);
        assertEquals(1,packed.getFloat(0)); assertEquals(3,packed.getInt(4));
        assertEquals(0,packed.getFloat(16)); assertEquals(0,packed.getInt(20));
        assertEquals(0,packed.getInt(24)); assertEquals(0,packed.getInt(28));
        scene.setLightVolumeHints(0,hints); assertEquals(revision+1,scene.lightingRevision());
        assertThrows(IllegalArgumentException.class,()->new LightVolumeHints(-1,true,true));
        assertThrows(IllegalArgumentException.class,()->new LightVolumeHints(Float.POSITIVE_INFINITY,true,true));
    }
}
