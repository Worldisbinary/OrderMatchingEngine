package com.ome.cli;

import com.ome.engine.Exchange;
import com.ome.gateway.GatewayServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

/** Runs the journaled exchange behind the TCP gateway until the process is stopped (Ctrl+C). */
public final class ServerMain {

    private ServerMain() {
    }

    public static void run(int port, Path journal) throws IOException, InterruptedException {
        Exchange exchange = Exchange.open(SampleInstruments.ALL, journal);
        GatewayServer server = GatewayServer.start(exchange, port);
        System.out.printf("order-matching-engine listening on 127.0.0.1:%d%n", server.port());
        System.out.printf("journal %s (recovered %,d commands)%n", journal.toAbsolutePath(), exchange.recoveredCommands());
        System.out.println("instruments: ACME (tick 0.05), BOLT (tick 0.01)");
        System.out.println("connect with: java -jar target/order-matching-engine.jar client");

        CountDownLatch stopped = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                server.close();
            } catch (IOException e) {
                System.err.println("closing the gateway: " + e);
            }
            exchange.close();
            stopped.countDown();
        }, "ome-shutdown"));
        stopped.await();
    }
}
