package com.btl.auction.server.network;

import com.btl.auction.common.model.AuctionStatus;
import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.common.protocol.RequestMessage;
import com.btl.auction.common.protocol.ResponseMessage;
import com.btl.auction.server.service.AuctionService;
import com.btl.auction.server.service.ServiceException;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Routes the requests whose backing services are available at this stage. */
public final class RequestDispatcher {
    private static final Logger LOGGER = Logger.getLogger(RequestDispatcher.class.getName());
    private final AuctionService auctions;
    private final Gson gson = new Gson();

    public RequestDispatcher() {
        this(null);
    }

    public RequestDispatcher(AuctionService auctions) {
        this.auctions = auctions;
    }

    public ResponseMessage dispatch(RequestMessage request) {
        Objects.requireNonNull(request, "request");
        try {
            JsonObject output = new JsonObject();
            switch (request.type()) {
                case PING -> output.addProperty("pong", true);
                case GET_AUCTIONS -> {
                    requireAuctions();
                    output.add("auctions", gson.toJsonTree(auctions.listAuctions(status(request.payload()))));
                }
                case GET_AUCTION_DETAIL -> {
                    requireAuctions();
                    output.add("auction", gson.toJsonTree(
                            auctions.getAuction(auctionId(request.payload()))));
                }
                case GET_BID_HISTORY -> {
                    requireAuctions();
                    output.add("bids", gson.toJsonTree(
                            auctions.getBids(auctionId(request.payload()))));
                }
                default -> {
                    return ResponseMessage.error(request.requestId(), ErrorCode.UNSUPPORTED_REQUEST,
                            "Request type is not available yet");
                }
            }
            return ResponseMessage.ok(request.requestId(), output);
        } catch (UnavailableException ex) {
            return ResponseMessage.error(request.requestId(), ErrorCode.UNSUPPORTED_REQUEST,
                    "Auction service is not configured");
        } catch (ServiceException ex) {
            String message = ex.getCode() == ErrorCode.INTERNAL_ERROR
                    ? "Server operation failed" : ex.getMessage();
            return ResponseMessage.error(request.requestId(), ex.getCode(), message);
        } catch (IllegalArgumentException ex) {
            return ResponseMessage.error(request.requestId(), ErrorCode.VALIDATION_ERROR,
                    "Invalid request payload");
        } catch (RuntimeException ex) {
            LOGGER.log(Level.SEVERE, "Request failed", ex);
            return ResponseMessage.error(request.requestId(), ErrorCode.INTERNAL_ERROR,
                    "Server operation failed");
        }
    }

    private void requireAuctions() {
        if (auctions == null) {
            throw new UnavailableException();
        }
    }

    private static long auctionId(JsonObject payload) {
        JsonElement value = payload.get("auctionId");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("[0-9]+")) {
            throw new IllegalArgumentException("auctionId");
        }
        return Long.parseLong(value.getAsString());
    }

    private static AuctionStatus status(JsonObject payload) {
        JsonElement value = payload.get("status");
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("status");
        }
        return AuctionStatus.valueOf(value.getAsString());
    }

    private static final class UnavailableException extends RuntimeException {
    }
}
