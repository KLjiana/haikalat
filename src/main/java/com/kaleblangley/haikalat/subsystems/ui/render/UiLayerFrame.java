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

    static UiScreenRect intersection(UiScreenRect a, UiScreenRect b) {
        double x = Math.max(a.x(), b.x()), y = Math.max(a.y(), b.y());
        return new UiScreenRect(x, y, Math.max(0, Math.min(a.x()+a.width(),b.x()+b.width())-x),
                Math.max(0, Math.min(a.y()+a.height(),b.y()+b.height())-y));
    }
}
