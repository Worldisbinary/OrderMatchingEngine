package com.ome.gateway;

import com.ome.engine.Exchange;
import com.ome.model.ExecutionListener;
import com.ome.model.ExecutionReport;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TCP gateway in front of an {@link Exchange}, speaking the {@link Protocol} text protocol.
 * One thread reads each connection and one writes to it, so a slow client never stalls the
 * engine: execution reports are handed to the owning session's outbox and written from there.
 *
 * <p>Listens on the loopback interface only: there is no authentication.
 */
public final class GatewayServer implements Closeable {

    private final Exchange exchange;
    private final ServerSocket serverSocket;
    private final Thread acceptor;
    private final ConcurrentHashMap<String, Session> sessionsByAccount = new ConcurrentHashMap<>();
    private final Set<Session> sessions = ConcurrentHashMap.newKeySet();
    private final AtomicInteger sessionIds = new AtomicInteger();
    private volatile boolean closed;

    private GatewayServer(Exchange exchange, ServerSocket serverSocket) {
        this.exchange = exchange;
        this.serverSocket = serverSocket;
        this.acceptor = new Thread(this::acceptLoop, "ome-gateway-acceptor");
    }

    /** Starts listening on {@code port} (0 picks a free port) on 127.0.0.1. */
    public static GatewayServer start(Exchange exchange, int port) throws IOException {
        ServerSocket socket = new ServerSocket(port, 50, InetAddress.getLoopbackAddress());
        GatewayServer server = new GatewayServer(exchange, socket);
        exchange.addListener(server.new Router());
        server.acceptor.start();
        return server;
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    Exchange exchange() {
        return exchange;
    }

    /** Claims {@code account} for a session; false if another session already holds it. */
    boolean login(String account, Session session) {
        return sessionsByAccount.putIfAbsent(account, session) == null;
    }

    void ended(Session session, String account) {
        sessions.remove(session);
        if (account != null) {
            sessionsByAccount.remove(account, session);
        }
    }

    private void acceptLoop() {
        while (!closed) {
            try {
                Socket socket = serverSocket.accept();
                socket.setTcpNoDelay(true);
                Session session = new Session(this, socket, sessionIds.incrementAndGet());
                sessions.add(session);
                session.start();
            } catch (SocketException e) {
                if (!closed) {
                    System.err.println("ome gateway: accept failed: " + e);
                }
            } catch (IOException e) {
                System.err.println("ome gateway: accept failed: " + e);
            }
        }
    }

    @Override
    public void close() throws IOException {
        closed = true;
        serverSocket.close();
        for (Session session : sessions) {
            session.close();
        }
        try {
            acceptor.join(1_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Runs on the sequencer thread: hands each report to the owning account's session, if connected. */
    private final class Router implements ExecutionListener {
        @Override
        public void onReport(ExecutionReport report) {
            Session session = sessionsByAccount.get(report.account());
            if (session != null) {
                session.send(report.describe());
            }
        }
    }
}
