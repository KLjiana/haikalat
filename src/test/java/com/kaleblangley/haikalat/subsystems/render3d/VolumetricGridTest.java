package com.kaleblangley.haikalat.subsystems.render3d;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VolumetricGridTest {
    private static ExternalCamera camera(Matrix4f view, Matrix4f projection, float near, float far) {
        return new ExternalCamera(view,projection,new Matrix4f(projection).mul(view),new Vector3f(),0,near,far,0);
    }

    @Test void stationaryGridReuseDoesNotRetainMovedOrFailedCameraCoordinates() {
        var state=new VolumetricFrameState();
        var projection=new Matrix4f().perspective(1,33f/17,.125f,64);
        var still=camera(new Matrix4f(),projection,.125f,64);
        var settings=VolumetricFogSettings.disabled();
        var scene=new Scene(still);
        var first=state.prepare(RenderFrameContext.capture(scene,still,33,17,1f/60,0,0,0,null),settings);
        state.commit();
        var equal=camera(new Matrix4f(),projection,.125f,64);
        var second=state.prepare(RenderFrameContext.capture(scene,equal,33,17,1f/60,1,1,0,null),settings);
        assertSame(first.grid(),second.grid(),"identical physical camera inputs share immutable boundaries and rays");
        state.commit();
        var moved=camera(new Matrix4f().translation(-3,0,0),projection,.125f,64);
        var failed=state.prepare(RenderFrameContext.capture(scene,moved,33,17,1f/60,2,2,0,null),settings);
        assertNotSame(second.grid(),failed.grid());
        assertEquals(second.grid().worldSample(0,0,1,new Vector3f()).x+3,
                failed.grid().worldSample(0,0,1,new Vector3f()).x,.000001);
        state.discard();
        assertSame(second,state.previous());
        var recovered=state.prepare(RenderFrameContext.capture(scene,equal,33,17,1f/60,3,3,0,null),settings);
        assertSame(second.grid(),recovered.grid());
        assertEquals(second.successfulIndex()+1,recovered.successfulIndex());
        assertEquals(second.timeSeconds()+1f/60,recovered.timeSeconds());
        state.commit();
        var resized=state.prepare(RenderFrameContext.capture(scene,equal,35,19,1f/60,4,4,0,null),settings);
        assertNotSame(recovered.grid(),resized.grid());assertEquals(35,resized.grid().width());
        state.discard();
        var zoom=camera(new Matrix4f(),new Matrix4f(projection).m00(projection.m00()*1.1f),.125f,64);
        var zoomed=state.prepare(RenderFrameContext.capture(scene,zoom,33,17,1f/60,5,5,0,null),settings);
        assertNotSame(recovered.grid(),zoomed.grid());
        assertNotEquals(recovered.grid().worldSample(0,0,1,new Vector3f()).x,zoomed.grid().worldSample(0,0,1,new Vector3f()).x);
    }

    @Test
    void perspectiveBoundariesMatchIndependentDoubleReferenceAndIncludeCameraToNear() {
        var camera=camera(new Matrix4f(),new Matrix4f().perspective((float)Math.toRadians(90),2,0.1f,100),0.1f,100);
        var grid=new VolumetricGrid(camera,1919,1079,16,64,80);
        assertEquals(120,grid.nx()); assertEquals(68,grid.ny());
        assertEquals(0,grid.boundary(0)); assertEquals(0.1f,grid.boundary(1)); assertEquals(80,grid.boundary(64));
        for (int k=1;k<=64;k++) {
            double expected=(double)0.1f*Math.exp(Math.log(80/(double)0.1f)*(k-1)/63);
            assertEquals(expected,grid.boundary(k),Math.max(0.000001,expected*0.000001));
            assertEquals(k==64?63:k,grid.interval(grid.boundary(k)));
        }
        assertEquals(0,grid.interval(0.01f));
        assertEquals(63,grid.interval(100));
        assertEquals(0.05f,grid.center(0));
    }

    @Test
    void partialEdgeColumnsAndObliquePerspectiveRaysUseWorldDistance() {
        float fov=(float)Math.toRadians(90);
        var camera=camera(new Matrix4f().translation(-3,-4,-5),new Matrix4f().perspective(fov,2,1,100),1,100);
        var grid=new VolumetricGrid(camera,33,17,16,8,50);
        // Last partial tile is [32,33] x [16,17], not the center of an imaginary full tile.
        double rx=(2*32.5/33-1)*2, ry=2*16.5/17-1;
        double pathScale=Math.sqrt(1+rx*rx+ry*ry);
        float endpoint=0.25f;
        assertEquals(endpoint*pathScale,grid.segmentLength(2,1,0,endpoint),0.000002);
        var sample=grid.worldSample(2,1,0,new Vector3f());
        assertEquals(3+0.5*rx,sample.x,0.000002);
        assertEquals(4+0.5*ry,sample.y,0.000002);
        assertEquals(4.5,sample.z,0.000002);
        assertEquals(0,grid.segmentLength(2,1,1,0.25f));
    }

    @Test
    void orthographicRaysStartOnTheCameraPlaneAndUseLinearSlices() {
        var camera=camera(new Matrix4f(),new Matrix4f().ortho(-4,4,-2,2,1,100),1,100);
        var grid=new VolumetricGrid(camera,32,16,16,8,16);
        assertFalse(grid.perspective());
        for(int k=0;k<=8;k++) assertEquals(k*2,grid.boundary(k));
        var left=grid.worldSample(0,0,0,new Vector3f());
        var right=grid.worldSample(1,0,0,new Vector3f());
        assertEquals(-2,left.x,0.000001); assertEquals(2,right.x,0.000001);
        assertEquals(-1,left.z); assertEquals(left.z,right.z);
        assertEquals(1.25f,grid.segmentLength(0,0,0,1.25f));
        assertEquals(new Vector3f(0,0,-1),grid.viewRay(1,0,new Vector3f()));
    }

    @Test
    void historyInterpolationUsesPhysicalCentersAndRejectsInvalidDepths() {
        var camera=camera(new Matrix4f(),new Matrix4f().perspective(1,1,0.1f,100),0.1f,100);
        var grid=new VolumetricGrid(camera,1,1,16,64,80);
        for(int k=0;k<64;k++) assertEquals(k,grid.historyLayer(grid.center(k)),0.00001f);
        for(int k=0;k<63;k++) {
            float depth=grid.center(k)*0.75f+grid.center(k+1)*0.25f;
            assertEquals(k+0.25f,grid.historyLayer(depth),0.00001f);
        }
        assertThrows(IllegalArgumentException.class,()->grid.historyLayer(-0.001f));
        assertThrows(IllegalArgumentException.class,()->grid.historyLayer(80.001f));
        assertThrows(IllegalArgumentException.class,()->grid.interval(Float.NaN));
    }

    @Test
    void unsupportedProjectionScaledViewAndUnrepresentableGridAreRejected() {
        var projection=new Matrix4f().perspective(1,1,0.1f,100);
        var scaled=camera(new Matrix4f().scale(2),projection,0.1f,100);
        assertThrows(IllegalArgumentException.class,()->new VolumetricGrid(scaled,32,32,16,64,80));
        var oblique=camera(new Matrix4f(),new Matrix4f(projection).m02(0.1f),0.1f,100);
        assertThrows(IllegalArgumentException.class,()->new VolumetricGrid(oblique,32,32,16,64,80));
        var camera=camera(new Matrix4f(),projection,0.1f,100);
        assertThrows(IllegalArgumentException.class,()->new VolumetricGrid(camera,32,32,16,1,80));
        assertThrows(IllegalArgumentException.class,()->new VolumetricGrid(camera,32,32,16,64,Float.POSITIVE_INFINITY));
        var grid=new VolumetricGrid(camera,1,1,16,64,200);
        assertEquals(100,grid.far());
        assertEquals(0,grid.worldSample(0,0,0,new Vector3f()).x,0.000001);
    }
}
