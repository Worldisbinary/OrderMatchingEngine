package com.ome.engine;

import com.ome.command.Command;
import com.ome.command.CommandCodec;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only, write-ahead log of every command, one line each (see {@link CommandCodec}).
 *
 * <p>Because the engine is deterministic, the commands are the whole state: replaying the
 * journal into a fresh engine rebuilds the same books, ids and trades. A crash in the middle
 * of a write can leave a partial last line; it is ignored on read and trimmed before new
 * commands are appended.
 */
public final class CommandJournal implements Closeable {

    static final String HEADER = "# order-matching-engine journal v1";

    private final BufferedWriter writer;

    private CommandJournal(BufferedWriter writer) {
        this.writer = writer;
    }

    /** Opens (or creates) a journal for appending, trimming a torn final line first. */
    public static CommandJournal openForAppend(Path file) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.exists(file)) {
            trimTornTail(file);
        }
        boolean fresh = !Files.exists(file) || Files.size(file) == 0;
        BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        if (fresh) {
            writer.write(HEADER);
            writer.write('\n');
            writer.flush();
        }
        return new CommandJournal(writer);
    }

    /** Buffers one command; call {@link #flush()} to hand it to the operating system. */
    public void append(Command command) throws IOException {
        writer.write(CommandCodec.encode(command));
        writer.write('\n');
    }

    public void flush() throws IOException {
        writer.flush();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }

    /**
     * Reads every complete command in the journal.
     *
     * @throws IOException if a complete line cannot be decoded (the journal is corrupt)
     */
    public static List<Command> read(Path file) throws IOException {
        boolean complete = endsWithNewline(file);
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (!complete && !lines.isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        List<Command> commands = new ArrayList<>(lines.size());
        int lineNumber = 0;
        for (String line : lines) {
            lineNumber++;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                commands.add(CommandCodec.decode(line));
            } catch (IllegalArgumentException e) {
                throw new IOException("corrupt journal " + file + " at line " + lineNumber + ": " + line, e);
            }
        }
        return commands;
    }

    private static boolean endsWithNewline(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
            long size = channel.size();
            return size == 0 || byteAt(channel, size - 1) == '\n';
        }
    }

    private static void trimTornTail(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            long position = channel.size() - 1;
            while (position >= 0 && byteAt(channel, position) != '\n') {
                position--;
            }
            channel.truncate(position + 1);
        }
    }

    private static byte byteAt(FileChannel channel, long position) throws IOException {
        ByteBuffer one = ByteBuffer.allocate(1);
        channel.read(one, position);
        return one.get(0);
    }
}
