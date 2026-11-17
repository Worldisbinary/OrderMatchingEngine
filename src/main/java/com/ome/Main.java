package com.ome;

import com.ome.cli.Demo;
import com.ome.cli.LoadTest;
import com.ome.cli.ServerMain;
import com.ome.gateway.TerminalClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Command-line entry point. */
public final class Main {

    private static final String USAGE = String.join(System.lineSeparator(),
            "usage: java -jar order-matching-engine.jar <command>",
            "  demo                                       guided tour of order types, amends and rejects (default)",
            "  server [port] [journal]                    journaled exchange behind a TCP gateway on 127.0.0.1",
            "                                             (defaults: 9090, data/journal.log)",
            "  client [host] [port]                       interactive client (defaults: 127.0.0.1 9090)",
            "  loadtest [commands] [threads] [--journal]  throughput and latency (defaults: 1000000 4)");

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String command = args.length == 0 ? "demo" : args[0];
        List<String> rest = new ArrayList<>(Arrays.asList(args).subList(Math.min(1, args.length), args.length));
        switch (command) {
            case "demo" -> Demo.run(System.out);
            case "server" -> ServerMain.run(intArg(rest, 0, 9090), Path.of(stringArg(rest, 1, "data/journal.log")));
            case "client" -> TerminalClient.run(stringArg(rest, 0, "127.0.0.1"), intArg(rest, 1, 9090));
            case "loadtest" -> {
                boolean journal = rest.remove("--journal");
                LoadTest.run(intArg(rest, 0, 1_000_000), intArg(rest, 1, 4), journal, System.out);
            }
            case "help", "-h", "--help" -> System.out.println(USAGE);
            default -> {
                System.err.println(USAGE);
                System.exit(2);
            }
        }
    }

    private static String stringArg(List<String> args, int index, String fallback) {
        return index < args.size() ? args.get(index) : fallback;
    }

    private static int intArg(List<String> args, int index, int fallback) {
        return index < args.size() ? Integer.parseInt(args.get(index).replace("_", "")) : fallback;
    }
}
