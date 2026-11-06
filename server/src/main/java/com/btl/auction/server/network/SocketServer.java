package com.btl.auction.server.network;

import com.btl.auction.server.db.Database;
import com.btl.auction.server.db.DatabaseInitializer;
import com.btl.auction.server.event.AuctionEventPublisher;
import com.btl.auction.server.service.AuctionScheduler;
import com.btl.auction.server.service.AuctionService;
import com.btl.auction.server.service.AntiSnipingPolicy;
import com.btl.auction.server.service.AuthService;
import com.btl.auction.server.service.ProductService;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class SocketServer implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(SocketServer.class.getName());
    private final int configuredPort;
    private final Clock clock;
    private final AuctionService auctions;
    private final RequestDispatcher dispatcher;
    private final AuctionEventBroadcaster broadcaster;
    private final Set<ClientSession> sessions = ConcurrentHashMap.newKeySet();
    private final ExecutorService handlers = Executors.newCachedThreadPool();
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch stopped = new CountDownLatch(1);
    private ServerSocket listener;
    private AuctionScheduler scheduler;

    public SocketServer(int port, Path databasePath, Clock clock) throws IOException, SQLException {
        this(port, databasePath, clock, AntiSnipingPolicy.disabled());
    }

    public SocketServer(int port, Path databasePath, Clock clock,
                        AntiSnipingPolicy antiSniping) throws IOException, SQLException {
        if (port < 0 || port > 65535) {
            throw new IllegalArgumentException("Port out of range");
        }
        this.configuredPort = port;
        this.clock = clock;
        Database database = DatabaseInitializer.initialize(databasePath);
        AuctionEventPublisher publisher = new AuctionEventPublisher();
        auctions = new AuctionService(database, clock, publisher, antiSniping);
        broadcaster = new AuctionEventBroadcaster(publisher);
        dispatcher = new RequestDispatcher(new AuthService(database, clock),
                new ProductService(database), auctions, broadcaster);
    }

    public synchronized void start() throws IOException {
        if (!running.compareAndSet(false, true)) {
            throw new IllegalStateException("Server already started");
        }
        try {
            listener = new ServerSocket(configuredPort, 64, InetAddress.getByName("127.0.0.1"));
            scheduler = new AuctionScheduler(auctions, clock);
            Thread acceptThread = new Thread(this::acceptLoop, "auction-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
        } catch (IOException | RuntimeException ex) {
            close();
            throw ex;
        }
    }

    public int port() {
        if (listener == null) {
            throw new IllegalStateException("Server not started");
        }
        return listener.getLocalPort();
    }

    public void awaitTermination() throws InterruptedException {
        stopped.await();
    }

    private void acceptLoop() {
        while (running.get()) {
            Socket socket = null;
            ClientSession session = null;
            try {
                socket = listener.accept();
                socket.setTcpNoDelay(true);
                session = new ClientSession(socket);
                sessions.add(session);
                ClientSession active = session;
                handlers.execute(new ClientHandler(active, dispatcher, broadcaster,
                        () -> sessions.remove(active)));
            } catch (RejectedExecutionException ex) {
                closeRejected(socket, session);
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "Client handler rejected", ex);
                }
            } catch (SocketException ex) {
                closeRejected(socket, session);
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "Accept failed", ex);
                }
            } catch (IOException ex) {
                closeRejected(socket, session);
                if (running.get()) {
                    LOGGER.log(Level.WARNING, "Accept failed", ex);
                }
            }
        }
    }

    private void closeRejected(Socket socket, ClientSession session) {
        if (session != null) {
            sessions.remove(session);
            session.close();
        } else if (socket != null) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Socket was already closed.
            }
        }
    }

    @Override
    public synchronized void close() {
        if (running.getAndSet(false)) {
            if (scheduler != null) {
                scheduler.close();
            }
            if (listener != null) {
                try {
                    listener.close();
                } catch (IOException ignored) {
                    // Closing an already closed listener is harmless.
                }
            }
            for (ClientSession session : sessions) {
                broadcaster.unsubscribeAll(session);
                session.close();
            }
            handlers.shutdownNow();
        }
        stopped.countDown();
    }
}
