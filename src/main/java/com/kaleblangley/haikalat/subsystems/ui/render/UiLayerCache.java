package com.kaleblangley.haikalat.subsystems.ui.render;

import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Exact dependency fingerprints, not caller dirty flags. No native resources are owned here. */
final class UiLayerCache {
    record Key(Map<String,String> parts,boolean eligible) {
        Key { parts=Collections.unmodifiableMap(new LinkedHashMap<>(parts)); }
        String reason(Key previous) {
            for(var entry:parts.entrySet())if(!Objects.equals(entry.getValue(),previous.parts.get(entry.getKey())))return entry.getKey();
            return "HIT";
        }
    }
    private record Stored(Key key,String storage) { }
    private final Map<String,Stored> valid=new HashMap<>();
    private final Map<String,Key> pending=new HashMap<>();
    private final Map<String,String> pendingStorage=new HashMap<>();
    private boolean failed;

    void begin(){pending.clear();pendingStorage.clear();}
    String evaluate(String id,Key key,String storage,UiLayerDescription.CachePolicy policy,boolean backdrop) {
        if(backdrop)return "DYNAMIC_BACKDROP";
        if(policy==UiLayerDescription.CachePolicy.NONE)return "CACHE_DISABLED";
        if(!key.eligible())return "UNTRACKED_DEPENDENCY";
        pending.put(id,key);pendingStorage.put(id,storage);
        Stored previous=valid.get(id);
        if(previous==null)return failed?"FAILED_FRAME":"FIRST_USE";
        if(!previous.storage.equals(storage))return "TARGET_REPLACED";
        return key.reason(previous.key);
    }
    void complete(Throwable failure) {
        if(failure!=null){valid.clear();failed=true;}
        else {
            valid.keySet().retainAll(pending.keySet());
            pending.forEach((id,key)->valid.put(id,new Stored(key,pendingStorage.get(id))));failed=false;
        }
        pending.clear();pendingStorage.clear();
    }

    static Map<String,Key> keys(UiLayerFrame frame,UiRenderSnapshot snapshot,UiRenderer renderer,
                                Map<String,UiScreenRect> views,Map<String,String> mapping) {
        var result=new LinkedHashMap<String,Key>();
        for(var layer:frame.layers())key(layer,frame,snapshot,renderer,views,mapping,result);
        return result;
    }
    private static Key key(UiLayerFrame.Layer layer,UiLayerFrame frame,UiRenderSnapshot snapshot,UiRenderer renderer,
                           Map<String,UiScreenRect> views,Map<String,String> mapping,Map<String,Key> result) {
        String id=layer.description().id();if(result.containsKey(id))return result.get(id);
        var view=views.get(id);if(view==null)return new Key(Map.of(),false);
        var list=snapshot.displayList();var batches=snapshot.batches();
        var paint=new Digest();var geometry=new Digest();var dependencies=new Digest();var children=new Digest();
        boolean eligible=true,hasGlyph=false;
        for(var operation:layer.operations()) {
            if(operation.child()!=null) {
                var child=frame.layer(operation.child());var childKey=key(child,frame,snapshot,renderer,views,mapping,result);
                eligible&=childKey.eligible()&&child.description().cachePolicy()!=UiLayerDescription.CachePolicy.NONE&&!child.description().effects().contains(UiLayerDescription.Effect.BACKDROP_BLUR);
                children.add(operation.child()).add(childKey.parts.toString()).add(child.description().opacity())
                        .add(child.bounds().x()-view.x()).add(child.bounds().y()-view.y());
                continue;
            }
            int batch=operation.batch(),primitive=batches.firstPrimitive(batch);
            paint.add(batches.shader(batch).ordinal()).add(batches.blend(batch).ordinal()).add(batches.quadCount(batch));
            if(batches.hasClip(batch)) {
                var clip=batches.clip(batch);geometry.add(clip.x()-view.x()).add(clip.y()-view.y()).add(clip.width()).add(clip.height());
            } else geometry.add("NO_CLIP");
            if(batches.shader(batch)==UiShaderVariant.TEXTURED) {
                long image=batches.imageId(batch);long generation=image<0?-1:renderer.imageGeneration(image);
                if(generation<0)eligible=false;
                dependencies.add(image).add(generation).add(image<0?"raw":String.valueOf(renderer.imageRegion(image)));
            }
            if(batches.shader(batch)==UiShaderVariant.GLYPH)hasGlyph=true;
            paint.add(batches.texture(batch)).add(batches.sampler(batch));
            if(batches.shader(batch)==UiShaderVariant.SDF) {
                paint.add(list.sdfKind(primitive).ordinal()).add(list.sdfGradientAngle(primitive));
                for(int j=0;j<4;j++)paint.add(list.sdfRadius(primitive,j)).add(list.sdfParameter(primitive,j));
                paint.add(list.sdfFillColor(primitive)).add(list.sdfBorderColor(primitive))
                        .add(list.sdfGradientStartColor(primitive)).add(list.sdfGradientEndColor(primitive));
            }
            if(batches.shader(batch)==UiShaderVariant.GLYPH)paint.add(list.textEffectType(primitive))
                    .add(list.textEffectColor1(primitive)).add(list.textEffectColor2(primitive)).add(list.textEffectThickness(primitive))
                    .add(list.textEffectOffsetX(primitive)).add(list.textEffectOffsetY(primitive)).add(list.textEffectBlur(primitive)).add(list.textEffectAngle(primitive));
            for(int q=batches.firstQuad(batch);q<batches.firstQuad(batch)+batches.quadCount(batch);q++) {
                paint.add(list.quadColor(q)).add(list.quadU0(q)).add(list.quadV0(q)).add(list.quadU1(q)).add(list.quadV1(q));
                for(int corner=0;corner<4;corner++) {
                    double x=list.quadX(q)+(corner==1||corner==2?list.quadWidth(q):0);
                    double y=list.quadY(q)+(corner>=2?list.quadHeight(q):0);
                    geometry.add(list.quadTransformM00(q)*x+list.quadTransformM01(q)*y+list.quadTransformX(q)-view.x())
                            .add(list.quadTransformM10(q)*x+list.quadTransformM11(q)*y+list.quadTransformY(q)-view.y());
                }
            }
        }
        var description=layer.description();var parts=new LinkedHashMap<String,String>();
        parts.put("INSTANCE",Long.toString(layer.instanceId()));
        parts.put("TARGET_MAPPING",mapping.get(id));
        parts.put("AUTHOR_DIRTY",Long.toString(description.dirtyRevision()));
        parts.put("EFFECT_PARAMETERS",description.effects()+"/"+description.maskMode()+"/"+description.blurRadius()+"/"+description.blurDownsample()+"/"+description.framebufferScale());
        parts.put("CLIP_LAYOUT",geometry.add(view.width()).add(view.height()).finish());
        parts.put("IMAGE_DEPENDENCY",dependencies.finish());
        parts.put("GLYPH_ATLAS",hasGlyph?Long.toString(renderer.glyphGeneration()):"none");
        parts.put("CHILD_CHANGED",children.finish());parts.put("PAINT_CONTENT",paint.finish());
        Key key=new Key(parts,eligible);result.put(id,key);return key;
    }
    private static final class Digest {
        private final MessageDigest digest;
        Digest(){try{digest=MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
        Digest add(Object value){byte[] bytes=String.valueOf(value).getBytes(StandardCharsets.UTF_8);digest.update(bytes);digest.update((byte)0);return this;}
        String finish(){return HexFormat.of().formatHex(digest.digest());}
    }
}
