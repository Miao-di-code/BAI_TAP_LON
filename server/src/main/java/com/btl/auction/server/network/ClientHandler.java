package com.btl.auction.server.network;

import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.common.protocol.InvalidProtocolException;
import com.btl.auction.common.protocol.JsonCodec;
import com.btl.auction.common.protocol.LineFraming;
import com.btl.auction.common.protocol.RequestMessage;
import com.btl.auction.common.protocol.ResponseMessage;
import java.io.BufferedInputStream;
import java.io.IOException;

public final class ClientHandler implements Runnable {
    private final ClientSession session;
    private final RequestDispatcher dispatcher;
    private final AuctionEventBroadcaster broadcaster;
    private final Runnable onClose;

    public ClientHandler(ClientSession session, RequestDispatcher dispatcher,
                         AuctionEventBroadcaster broadcaster, Runnable onClose) {
        this.session = session;
        this.dispatcher = dispatcher;
        this.broadcaster = broadcaster;
        this.onClose = onClose;
    }

    @Override
    public void run() {
        try {
            BufferedInputStream input = new BufferedInputStream(session.socket().getInputStream());
            String line;
            while ((line = LineFraming.readLine(input)) != null) {
                ResponseMessage response;
                try {
                    RequestMessage request = JsonCodec.parseRequest(line);
                    response = dispatcher.dispatch(request, session);
                } catch (InvalidProtocolException ex) {
                    response = ResponseMessage.error(null, ErrorCode.INVALID_REQUEST,
                            "Invalid request JSON");
                }
                if (!session.send(response)) {
                    break;
                }
            }
        } catch (LineFraming.LineTooLongException ex) {
            session.sendFinal(ResponseMessage.error(null, ErrorCode.INVALID_REQUEST,
                    "JSON line exceeds 1 MiB"));
        } catch (IOException ignored) {
            // A disconnected client must not stop the server.
        } finally {
            broadcaster.unsubscribeAll(session);
            session.close();
            onClose.run();
        }
    }
}
