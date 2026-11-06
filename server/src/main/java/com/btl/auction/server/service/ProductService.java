package com.btl.auction.server.service;

import com.btl.auction.common.dto.ItemDto;
import com.btl.auction.common.model.ItemCategory;
import com.btl.auction.common.model.UserRole;
import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.server.dao.AuctionDao;
import com.btl.auction.server.dao.ItemDao;
import com.btl.auction.server.dao.UserDao;
import com.btl.auction.server.db.Database;
import com.btl.auction.server.db.Transactions;
import java.util.List;
import java.util.Objects;

public final class ProductService {
    private final Database database;
    private final UserDao users = new UserDao();
    private final ItemDao items = new ItemDao();
    private final AuctionDao auctions = new AuctionDao();

    public ProductService(Database database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public ItemDto createItem(long actorId, ItemCategory category, String name,
                              String description, long startingPriceVnd, String detail) {
        validate(category, name, description, startingPriceVnd, detail);
        return Transactions.write(database, connection -> {
            UserDao.StoredUser actor = users.findById(connection, actorId);
            if (actor == null || actor.user().role() != UserRole.SELLER) {
                throw new ServiceException(ErrorCode.FORBIDDEN, "Seller role required");
            }
            return items.insert(connection, actorId, category, name.trim(), description.trim(),
                    startingPriceVnd, detail.trim());
        });
    }

    public ItemDto updateItem(long actorId, long itemId, String name, String description,
                              long startingPriceVnd, String detail) {
        return Transactions.write(database, connection -> {
            ItemDto current = requireItem(connection, itemId);
            requireManager(connection, actorId, current);
            if (auctions.existsForItem(connection, itemId)) {
                throw new ServiceException(ErrorCode.CONFLICT, "Item already has an auction");
            }
            validate(current.category(), name, description, startingPriceVnd, detail);
            ItemDto updated = new ItemDto(itemId, current.sellerId(), current.category(),
                    name.trim(), description.trim(), startingPriceVnd, detail.trim());
            items.update(connection, updated);
            return updated;
        });
    }

    public void deleteItem(long actorId, long itemId) {
        Transactions.write(database, connection -> {
            ItemDto item = requireItem(connection, itemId);
            requireManager(connection, actorId, item);
            if (auctions.existsForItem(connection, itemId)) {
                throw new ServiceException(ErrorCode.CONFLICT, "Item already has an auction");
            }
            items.delete(connection, itemId);
            return null;
        });
    }

    public ItemDto getItem(long itemId) {
        return Transactions.read(database, connection -> requireItem(connection, itemId));
    }

    public List<ItemDto> listItems(Long sellerId) {
        return Transactions.read(database, connection -> items.list(connection, sellerId));
    }

    public List<ItemDto> listOwnItems(long actorId) {
        return Transactions.read(database, connection -> {
            UserDao.StoredUser actor = users.findById(connection, actorId);
            if (actor == null || actor.user().role() != UserRole.SELLER) {
                throw new ServiceException(ErrorCode.FORBIDDEN, "Seller role required");
            }
            return items.list(connection, actorId);
        });
    }

    private ItemDto requireItem(java.sql.Connection connection, long id)
            throws java.sql.SQLException {
        ItemDto item = items.find(connection, id);
        if (item == null) {
            throw new ServiceException(ErrorCode.NOT_FOUND, "Item not found");
        }
        return item;
    }

    private void requireManager(java.sql.Connection connection, long actorId, ItemDto item)
            throws java.sql.SQLException {
        UserDao.StoredUser actor = users.findById(connection, actorId);
        if (actor == null || !(actor.user().role() == UserRole.ADMIN
                || (actor.user().role() == UserRole.SELLER && item.sellerId() == actorId))) {
            throw new ServiceException(ErrorCode.FORBIDDEN, "Item management forbidden");
        }
    }

    private void validate(ItemCategory category, String name, String description,
                          long price, String detail) {
        if (category == null || price < 0) {
            throw new ServiceException(ErrorCode.VALIDATION_ERROR, "Invalid item category or price");
        }
        AuthService.validText(name, "item name", 1, 150);
        AuthService.validText(description, "description", 1, 2000);
        AuthService.validText(detail, "detail", 1, 200);
        category.factory().create(1, 1, name, description, price, detail);
    }
}
