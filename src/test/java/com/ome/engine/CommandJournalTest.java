package com.ome.engine;

import com.ome.command.CancelOrder;
import com.ome.command.Command;
import com.ome.command.NewOrder;
import com.ome.command.ReplaceOrder;
import com.ome.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("CommandJournal")
class CommandJournalTest {

    private static final List<Command> COMMANDS = List.of(
            NewOrder.limit("a", "ACME", Side.BUY, 10_000, 5),
            NewOrder.market("b", "ACME", Side.SELL, 3),
            new ReplaceOrder("a", "ACME", 1, 10_005, 2),
            new CancelOrder("a", "ACME", 1));

    @TempDir
    Path dir;

    @Test
    @DisplayName("Reads back exactly what was appended, with one header line")
    void roundTrip() throws IOException {
        Path file = dir.resolve("nested/journal.log");
        write(file, COMMANDS);

        assertEquals(COMMANDS, CommandJournal.read(file));
        assertTrue(Files.readString(file).startsWith(CommandJournal.HEADER + "\n"));
    }

    @Test
    @DisplayName("Reopening appends after existing commands without a second header")
    void reopenAppends() throws IOException {
        Path file = dir.resolve("journal.log");
        write(file, COMMANDS.subList(0, 2));
        write(file, COMMANDS.subList(2, 4));

        assertEquals(COMMANDS, CommandJournal.read(file));
        assertEquals(1, Files.readAllLines(file).stream().filter(l -> l.startsWith("#")).count());
    }

    @Test
    @DisplayName("A torn final line from a crash is ignored, then trimmed before the next append")
    void tornTail() throws IOException {
        Path file = dir.resolve("journal.log");
        write(file, COMMANDS.subList(0, 2));
        Files.writeString(file, "N|a|ACME|BU", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        assertEquals(COMMANDS.subList(0, 2), CommandJournal.read(file));

        write(file, COMMANDS.subList(2, 4));
        assertEquals(COMMANDS, CommandJournal.read(file));
    }

    @Test
    @DisplayName("A corrupt complete line is an error, not silently skipped")
    void corruptLine() throws IOException {
        Path file = dir.resolve("journal.log");
        write(file, COMMANDS.subList(0, 1));
        Files.writeString(file, "N|a|ACME|SIDEWAYS|LIMIT|1|1\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        IOException error = assertThrows(IOException.class, () -> CommandJournal.read(file));
        assertTrue(error.getMessage().contains("line 3"), error.getMessage());
    }

    private static void write(Path file, List<Command> commands) throws IOException {
        try (CommandJournal journal = CommandJournal.openForAppend(file)) {
            for (Command command : commands) {
                journal.append(command);
            }
        }
    }
}
