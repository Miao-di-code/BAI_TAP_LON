package com.btl.auction.server.network;

import com.btl.auction.common.model.AuctionStatus;
import com.btl.auction.common.model.ItemCategory;
import com.btl.auction.common.model.UserRole;
import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.common.protocol.RequestMessage;
import com.btl.auction.common.protocol.ResponseMessage;
import com.btl.auction.server.service.AuctionService;
import com.btl.auction.server.service.AuthService;
import com.btl.auction.server.service.ProductService;
import com.btl.auction.server.service.ServiceException;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class RequestDispatcher {
    private static final Logger LOGGER = Logger.getLogger(RequestDispatcher.class.getName());
    private final AuthService auth;
    private final ProductService products;
    private final AuctionService auctions;
    private final AuctionEventBroadcaster broadcaster;
    private final Gson gson = new Gson();

    public RequestDispatcher(AuthService auth, ProductService products, AuctionService auctions,
                             AuctionEventBroadcaster broadcaster) {
        this.auth = auth;
        this.products = products;
        this.auctions = auctions;
        this.broadcaster = broadcaster;
    }

    public ResponseMessage dispatch(RequestMessage request, ClientSession session) {
        try {
            return ResponseMessage.ok(request.requestId(), handle(request, session));
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

    private JsonObject handle(RequestMessage request, ClientSession session) {
        JsonObject input = request.payload();
        JsonObject output = new JsonObject();
        switch (request.type()) {
            case PING -> output.addProperty("pong", true);
            case REGISTER -> output.add("user", gson.toJsonTree(auth.register(
                    string(input, "username"), string(input, "displayName"),
                    string(input, "password"), enumeration(input, "role", UserRole.class))));
            case LOGIN -> {
                var result = auth.login(string(input, "username"), string(input, "password"));
                output.addProperty("token", result.token());
                output.add("user", gson.toJsonTree(result.user()));
            }
            case LOGOUT -> {
                auth.requireUser(request.token());
                auth.logout(request.token());
                broadcaster.unsubscribeAll(session);
            }
            case LIST_AUCTIONS, GET_AUCTIONS -> output.add("auctions", gson.toJsonTree(
                    auctions.listAuctions(optionalEnum(input, "status", AuctionStatus.class))));
            case GET_AUCTION, GET_AUCTION_DETAIL -> output.add("auction", gson.toJsonTree(
                    auctions.getAuction(number(input, "auctionId"))));
            case GET_MY_ITEMS -> output.add("items", gson.toJsonTree(
                    products.listOwnItems(actor(request))));
            case LIST_ITEMS -> {
                auth.requireRole(request.token(), UserRole.ADMIN);
                output.add("items", gson.toJsonTree(products.listItems(null)));
            }
            case GET_ITEM -> output.add("item", gson.toJsonTree(
                    products.getItem(number(input, "itemId"))));
            case CREATE_ITEM -> output.add("item", gson.toJsonTree(products.createItem(
                    actor(request), enumeration(input, "category", ItemCategory.class),
                    string(input, "name"), string(input, "description"),
                    number(input, "startingPriceVnd"), string(input, "detail"))));
            case UPDATE_ITEM -> output.add("item", gson.toJsonTree(products.updateItem(
                    actor(request), number(input, "itemId"), string(input, "name"),
                    string(input, "description"), number(input, "startingPriceVnd"),
                    string(input, "detail"))));
            case DELETE_ITEM -> products.deleteItem(actor(request), number(input, "itemId"));
            case CREATE_AUCTION -> output.add("auction", gson.toJsonTree(auctions.createAuction(
                    actor(request), number(input, "itemId"), number(input, "startAtEpochMs"),
                    number(input, "endAtEpochMs"))));
            case PLACE_BID -> {
                var result = auctions.placeBid(actor(request), number(input, "auctionId"),
                        number(input, "amountVnd"));
                output.add("bid", gson.toJsonTree(result.bid()));
                output.add("auction", gson.toJsonTree(result.auction()));
            }
            case SET_AUTO_BID -> {
                var result = auctions.setAutoBid(actor(request), number(input, "auctionId"),
                        number(input, "maxBidVnd"), number(input, "incrementVnd"));
                output.add("autoBid", gson.toJsonTree(result.autoBid()));
                output.add("auction", gson.toJsonTree(result.auction()));
            }
            case GET_AUTO_BID -> output.add("autoBid", gson.toJsonTree(
                    auctions.getAutoBid(actor(request), number(input, "auctionId"))));
            case REMOVE_AUTO_BID -> auctions.removeAutoBid(actor(request),
                    number(input, "auctionId"));
            case GET_BIDS, GET_BID_HISTORY -> output.add("bids", gson.toJsonTree(
                    auctions.getBids(number(input, "auctionId"))));
            case SUBSCRIBE_AUCTION -> {
                auth.requireUser(request.token());
                long id = number(input, "auctionId");
                auctions.getAuction(id);
                broadcaster.subscribe(id, session);
                output.add("auction", gson.toJsonTree(auctions.getAuction(id)));
            }
            case UNSUBSCRIBE_AUCTION -> {
                auth.requireUser(request.token());
                broadcaster.unsubscribe(number(input, "auctionId"), session);
            }
            case MARK_PAID -> output.add("auction", gson.toJsonTree(
                    auctions.markPaid(actor(request), number(input, "auctionId"))));
            case CANCEL_AUCTION -> output.add("auction", gson.toJsonTree(
                    auctions.cancel(actor(request), number(input, "auctionId"))));
        }
        return output;
    }

    private long actor(RequestMessage request) {
        return auth.requireUser(request.token()).id();
    }

    private static String string(JsonObject input, String name) {
        JsonElement value = input.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name);
        }
        return value.getAsString();
    }

    private static long number(JsonObject input, String name) {
        JsonElement value = input.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()
                || !value.getAsString().matches("-?[0-9]+")) {
            throw new IllegalArgumentException(name);
        }
        return Long.parseLong(value.getAsString());
    }

    private static <T extends Enum<T>> T enumeration(JsonObject input, String name, Class<T> type) {
        return Enum.valueOf(type, string(input, name));
    }

    private static <T extends Enum<T>> T optionalEnum(JsonObject input, String name, Class<T> type) {
        JsonElement value = input.get(name);
        return value == null || value.isJsonNull() ? null : enumeration(input, name, type);
    }
}
