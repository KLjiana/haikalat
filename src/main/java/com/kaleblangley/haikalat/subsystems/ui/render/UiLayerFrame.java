package com.kaleblangley.haikalat.subsystems.ui.render;

import java.util.*;

/** A render-thread plan derived only from one immutable snapshot and its registered topology. */
final class UiLayerFrame {
    static final String ROOT = "";
    record Operation(int batch, String child) {
        static Operation draw(int batch) { return new Operation(batch, null); }
        static Operation composite(String child) { return new Operation(-1, child); }
    }
    record Layer(UiLayerDescription description, UiScreenRect bounds, UiScreenRect clip,
                 List<Operation> operations, long instanceId) { }

    private final Map<String, Layer> layers;
    private final List<Operation> root;

    private UiLayerFrame(Map<String, Layer> layers, List<Operation> root) {
        this.layers = Collections.unmodifiableMap(new LinkedHashMap<>(layers));
        this.root = List.copyOf(root);
    }

    static UiLayerFrame build(UiRenderSnapshot snapshot, List<UiLayerDescription> registered,
                              Set<String> offscreenIds) {
        Map<String, UiLayerDescription> topology = new HashMap<>();
        for (var description : registered) {
            if (topology.put(description.id(), description) != null)
                throw new IllegalArgumentException("duplicate registered layer: " + description.id());
        }
        var list = snapshot.displayList();
        var batches = snapshot.batches();
        Map<Integer, Integer> batchAt = new HashMap<>();
        for (int i = 0; i < batches.size(); i++) batchAt.put(batches.firstPrimitive(i), i);
        Map<String, List<Operation>> operations = new LinkedHashMap<>();
        operations.put(ROOT, new ArrayList<>());
        Map<String, Layer> layers = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        Deque<String> nesting = new ArrayDeque<>();
        Deque<String> owners = new ArrayDeque<>();
        owners.push(ROOT);
        Deque<UiScreenRect> clips = new ArrayDeque<>();
        for (int i = 0; i < list.primitiveCount(); i++) {
            switch (list.primitiveKind(i)) {
                case PUSH_CLIP -> {
                    UiScreenRect clip = list.primitiveClip(i);
                    clips.push(clips.isEmpty() ? clip : intersection(clips.peek(), clip));
                }
                case POP_CLIP -> clips.pop();
                case LAYER_BEGIN -> {
                    var description = list.primitiveLayer(i);
                    UiCompositor.validateSupported(description);
                    String id = description.id();
                    var declared = topology.get(id);
                    if (declared == null) throw new IllegalArgumentException("unregistered UI layer: " + id);
                    if (!seen.add(id)) throw new IllegalArgumentException("duplicate snapshot layer: " + id);
                    String parent = nesting.isEmpty() ? ROOT : nesting.peek();
                    if (!parent.equals(description.parentId()) || !parent.equals(declared.parentId()))
                        throw new IllegalArgumentException("snapshot parent mismatch for layer: " + id);
                    if (!declared.effects().equals(description.effects()))
                        throw new IllegalArgumentException("effect structure requires reconfigure: " + id);
                    nesting.push(id);
                    if (nesting.size() > 8) throw new IllegalArgumentException("UI layer nesting exceeds 8: " + id);
                    if (offscreenIds.contains(id)) {
                        operations.get(owners.peek()).add(Operation.composite(id));
                        var own = new ArrayList<Operation>();
                        operations.put(id, own);
                        layers.put(id, new Layer(description, list.primitiveLayerBounds(i),
                                clips.isEmpty() ? null : clips.peek(), own, list.primitiveLayerInstance(i)));
                        owners.push(id);
                    } else owners.push(owners.peek());
                }
                case LAYER_END -> { nesting.pop(); owners.pop(); }
                default -> {
                    Integer batch = batchAt.get(i);
                    if (batch != null) operations.get(owners.peek()).add(Operation.draw(batch));
                }
            }
        }
        Map<String, Layer> immutable = new LinkedHashMap<>();
        layers.forEach((id, layer) -> immutable.put(id, new Layer(layer.description(), layer.bounds(),
                layer.clip(), List.copyOf(layer.operations()),layer.instanceId())));
        return new UiLayerFrame(immutable, operations.get(ROOT));
    }

    Layer layer(String id) { return layers.get(id); }
    Collection<Layer> layers() { return layers.values(); }
    List<Operation> operations(String id) { return id.isEmpty() ? root : layers.get(id).operations(); }

    /** Include unclipped overflow and effect outsets of nested layers in the actual raster extent. */
    UiScreenRect rasterBounds(String id,UiRenderSnapshot snapshot,java.util.function.Predicate<UiLayerDescription> filtered) {
        Layer layer=layers.get(id);UiScreenRect result=layer.bounds();
        var batches=snapshot.batches();var list=snapshot.displayList();
        for(var operation:layer.operations()) {
            if(operation.child()!=null) {
                var child=layers.get(operation.child());var bounds=rasterBounds(operation.child(),snapshot,filtered);
                if(filtered.test(child.description())) {
                    double p=child.description().blurRadius();bounds=new UiScreenRect(bounds.x()-p,bounds.y()-p,bounds.width()+2*p,bounds.height()+2*p);
                }
                result=union(result,bounds);continue;
            }
            int batch=operation.batch();
            for(int q=batches.firstQuad(batch);q<batches.firstQuad(batch)+batches.quadCount(batch);q++) {
                double left=Double.POSITIVE_INFINITY,top=left,right=Double.NEGATIVE_INFINITY,bottom=right;
                for(int corner=0;corner<4;corner++) {
                    double x=list.quadX(q)+(corner==1||corner==2?list.quadWidth(q):0),y=list.quadY(q)+(corner>=2?list.quadHeight(q):0);
                    double tx=list.quadTransformM00(q)*x+list.quadTransformM01(q)*y+list.quadTransformX(q);
                    double ty=list.quadTransformM10(q)*x+list.quadTransformM11(q)*y+list.quadTransformY(q);
                    left=Math.min(left,tx);right=Math.max(right,tx);top=Math.min(top,ty);bottom=Math.max(bottom,ty);
                }
                var bounds=new UiScreenRect(left,top,right-left,bottom-top);
                if(batches.hasClip(batch))bounds=bounds.intersect(batches.clip(batch));
                result=union(result,bounds);
            }
        }
        if(layer.description().maskMode()==UiLayerDescription.MaskMode.CLIP)result=result.intersect(layer.bounds());
        return layer.clip()==null?result:result.intersect(layer.clip());
    }
    private static UiScreenRect union(UiScreenRect a,UiScreenRect b) {
        if(a.isEmpty())return b;if(b.isEmpty())return a;
        double x=Math.min(a.x(),b.x()),y=Math.min(a.y(),b.y());
        return new UiScreenRect(x,y,Math.max(a.right(),b.right())-x,Math.max(a.bottom(),b.bottom())-y);
    }

    static UiScreenRect intersection(UiScreenRect a, UiScreenRect b) {
        double x = Math.max(a.x(), b.x()), y = Math.max(a.y(), b.y());
        return new UiScreenRect(x, y, Math.max(0, Math.min(a.x()+a.width(),b.x()+b.width())-x),
                Math.max(0, Math.min(a.y()+a.height(),b.y()+b.height())-y));
    }
}
