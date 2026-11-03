package com.btl.auction.server.db;

import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.server.service.ServiceException;
import java.sql.Connection;
import java.sql.SQLException;

public final class Transactions {
    private Transactions() {
    }

    @FunctionalInterface
    public interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    public static <T> T read(Database database, Work<T> work) {
        try (Connection connection = database.openConnection()) {
            return work.run(connection);
        } catch (SQLException ex) {
            throw new ServiceException(ErrorCode.INTERNAL_ERROR, "Database operation failed", ex);
        }
    }

    public static <T> T write(Database database, Work<T> work) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException ex) {
                connection.rollback();
                throw ex;
            }
        } catch (SQLException ex) {
            throw new ServiceException(ErrorCode.INTERNAL_ERROR, "Database transaction failed", ex);
        }
    }
}
