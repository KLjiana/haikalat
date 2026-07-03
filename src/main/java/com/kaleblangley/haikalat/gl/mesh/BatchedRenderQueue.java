package com.kaleblangley.haikalat.gl.mesh;

import com.kaleblangley.haikalat.gl.command.RenderCommand;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

public final class BatchedRenderQueue {
    private static final Comparator<Entry> ORDER = Comparator.comparing(Entry::key);

    private final List<Entry> entries = new ArrayList<>();

    public void submit(DrawSortKey key, RenderCommand command) {
        entries.add(new Entry(Objects.requireNonNull(key, "key"), Objects.requireNonNull(command, "command")));
    }

    public int flush() {
        entries.sort(ORDER);
        int executed = 0;
        for (Entry entry : entries) {
            entry.command.execute();
            executed++;
        }
        entries.clear();
        return executed;
    }

    public void clear() {
        entries.clear();
    }

    public int size() {
        return entries.size();
    }

    private record Entry(DrawSortKey key, RenderCommand command) {
    }
}
