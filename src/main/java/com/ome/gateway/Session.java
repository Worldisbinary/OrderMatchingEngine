package com.ome.gateway;

import com.ome.command.Command;
import com.ome.model.Names;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/** One client connection: a reader thread that handles requests and a writer thread that drains the outbox. */
final class Session {

    static final int MAX_LINE_LENGTH = 512;
    static final int MAX_PENDING_LINES = 100_000;

    /** Poison pill for the writer; compared by identity, so no client line can ever match it. */
    private static final String END = new String("END");

    private final GatewayServer server;
    private final Socket socket;
    private final int id;
    private final BlockingQueue<String> outbox = new LinkedBlockingQueue<>(MAX_PENDING_LINES);
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile String account;

    Session(GatewayServer server, Socket socket, int id) {
        this.server = server;
        this.socket = socket;
        this.id = id;
    }

    void start() {
        Thread writer = new Thread(this::writeLoop, "ome-session-" + id + "-writer");
        writer.setDaemon(true);
        writer.start();
        Thread reader = new Thread(this::readLoop, "ome-session-" + id + "-reader");
        reader.setDaemon(true);
        reader.start();
    }

    /** Queues a line for the client. A client that falls this far behind is disconnected. */
    void send(String line) {
        if (open.get() && !outbox.offer(line)) {
            close();
        }
    }

    /** Stops the session; lines already queued are still written before the socket closes. */
    void close() {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        server.ended(this, account);
        if (!outbox.offer(END)) { // full: the client is not reading, so drop its backlog
            outbox.clear();
            outbox.offer(END);
        }
    }

    // ---------------------------------------------------------------- reading

    /**
     * Reads requests until QUIT, end of stream or close. It deliberately does not close the
     * input stream: closing a socket stream closes the socket, and the writer must first
     * deliver the last replies. The writer closes the socket.
     */
    private void readLoop() {
        send("WELCOME order-matching-engine. LOGIN <account> to start, HELP for commands.");
        try {
            Reader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            StringBuilder line = new StringBuilder();
            while (open.get()) {
                int result = readLine(in, line);
                if (result < 0) {
                    break;
                }
                if (result == 0) {
                    send("ERR line longer than " + MAX_LINE_LENGTH + " characters");
                    break;
                }
                String request = line.toString().trim();
                if (!request.isEmpty() && !handle(request)) {
                    break;
                }
            }
        } catch (IOException e) {
            // The client went away.
        } finally {
            close();
        }
    }

    /** Reads one line into {@code line}: 1 = read, 0 = too long, -1 = end of stream. */
    private static int readLine(Reader in, StringBuilder line) throws IOException {
        line.setLength(0);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return 1;
            }
            if (c != '\r') {
                if (line.length() == MAX_LINE_LENGTH) {
                    return 0;
                }
                line.append((char) c);
            }
        }
        return line.length() > 0 ? 1 : -1;
    }

    /** Handles one request; returns false when the client asked to quit. */
    private boolean handle(String request) {
        String[] words = request.split("\\s+");
        String verb = words[0].toUpperCase(Locale.ROOT);
        try {
            switch (verb) {
                case "LOGIN" -> login(words);
                case "NEW" -> submit(Protocol.parseNew(requireAccount(), words));
                case "CANCEL" -> submit(Protocol.parseCancel(requireAccount(), words));
                case "REPLACE" -> submit(Protocol.parseReplace(requireAccount(), words));
                case "BOOK" -> book(words);
                case "STATS" -> stats(words);
                case "HELP" -> Protocol.HELP.forEach(this::send);
                case "QUIT" -> {
                    send("OK bye");
                    return false;
                }
                default -> throw new IllegalArgumentException("unknown command " + words[0] + " (try HELP)");
            }
            send("OK");
        } catch (IllegalArgumentException | IllegalStateException e) {
            send("ERR " + e.getMessage());
        } catch (CompletionException e) {
            send("ERR " + e.getCause().getMessage());
        }
        return true;
    }

    private void login(String[] words) {
        if (words.length != 2) {
            throw new IllegalArgumentException("usage: LOGIN <account>");
        }
        if (account != null) {
            throw new IllegalStateException("already logged in as " + account);
        }
        String name = Names.account(words[1]);
        if (!server.login(name, this)) {
            throw new IllegalStateException("account " + name + " is already connected");
        }
        account = name;
    }

    private String requireAccount() {
        if (account == null) {
            throw new IllegalStateException("LOGIN first");
        }
        return account;
    }

    /**
     * Waits until the command has been applied. This session's EXEC lines were queued by the
     * gateway's router before the future completed, so they always arrive before the OK.
     */
    private void submit(Command command) {
        server.exchange().submit(command).join();
    }

    private void book(String[] words) {
        if (words.length < 2 || words.length > 3) {
            throw new IllegalArgumentException("usage: BOOK <SYMBOL> [DEPTH]");
        }
        String symbol = words[1].toUpperCase(Locale.ROOT);
        int depth = words.length == 3 ? (int) Protocol.parseLong(words[2], "depth") : 5;
        if (depth < 1 || depth > 100) {
            throw new IllegalArgumentException("depth must be 1 to 100");
        }
        Protocol.formatBook(server.exchange().snapshot(symbol, depth)).forEach(this::send);
    }

    private void stats(String[] words) {
        if (words.length != 2) {
            throw new IllegalArgumentException("usage: STATS <SYMBOL>");
        }
        String symbol = words[1].toUpperCase(Locale.ROOT);
        send(server.exchange().marketData().stats(symbol)
                .map(s -> s.describe())
                .orElse("STATS symbol=" + symbol + " trades=0"));
    }

    // ---------------------------------------------------------------- writing

    private void writeLoop() {
        try (Writer out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String line = outbox.take();
                if (line == END) {
                    break;
                }
                out.write(line);
                out.write('\n');
                if (outbox.isEmpty()) {
                    out.flush();
                }
            }
        } catch (IOException e) {
            // The client went away.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
            try {
                socket.close();
            } catch (IOException ignored) {
                // Already closing.
            }
        }
    }
}
