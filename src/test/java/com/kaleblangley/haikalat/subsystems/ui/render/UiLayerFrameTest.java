package com.kaleblangley.haikalat.subsystems.ui.render;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class UiLayerFrameTest {
    private static UiLayerDescription layer(String id, String parent) {
        return UiLayerDescription.builder(id, new UiScreenRect(10,20,80,40)).parent(parent).build();
    }
    @Test void nestedSubtreesAreRemovedFromDirectAndCompositeAtTheirOriginalPosition() {
        var parent=layer("parent", ""); var child=layer("child", "parent");
        var list=new UiDisplayList();var box=new UiScreenRect(0,0,20,20);
        list.addSolidQuad(box,0xff0000ff,UiBlendMode.PREMULTIPLIED_ALPHA)
                .beginLayer(parent).addSolidQuad(box,0x00ff00ff,UiBlendMode.PREMULTIPLIED_ALPHA)
                .beginLayer(child).addSolidQuad(box,0x0000ffff,UiBlendMode.PREMULTIPLIED_ALPHA)
                .endLayer().addSolidQuad(box,0xffffffff,UiBlendMode.PREMULTIPLIED_ALPHA).endLayer()
                .addSolidQuad(box,0x808080ff,UiBlendMode.PREMULTIPLIED_ALPHA);
        var snapshot=UiRenderSnapshot.capture(1,list);
        var frame=UiLayerFrame.build(snapshot,List.of(child,parent),Set.of("child","parent"));
        assertEquals(List.of(UiLayerFrame.Operation.draw(0),UiLayerFrame.Operation.composite("parent"),
                UiLayerFrame.Operation.draw(4)),frame.operations(""));
        assertEquals(List.of(UiLayerFrame.Operation.draw(1),UiLayerFrame.Operation.composite("child"),
                UiLayerFrame.Operation.draw(3)),frame.operations("parent"));
        assertEquals(List.of(UiLayerFrame.Operation.draw(2)),frame.operations("child"));
        assertEquals(5,frame.layers().stream().mapToLong(l->l.operations().stream().filter(o->o.batch()>=0).count()).sum()
                +frame.operations("").stream().filter(o->o.batch()>=0).count());
    }
    @Test void rejectsUnknownDuplicateOrMismatchedParentsBeforeDrawing() {
        var a=layer("a", "");var b=layer("b", "");
        assertThrows(IllegalArgumentException.class,()->UiLayerFrame.build(UiRenderSnapshot.capture(1,
                new UiDisplayList().beginLayer(a).endLayer()),List.of(),Set.of()));
        assertThrows(IllegalArgumentException.class,()->UiLayerFrame.build(UiRenderSnapshot.capture(1,
                new UiDisplayList().beginLayer(a).endLayer().beginLayer(a).endLayer()),List.of(a),Set.of("a")));
        assertThrows(IllegalArgumentException.class,()->UiLayerFrame.build(UiRenderSnapshot.capture(1,
                new UiDisplayList().beginLayer(a).beginLayer(b).endLayer().endLayer()),List.of(a,b),Set.of("a","b")));
    }
    @Test void budgetFallbackFlattensOnlyTheRejectedLayerWithoutDuplicatingItsPrimitives() {
        var a=layer("a", "");var b=layer("b", "a");
        var list=new UiDisplayList().beginLayer(a).beginLayer(b).addSolidQuad(new UiScreenRect(0,0,2,2),
                0xffffffff,UiBlendMode.PREMULTIPLIED_ALPHA).endLayer().endLayer();
        var frame=UiLayerFrame.build(UiRenderSnapshot.capture(1,list),List.of(a,b),Set.of("a"));
        assertEquals(List.of(UiLayerFrame.Operation.draw(0)),frame.operations("a"));
        assertNull(frame.layer("b"));
    }
    @Test void budgetCountsPaddedHalfFloatTargetsAndDisablesOptionalBlurBeforeGroupOpacity() {
        var layer=UiLayerDescription.builder("panel",new UiScreenRect(0,0,52,40)).blur(8,2).opacity(.5f).build();
        var full=UiCompositorPlan.build(UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(layer)).build());
        assertEquals(68L*56+2L*34*28,full.intermediatePixels());
        var limited=UiCompositorPlan.build(UiAttachmentOptions.builder().enableCompositor(true).layers(List.of(layer)).maximumIntermediatePixels(3000).build());
        assertEquals(List.of("panel"),limited.acceptedIds());assertEquals(52L*40,limited.intermediatePixels());
        assertEquals(Set.of("panel"),limited.disabledEffects());assertEquals(0,limited.directFallbacks());
        assertEquals(UiCompositor.FallbackReason.OPTIONAL_EFFECT_BUDGET,limited.fallbacks().getFirst().reason());
    }
}
