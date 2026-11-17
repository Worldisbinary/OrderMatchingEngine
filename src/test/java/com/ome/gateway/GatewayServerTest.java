package com.ome.gateway;

import com.ome.engine.Exchange;
import com.ome.model.Instrument;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("TCP gateway end to end")
@Timeout(30)
class GatewayServerTest {

    private static final Pattern ORDER_ID = Pattern.compile("order=(\\d+)");

    private Exchange exchange;
    private GatewayServer server;

    @BeforeEach
    void start() throws IOException {
        exchange = Exchange.open(List.of(new Instrument("ACME", 5)));
        server = GatewayServer.start(exchange, 0);
    }

    @AfterEach
    void stop() throws IOException {
        server.close();
        exchange.close();
    }

    @Test
    @DisplayName("Orders need a LOGIN first")
    void loginRequired() throws IOException {
        try (Client c = new Client(server.port())) {
            assertEquals(List.of("ERR LOGIN first"), c.request("NEW BUY ACME LIMIT 10 100.00"));
        }
    }

    @Test
    @DisplayName("A new order is answered with its EXEC report, then OK")
    void newOrder() throws IOException {
        try (Client alice = new Client(server.port())) {
            assertEquals(List.of("OK"), alice.request("LOGIN alice"));
            List<String> reply = alice.request("new sell acme limit 100 100.10");

            assertEquals(2, reply.size(), reply.toString());
            assertTrue(reply.get(0).startsWith("EXEC seq=1 exec=NEW order=1 account=alice symbol=ACME side=SELL"),
                    reply.get(0));
            assertTrue(reply.get(0).contains("price=100.10 leaves=100 cum=0 status=OPEN"), reply.get(0));
            assertEquals("OK", reply.get(1));
        }
    }

    @Test
    @DisplayName("The resting side is told about its fill even though another client caused it")
    void counterpartyGetsFill() throws IOException {
        try (Client alice = new Client(server.port()); Client bob = new Client(server.port())) {
            alice.request("LOGIN alice");
            bob.request("LOGIN bob");
            long aliceOrder = orderId(alice.request("NEW SELL ACME LIMIT 100 100.10").get(0));

            List<String> bobReply = bob.request("NEW BUY ACME LIMIT 60 100.20");
            assertEquals(3, bobReply.size(), bobReply.toString());
            assertTrue(bobReply.get(1).contains("exec=TRADE") && bobReply.get(1).contains("lastPrice=100.10"),
                    bobReply.get(1));

            String pushed = alice.readLine();
            assertTrue(pushed.startsWith("EXEC") && pushed.contains("exec=TRADE"), pushed);
            assertEquals(aliceOrder, orderId(pushed));
            assertTrue(pushed.contains("leaves=40 cum=60 lastQty=60"), pushed);
        }
    }

    @Test
    @DisplayName("One session per account; nobody can cancel someone else's order")
    void accountsAreIsolated() throws IOException {
        try (Client alice = new Client(server.port()); Client mallory = new Client(server.port())) {
            alice.request("LOGIN alice");
            assertEquals(List.of("ERR account alice is already connected"), mallory.request("LOGIN alice"));
            mallory.request("LOGIN mallory");

            long id = orderId(alice.request("NEW BUY ACME LIMIT 10 99.00").get(0));
            List<String> reply = mallory.request("CANCEL ACME " + id);
            assertTrue(reply.get(0).contains("exec=REJECTED") && reply.get(0).contains("reason=UNKNOWN_ORDER"),
                    reply.toString());

            List<String> own = alice.request("CANCEL ACME " + id);
            assertTrue(own.get(0).contains("exec=CANCELLED") && own.get(0).contains("reason=CANCEL_REQUESTED"),
                    own.toString());
        }
    }

    @Test
    @DisplayName("REPLACE, BOOK and STATS")
    void replaceBookStats() throws IOException {
        try (Client alice = new Client(server.port()); Client bob = new Client(server.port())) {
            alice.request("LOGIN alice");
            bob.request("LOGIN bob");
            long id = orderId(alice.request("NEW SELL ACME LIMIT 100 101.00").get(0));
            alice.request("NEW SELL ACME LIMIT 50 101.50");
            bob.request("NEW BUY ACME LIMIT 30 100.00");
            assertTrue(alice.request("REPLACE ACME " + id + " 80 101.00").get(0).contains("exec=REPLACED"));

            assertEquals(List.of("BOOK ACME", "ASK 101.50 50 1", "ASK 101.00 80 1", "BID 100.00 30 1", "OK"),
                    bob.request("BOOK acme"));

            bob.request("NEW BUY ACME MARKET 20");
            List<String> stats = bob.request("STATS ACME");
            assertTrue(stats.get(0).startsWith("STATS symbol=ACME trades=1 volume=20"), stats.toString());
        }
    }

    @Test
    @DisplayName("Bad input gets ERR and the session keeps working")
    void badInput() throws IOException {
        try (Client c = new Client(server.port())) {
            c.request("LOGIN carol");
            assertTrue(c.request("NEW BUY ACME LIMIT ten 100.00").get(0).startsWith("ERR quantity must be a whole number"));
            assertTrue(c.request("NEW BUY ACME LIMIT 10 100.001").get(0).startsWith("ERR at most 2 decimal places"));
            assertTrue(c.request("NEW BUY ACME MARKET 10 100.00").get(0).startsWith("ERR a MARKET order takes no price"));
            assertTrue(c.request("DANCE").get(0).startsWith("ERR unknown command"));
            assertTrue(c.request("BOOK NOPE").get(0).startsWith("ERR unknown symbol"));
            List<String> offTick = c.request("NEW BUY ACME LIMIT 10 100.03");
            assertTrue(offTick.get(0).contains("reason=PRICE_NOT_ON_TICK"), offTick.toString());
            assertEquals(Protocol.HELP.size() + 1, c.request("HELP").size());
        }
    }

    @Test
    @DisplayName("QUIT says goodbye and closes the connection")
    void quit() throws IOException {
        try (Client c = new Client(server.port())) {
            assertEquals(List.of("OK bye"), c.request("QUIT"));
            assertNull(c.readLine());
        }
    }

    private static long orderId(String execLine) {
        Matcher m = ORDER_ID.matcher(execLine);
        assertTrue(m.find(), execLine);
        return Long.parseLong(m.group(1));
    }

    /** Line-based test client; every read times out rather than hanging. */
    private static final class Client implements AutoCloseable {
        private final Socket socket;
        private final BufferedReader in;
        private final Writer out;

        Client(int port) throws IOException {
            socket = new Socket(InetAddress.getLoopbackAddress(), port);
            socket.setSoTimeout(5_000);
            in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            String welcome = readLine();
            assertTrue(welcome.startsWith("WELCOME"), welcome);
        }

        /** Sends one request and returns every line up to and including OK or ERR. */
        List<String> request(String line) throws IOException {
            out.write(line + "\r\n");
            out.flush();
            List<String> lines = new ArrayList<>();
            while (true) {
                String reply = readLine();
                if (reply == null) {
                    throw new IOException("connection closed after " + lines);
                }
                lines.add(reply);
                if (reply.startsWith("OK") || reply.startsWith("ERR")) {
                    return lines;
                }
            }
        }

        String readLine() throws IOException {
            return in.readLine();
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
