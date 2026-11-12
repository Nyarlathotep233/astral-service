package com.astral.core.config.webSocketConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.websocket.*;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

@ClientEndpoint
public class RevitWsClient {
    private static final Logger log = LoggerFactory.getLogger(RevitWsClient.class);

    private volatile Session session;
    private final String address;
    private final String port;
    private final String path;
    private final int timeout;
    private volatile boolean running;
    private volatile long lastHeartbeat;
    private final long heartbeatIntervalMillis;
    private final long maintenanceIntervalMillis;
    private final Object sendLock = new Object();
    private final AtomicLong connectionVersion = new AtomicLong();
    private final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "h3d-revit-connection");
        thread.setDaemon(true);
        return thread;
    });

    public RevitWsClient(String address, String port, String path, int timeout) {
        this.address = address;
        this.port = port;
        this.path = path;
        this.timeout = timeout;
        heartbeatIntervalMillis = timeout > 0 ? Math.max(1, Math.min(30000L, timeout / 3L)) : 30000L;
        maintenanceIntervalMillis = Math.max(1, Math.min(5000L, heartbeatIntervalMillis / 2));
    }

    public synchronized void start() {
        if (running) return;
        running = true;
        scheduler.scheduleWithFixedDelay(this::maintainConnection, 0, maintenanceIntervalMillis, TimeUnit.MILLISECONDS);
    }

    private void maintainConnection() {
        if (!running) return;
        try {
            if (!isConnected()) {
                WebSocketContainer container = ContainerProvider.getWebSocketContainer();
                container.setDefaultMaxSessionIdleTimeout(timeout);
                container.connectToServer(this, new URI("ws://" + address + ":" + port + path));
            } else if (System.nanoTime() - lastHeartbeat >= TimeUnit.MILLISECONDS.toNanos(heartbeatIntervalMillis)) {
                sendMsg("ping");
                lastHeartbeat = System.nanoTime();
            }
        } catch (Exception e) {
            log.debug("Revit connection unavailable: {}: {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }

    public boolean isConnected() {
        Session current = session;
        return current != null && current.isOpen();
    }

    public long getConnectionVersion() {
        return connectionVersion.get();
    }

    @OnOpen
    public void onOpen(Session opened) {
        synchronized (sendLock) {
            if (running) {
                messages.clear();
                session = opened;
                connectionVersion.incrementAndGet();
                lastHeartbeat = System.nanoTime();
                log.info("Revit conversion service connected");
                return;
            }
        }
        closeSession(opened);
    }

    @OnMessage
    public void onMessage(String message, Session sender) {
        if (sender == session && !"pong".equals(message)) messages.offer(message);
    }

    @OnClose
    public void onClose(Session closed, CloseReason reason) {
        invalidate(closed);
    }

    @OnError
    public void onError(Session failed, Throwable error) {
        invalidate(failed);
        closeSession(failed);
        log.warn("Revit connection error: {}: {}", error.getClass().getSimpleName(), error.getMessage());
    }

    private void invalidate(Session previous) {
        synchronized (sendLock) {
            if (previous != null && session == previous) {
                session = null;
                connectionVersion.incrementAndGet();
            }
        }
    }

    public long sendMsg(String message) throws IOException {
        Session current = null;
        try {
            synchronized (sendLock) {
                current = session;
                if (current == null || !current.isOpen()) throw new IOException("Revit 转换服务未连接");
                current.getBasicRemote().sendText(message);
                return connectionVersion.get();
            }
        } catch (IOException | RuntimeException e) {
            invalidate(current);
            closeSession(current);
            throw e;
        }
    }

    public String readMsg(long wait, TimeUnit unit) throws InterruptedException {
        return messages.poll(wait, unit);
    }

    public synchronized void stop() {
        running = false;
        scheduler.shutdownNow();
        Session current = session;
        invalidate(current);
        closeSession(current);
        messages.clear();
    }

    private void closeSession(Session current) {
        if (current == null) return;
        try {
            current.close();
        } catch (IOException e) {
            log.debug("Revit connection close failed: {}", e.getClass().getSimpleName());
        }
    }
}
