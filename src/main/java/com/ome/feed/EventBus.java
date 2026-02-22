package com.ome.feed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Lightweight publish-subscribe event bus.
 *
 * Publishers (MatchingEngine) call publish() synchronously.
 * Subscribers register via subscribe() and receive events on a
 * dedicated background dispatcher thread.
 *
 * This decouples the latency-critical matching loop from downstream
 * consumers like market data feeds, risk monitors, and loggers —
 * exactly how real exchange architectures (FAST protocol, ITCH, etc.) work.
 *
 * Uses a LinkedBlockingQueue (bounded) so the engine never blocks on
 * slow consumers; events are dropped and counted if the queue overflows.
 */
public class EventBus {

    private static final int DEFAULT_CAPACITY = 10_000;

    private final BlockingQueue<MarketEvent>                           queue;
    private final Map<MarketEvent.EventType, List<EventSubscriber>>    subscribers;
    private final Thread                                               dispatcher;
    private       volatile boolean                                     running;
    private       long                                                 droppedEvents;

    @FunctionalInterface
    public interface EventSubscriber {
        void onEvent(MarketEvent event);
    }

    public EventBus() {
        this(DEFAULT_CAPACITY);
    }

    public EventBus(int capacity) {
        this.queue       = new LinkedBlockingQueue<>(capacity);
        this.subscribers = new ConcurrentHashMap<>();
        this.running     = true;
        this.dispatcher  = new Thread(this::dispatch, "EventBus-Dispatcher");
        this.dispatcher.setDaemon(true);
        this.dispatcher.start();
    }

    // ── API ───────────────────────────────────────────────────────────────────

    /**
     * Publish an event. Non-blocking: drops event and increments counter if queue full.
     */
    public void publish(MarketEvent event) {
        if (!queue.offer(event)) {
            droppedEvents++;
        }
    }

    /**
     * Subscribe to a specific event type.
     */
    public void subscribe(MarketEvent.EventType type, EventSubscriber subscriber) {
        subscribers.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>()).add(subscriber);
    }

    /**
     * Drain remaining events and stop the dispatcher thread.
     */
    public void shutdown() throws InterruptedException {
        running = false;
        dispatcher.join(500);
        if (droppedEvents > 0)
            System.out.printf("⚠️  EventBus: %d events were dropped (queue overflow).%n", droppedEvents);
    }

    public long getDroppedEvents() { return droppedEvents; }

    // ── Dispatcher Loop ───────────────────────────────────────────────────────

    private void dispatch() {
        while (running || !queue.isEmpty()) {
            try {
                MarketEvent event = queue.poll();
                if (event != null) {
                    notifySubscribers(event);
                } else {
                    Thread.sleep(1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void notifySubscribers(MarketEvent event) {
        List<EventSubscriber> subs = subscribers.get(event.getType());
        if (subs == null) return;
        for (EventSubscriber sub : subs) {
            try {
                sub.onEvent(event);
            } catch (Exception ex) {
                System.err.println("Subscriber error on event " + event + ": " + ex.getMessage());
            }
        }
    }
}
