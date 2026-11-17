package com.ome.command;

import com.ome.model.OrderType;
import com.ome.model.Side;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("CommandCodec")
class CommandCodecTest {

    @Test
    @DisplayName("Every command survives encode then decode")
    void roundTrip() {
        List<Command> commands = List.of(
                NewOrder.limit("alice", "ACME", Side.BUY, 10_005, 100),
                NewOrder.market("bob", "BOLT", Side.SELL, 7),
                new NewOrder("c", "ACME", Side.BUY, OrderType.IOC, 1, 1),
                NewOrder.fok("d", "ACME", Side.SELL, 99_995, 3),
                new CancelOrder("alice", "ACME", 42),
                new ReplaceOrder("alice", "ACME", 42, 10_010, 60));
        for (Command command : commands) {
            assertEquals(command, CommandCodec.decode(CommandCodec.encode(command)));
        }
        assertEquals("N|alice|ACME|BUY|LIMIT|10005|100", CommandCodec.encode(commands.get(0)));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "X|a|ACME|1",
            "C|a|ACME",
            "C|a|ACME|one",
            "N|a|ACME|UP|LIMIT|1|1",
            "N|a|acme|BUY|LIMIT|1|1",
            "R|a|ACME|1|2|3|4"})
    @DisplayName("Malformed lines are refused")
    void rejectsMalformed(String line) {
        assertThrows(IllegalArgumentException.class, () -> CommandCodec.decode(line));
    }
}
