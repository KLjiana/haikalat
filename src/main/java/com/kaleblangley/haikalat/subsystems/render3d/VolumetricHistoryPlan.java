package com.kaleblangley.haikalat.subsystems.render3d;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/** Bounded world-space dirty domains, compared only with the last successful input. */
final class VolumetricHistoryPlan {
    static final int CAPACITY = VolumetricResources.DIRTY_DOMAINS_BYTES / 32;
    enum Reason { NONE, FIRST_FRAME, SCENE, GRID, CAMERA_CUT, GLOBAL_MEDIUM,
        DIRECTIONAL_LIGHT, DIRECTIONAL_SHADOW, DOMAIN_OVERFLOW, FRAME_FAILURE, RESIZE, EXPLICIT_RESET }
    private final ByteBuffer domains = ByteBuffer.allocateDirect(VolumetricResources.DIRTY_DOMAINS_BYTES)
            .order(ByteOrder.nativeOrder());
    private FrameLightTable previousLights, pendingLights;
    private ShadowFramePlan previousShadows = ShadowFramePlan.EMPTY, pendingShadows;
    private long previousScene, pendingScene;
    private int count;
    private int nativeMediumCount;
    private final ByteBuffer nativeMediumDomains=ByteBuffer.allocateDirect(16*32).order(ByteOrder.nativeOrder());
    private Reason reason = Reason.FIRST_FRAME;
    private boolean shadowRefit;
    private boolean lightTurnedOff;
    private boolean nativeLightChanged;
    static final int LIGHT_OFF_FLAG = 128;
    private SceneRevisionSnapshot previousRevisions,pendingRevisions;
    private boolean casterContentChanged,shadowPolicyChanged;
    private Object previousDirectionalPolicy,pendingDirectionalPolicy;
    private LocalShadowPipelineSettings previousLocalPolicy,pendingLocalPolicy;
    private DirectionalCascadeSettings previousCascades,pendingCascades;
    private VolumetricFrameState.Frame currentFrame,oldFrame;
    private Reason invalidationCause=Reason.FIRST_FRAME;
    private final org.joml.Vector3f footprintPoint=new org.joml.Vector3f();
    private final org.joml.Matrix4f currentView=new org.joml.Matrix4f(),oldView=new org.joml.Matrix4f();
    private float projectionX,projectionY,maximumRayLength;

    void shadowPolicy(Object directional,LocalShadowPipelineSettings local,DirectionalCascadeSettings cascades) {
        pendingDirectionalPolicy=directional; pendingLocalPolicy=local; pendingCascades=cascades;
    }

    void prepare(RenderFrameContext context, VolumetricFrameState.Frame current,
                 VolumetricFrameState.Frame previous, boolean valid, FrameLightTable lights,
                 ShadowFramePlan shadows) {
        domains.clear(); nativeMediumDomains.clear(); count = 0; nativeMediumCount = 0; reason = Reason.NONE; shadowRefit = false; lightTurnedOff = false; nativeLightChanged = false;
        currentFrame=current; oldFrame=previous;
        current.camera().getViewMatrix(currentView); if(previous!=null)previous.camera().getViewMatrix(oldView);
        var projection=current.camera().projection();projectionX=projection.m00();projectionY=projection.m11();
        double rayX=(1+Math.abs(projection.m20()))/projectionX, rayY=(1+Math.abs(projection.m21()))/projectionY;
        maximumRayLength=(float)Math.sqrt(1+rayX*rayX+rayY*rayY);
        pendingScene = context.revisions().sceneGeneration(); pendingLights = lights;
        pendingRevisions=context.revisions();
        casterContentChanged=previousRevisions==null || previousRevisions.membershipRevision()!=pendingRevisions.membershipRevision()
                || previousRevisions.transformModelRevision()!=pendingRevisions.transformModelRevision()
                || previousRevisions.materialRenderStateRevision()!=pendingRevisions.materialRenderStateRevision();
        shadowPolicyChanged=!java.util.Objects.equals(previousDirectionalPolicy,pendingDirectionalPolicy)
                || !java.util.Objects.equals(previousLocalPolicy,pendingLocalPolicy) || !java.util.Objects.equals(previousCascades,pendingCascades);
        pendingShadows = shadows == null ? ShadowFramePlan.EMPTY : shadows;
        if (!valid || previous == null) full(invalidationCause);
        else if (previousScene != pendingScene) full(Reason.SCENE);
        else if (!sameGrid(previous, current)) full(Reason.GRID);
        else if (cameraCut(previous, current)) full(Reason.CAMERA_CUT);
        else if (!previous.settings().globalMedium().equals(current.settings().globalMedium())
                || previous.settings().anisotropy() != current.settings().anisotropy()
                || previous.settings().noiseSeed() != current.settings().noiseSeed()
                || previous.settings().history() != current.settings().history()) full(Reason.GLOBAL_MEDIUM);
        if (previous != null) changedVolumes(previous.settings().localVolumes(), current.settings().localVolumes());
        changedLights(lights);
        changedShadows();
        domains.position(0).limit(count * 32);
        nativeMediumDomains.position(0).limit(nativeMediumCount*32);
    }

    private static boolean sameGrid(VolumetricFrameState.Frame a, VolumetricFrameState.Frame b) {
        VolumetricGrid x = a.grid(), y = b.grid();
        return x.width()==y.width() && x.height()==y.height() && x.nz()==y.nz()
                && x.tilePixels()==y.tilePixels() && x.near()==y.near() && x.far()==y.far()
                && x.perspective()==y.perspective() && a.camera().projection().equals(b.camera().projection());
    }
    private static boolean cameraCut(VolumetricFrameState.Frame a, VolumetricFrameState.Frame b) {
        float limit = Math.max(1, b.grid().far() * 0.25f);
        if (a.camera().positionInternal().distanceSquared(b.camera().positionInternal()) > limit*limit) return true;
        var oldForward = a.camera().inverseView().transformDirection(0,0,-1,new org.joml.Vector3f());
        var newForward = b.camera().inverseView().transformDirection(0,0,-1,new org.joml.Vector3f());
        return oldForward.dot(newForward) < 0.8f;
    }
    private void changedVolumes(List<LocalFogVolume> old, List<LocalFogVolume> now) {
        for (int i=0;i<Math.max(old.size(),now.size());i++) {
            LocalFogVolume a=i<old.size()?old.get(i):null, b=i<now.size()?now.get(i):null;
            if (java.util.Objects.equals(a,b)) continue;
            volume(a); volume(b);
            nativeMedium(a); nativeMedium(b);
        }
    }
    private void volume(LocalFogVolume volume) {
        if (volume == null) return;
        var c=volume.centerInternal(); var e=volume.extentInternal();
        domain(c.x,c.y,c.z,volume.shape()==LocalFogVolume.Shape.SPHERE ? e.x : -1,e.x,e.y,e.z);
    }
    private void nativeMedium(LocalFogVolume volume) {
        if(volume==null || volume.extinction()<=0 || volume.albedoInternal().lengthSquared()==0)return;
        // At most eight old and eight current volumes. Keep exact boundaries even
        // when coarse light domains overflow; final AA needs the previous footprint.
        var c=volume.centerInternal();var e=volume.extentInternal();int offset=nativeMediumCount++*32;
        nativeMediumDomains.putFloat(offset,c.x).putFloat(offset+4,c.y).putFloat(offset+8,c.z)
                .putFloat(offset+12,volume.shape()==LocalFogVolume.Shape.SPHERE?e.x:-1)
                .putFloat(offset+16,e.x).putFloat(offset+20,e.y).putFloat(offset+24,e.z).putFloat(offset+28,0);
    }
    private void changedLights(FrameLightTable now) {
        if (now != null) for (int i=0;i<now.totalCount();i++) {
            var current=now.record(i); var old=findAtIndex(previousLights,current.stableId(),i);
            if (!sameLight(old,current)) { light(old,active(old) && !active(current)); light(current); }
            if (active(current) && !current.volumeHints().temporalAccumulation()) light(current);
        }
        if (previousLights != null) for (int i=0;i<previousLights.totalCount();i++) {
            var old=previousLights.record(i);
            if (findAtIndex(now,old.stableId(),i)==null) light(old,true);
        }
    }
    private static FrameLightTable.Record findAtIndex(FrameLightTable table,long stableId,int index) {
        // Stable frames keep the sorted table order; verify identity before avoiding the boxed map lookup.
        if (table!=null && index<table.totalCount()) {
            var record=table.record(index);
            if (record.stableId()==stableId) return record;
        }
        return find(table,stableId);
    }
    private static FrameLightTable.Record find(FrameLightTable table,long stableId) {
        if (table==null) return null;
        int index=table.frameLightIndex(stableId); return index<0 ? null : table.record(index);
    }
    private static boolean sameLight(FrameLightTable.Record a,FrameLightTable.Record b) {
        if (a==null || b==null) return a==b;
        // View positions change with the camera and are never light-content revisions.
        return a.type()==b.type() && a.position().equals(b.position()) && a.direction().equals(b.direction())
                && a.color().equals(b.color()) && a.intensity()==b.intensity() && a.range()==b.range()
                && a.innerConeRadians()==b.innerConeRadians() && a.outerConeRadians()==b.outerConeRadians()
                && a.castShadows()==b.castShadows() && a.volumeHints().equals(b.volumeHints());
    }
    private static boolean active(FrameLightTable.Record r) {
        return r!=null && r.intensity()>0 && r.volumeHints().scatteringIntensity()>0;
    }
    private void light(FrameLightTable.Record light) {
        light(light,false);
    }
    private void light(FrameLightTable.Record light,boolean turnedOff) {
        if (!active(light)) return;
        lightTurnedOff |= turnedOff;
        nativeLightChanged = true;
        if (light.type()==LightType.DIRECTIONAL) { full(Reason.DIRECTIONAL_LIGHT); return; }
        var p=light.position(); // Sphere coarse rejection uses padded radius; the otherwise unused extent.x
        // keeps the physical light radius for native visible-depth response.
        domain(p.x,p.y,p.z,light.range(),light.range(),0,0,turnedOff ? 1 : 2);
    }
    private void changedShadows() {
        var old=previousShadows.directional().orElse(null);
        var now=pendingShadows.directional().orElse(null);
        long oldId=old==null ? -1 : old.entry().stableId(), nowId=now==null ? -1 : now.entry().stableId();
        if (oldId!=nowId) {
            if (usesShadow(find(previousLights,oldId)) || usesShadow(find(pendingLights,nowId))) full(Reason.DIRECTIONAL_SHADOW);
        }
        if (now!=null && usesShadow(find(pendingLights,nowId)) && shadowPolicyChanged) full(Reason.DIRECTIONAL_SHADOW);
        boolean directionalProjectionChanged=old==null || now==null || !old.matrices().equals(now.matrices());
        if (now!=null && usesShadow(find(pendingLights,nowId))) for (var miss:now.missReasons()) {
            if (miss==ShadowFramePlan.MissReason.NONE) continue;
            if (contentMiss(miss)) full(Reason.DIRECTIONAL_SHADOW);
            else if(directionalProjectionChanged) shadowRefit=true;
        }
        for (var slot:pendingShadows.points()) {
            var previous=previousShadows.points().stream().filter(s->s.entry().stableId()==slot.entry().stableId()).findFirst().orElse(null);
            if (shadowPolicyChanged || previous==null || previous.slot()!=slot.slot() || slot.dirty() && contentMiss(slot.missReason())) shadowLight(slot.entry().stableId());
            else if (slot.dirty() && !previous.faceMatrices().equals(slot.faceMatrices())) shadowRefit=true;
        }
        for (var slot:pendingShadows.spots()) {
            var previous=previousShadows.spots().stream().filter(s->s.entry().stableId()==slot.entry().stableId()).findFirst().orElse(null);
            if (shadowPolicyChanged || previous==null || previous.slot()!=slot.slot() || slot.dirty() && contentMiss(slot.missReason())) shadowLight(slot.entry().stableId());
            else if (slot.dirty() && !previous.lightSpaceMatrix().equals(slot.lightSpaceMatrix())) shadowRefit=true;
        }
        for (var slot:previousShadows.points()) if (pendingShadows.points().stream().noneMatch(s->s.entry().stableId()==slot.entry().stableId())) shadowLight(slot.entry().stableId());
        for (var slot:previousShadows.spots()) if (pendingShadows.spots().stream().noneMatch(s->s.entry().stableId()==slot.entry().stableId())) shadowLight(slot.entry().stableId());
    }
    private boolean contentMiss(ShadowFramePlan.MissReason miss) {
        return switch(miss) {
            case NONE,CAMERA_CASCADE -> false;
            case CASTER_MEMBERSHIP,TRANSFORM_MODEL,MATERIAL,DEFORMATION -> casterContentChanged;
            // An uncached pass reports SETTINGS on every draw, including an unchanged scene.
            case SETTINGS -> shadowPolicyChanged || casterContentChanged;
            default -> true;
        };
    }
    private static boolean usesShadow(FrameLightTable.Record r) { return active(r) && r.castShadows() && r.volumeHints().useAllocatedShadow(); }
    private void shadowLight(long id) {
        var a=find(previousLights,id); var b=find(pendingLights,id);
        if (usesShadow(a)) light(a); if (usesShadow(b)) light(b);
    }
    private void domain(float x,float y,float z,float radius,float ex,float ey,float ez) {
        domain(x,y,z,radius,ex,ey,ez,0);
    }
    private void domain(float x,float y,float z,float radius,float ex,float ey,float ez,int nativeLightKind) {
        if (reason!=Reason.NONE) return;
        if (count==CAPACITY) { full(Reason.DOMAIN_OVERFLOW); return; }
        // Reject the interpolation footprint as well as the exact physical light/medium domain.
        // Otherwise a changed sample inside the old domain can leak through a neighboring history texel.
        float bound=radius>=0 ? radius : (float)Math.sqrt(ex*ex+ey*ey+ez*ez);
        float padding=footprint(currentFrame,currentView,x,y,z,bound);
        if(oldFrame!=null)padding=Math.max(padding,footprint(oldFrame,oldView,x,y,z,bound));
        if(radius>=0)radius+=padding;else {ex+=padding;ey+=padding;ez+=padding;}
        int offset=count++*32;
        domains.putFloat(offset,x).putFloat(offset+4,y).putFloat(offset+8,z).putFloat(offset+12,radius)
                .putFloat(offset+16,ex).putFloat(offset+20,ey).putFloat(offset+24,ez).putFloat(offset+28,nativeLightKind);
    }
    private float footprint(VolumetricFrameState.Frame frame,org.joml.Matrix4f view,float x,float y,float z,float radius) {
        var grid=frame.grid();view.transformPosition(x,y,z,footprintPoint);
        float depth=Math.max(0,Math.min(grid.far(),-footprintPoint.z+radius));
        int layer=grid.interval(depth);
        float dz=grid.boundary(Math.min(grid.nz(),layer+2))-grid.boundary(Math.max(0,layer-1));
        if(grid.perspective())dz*=maximumRayLength;
        float scale=grid.perspective() ? depth : 1;
        float dx=2*scale*grid.tilePixels()/(projectionX*grid.width());
        float dy=2*scale*grid.tilePixels()/(projectionY*grid.height());
        return (float)Math.sqrt(dx*dx+dy*dy+dz*dz);
    }
    void invalidate(Reason cause) { invalidationCause=cause; }
    private void full(Reason value) { if (reason==Reason.NONE) reason=value; count=0; }
    int count() { return count; } Reason reason() { return reason; } boolean shadowRefit() { return shadowRefit; }
    boolean nativeReactive() { return nativeLightChanged || nativeMediumCount>0; }
    boolean nativeLightFull() { return nativeLightChanged && reason!=Reason.NONE; }
    int nativeMediumCount() { return nativeMediumCount; }
    ByteBuffer nativeMediumDomains() { return nativeMediumDomains; }
    int shaderReason() { return reason.ordinal() | (lightTurnedOff && reason!=Reason.NONE ? LIGHT_OFF_FLAG : 0); }
    ByteBuffer domains() { return domains; }
    void commit() {
        previousLights=pendingLights; previousShadows=pendingShadows; previousScene=pendingScene; previousRevisions=pendingRevisions;
        previousDirectionalPolicy=pendingDirectionalPolicy; previousLocalPolicy=pendingLocalPolicy; previousCascades=pendingCascades;
        invalidationCause=Reason.FIRST_FRAME;
        discard();
    }
    void discard() { pendingLights=null; pendingShadows=null; }
}
