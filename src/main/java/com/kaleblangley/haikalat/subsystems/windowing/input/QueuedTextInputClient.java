package com.kaleblangley.haikalat.subsystems.windowing.input;

import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/** 窗口线程写入、UI/update 线程排空的不可变 IME 命令队列。 */
public final class QueuedTextInputClient implements TextInputClient {
    public sealed interface Command {
        record Started() implements Command { }
        record Updated(ImeComposition composition) implements Command {
            public Updated { Objects.requireNonNull(composition, "composition"); }
        }
        record Committed(String text) implements Command {
            public Committed { Objects.requireNonNull(text, "text"); }
        }
        record Cancelled() implements Command { }
    }

    private final ConcurrentLinkedQueue<Command> commands = new ConcurrentLinkedQueue<>();

    @Override public void compositionStarted() { commands.add(new Command.Started()); }
    @Override public void compositionUpdated(ImeComposition value) {
        commands.add(new Command.Updated(value));
    }
    @Override public void compositionCommitted(String text) {
        commands.add(new Command.Committed(text));
    }
    @Override public void compositionCancelled() { commands.add(new Command.Cancelled()); }

    /** 排空调用开始前及过程中已经可见的命令，并保留 FIFO 顺序。 */
    public int drain(Consumer<? super Command> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        int count = 0;
        for (Command command; (command = commands.poll()) != null; ) {
            consumer.accept(command);
            count++;
        }
        return count;
    }

    public boolean isEmpty() { return commands.isEmpty(); }
    public void clear() { commands.clear(); }
}
