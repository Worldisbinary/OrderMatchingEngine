package com.ome.gateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Minimal interactive client: sends what you type, prints what the gateway sends back. */
public final class TerminalClient {

    private TerminalClient() {
    }

    public static void run(String host, int port) throws IOException, InterruptedException {
        try (Socket socket = new Socket(host, port);
             BufferedReader fromServer = new BufferedReader(
                     new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter toServer = new PrintWriter(
                     new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8), true)) {

            Thread printer = new Thread(() -> {
                try {
                    String line;
                    while ((line = fromServer.readLine()) != null) {
                        System.out.println(line);
                    }
                } catch (IOException e) {
                    // Connection closed.
                }
            }, "ome-client-printer");
            printer.setDaemon(true);
            printer.start();

            BufferedReader console = new BufferedReader(new InputStreamReader(System.in, Charset.defaultCharset()));
            String line;
            while ((line = console.readLine()) != null) {
                toServer.print(line + "\n");
                toServer.flush();
                if (line.trim().toUpperCase(Locale.ROOT).equals("QUIT")) {
                    break;
                }
            }
            printer.join(1_000);
        }
    }
}
