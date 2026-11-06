package com.btl.auction.server.service;

import com.btl.auction.common.dto.UserDto;
import com.btl.auction.common.model.UserRole;
import com.btl.auction.common.protocol.ErrorCode;
import com.btl.auction.server.dao.UserDao;
import com.btl.auction.server.db.Database;
import com.btl.auction.server.db.Transactions;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthService {
    public record LoginResult(String token, UserDto user) {
    }

    private record Session(long userId, long expiresAtMs) {
    }

    private static final long SESSION_LIFETIME_MS = Duration.ofHours(8).toMillis();
    private final Database database;
    private final Clock clock;
    private final UserDao users = new UserDao();
    private final SecureRandom random = new SecureRandom();
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();

    public AuthService(Database database, Clock clock) {
        this.database = Objects.requireNonNull(database, "database");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public UserDto register(String username, String displayName, String password, UserRole role) {
        if (role != UserRole.BIDDER && role != UserRole.SELLER) {
            throw new ServiceException(ErrorCode.FORBIDDEN, "Public Admin registration is disabled");
        }
        return createUser(username, displayName, password, role);
    }

    UserDto createLocalAdmin(String username, String displayName, String password) {
        return createUser(username, displayName, password, UserRole.ADMIN);
    }

    private UserDto createUser(String username, String displayName, String password, UserRole role) {
        String normalized = validText(username, "username", 3, 64);
        if (!normalized.matches("[A-Za-z0-9_]+")) {
            throw new ServiceException(ErrorCode.VALIDATION_ERROR, "Invalid username");
        }
        String display = validText(displayName, "displayName", 1, 100);
        if (password == null || password.length() < 8 || password.length() > 256) {
            throw new ServiceException(ErrorCode.VALIDATION_ERROR, "Password must have 8 to 256 characters");
        }
        String hash = PasswordHasher.hash(password);
        return Transactions.write(database, connection -> {
            try {
                return users.insert(connection, normalized, display, hash, role, clock.millis());
            } catch (java.sql.SQLException ex) {
                if (ex.getMessage().contains("UNIQUE")) {
                    throw new ServiceException(ErrorCode.CONFLICT, "Username already exists", ex);
                }
                throw ex;
            }
        });
    }

    public LoginResult login(String username, String password) {
        if (username == null || password == null) {
            throw new AuthenticationException("Invalid credentials");
        }
        UserDao.StoredUser stored = Transactions.read(database,
                connection -> users.findByUsername(connection, username));
        if (stored == null || !PasswordHasher.verify(password, stored.passwordHash())) {
            throw new AuthenticationException("Invalid credentials");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        sessions.put(token, new Session(stored.user().id(), clock.millis() + SESSION_LIFETIME_MS));
        return new LoginResult(token, stored.user());
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public UserDto requireUser(String token) {
        Session session = token == null ? null : sessions.get(token);
        if (session == null || clock.millis() >= session.expiresAtMs()) {
            if (token != null) {
                sessions.remove(token);
            }
            throw new ServiceException(ErrorCode.AUTH_REQUIRED, "Valid session required");
        }
        UserDao.StoredUser stored = Transactions.read(database,
                connection -> users.findById(connection, session.userId()));
        if (stored == null) {
            sessions.remove(token);
            throw new ServiceException(ErrorCode.AUTH_REQUIRED, "User no longer exists");
        }
        return stored.user();
    }

    public UserDto requireRole(String token, UserRole role) {
        UserDto user = requireUser(token);
        if (user.role() != role) {
            throw new ServiceException(ErrorCode.FORBIDDEN, "Role required: " + role);
        }
        return user;
    }

    static String validText(String text, String name, int min, int max) {
        String normalized = text == null ? null : text.trim();
        if (normalized == null || normalized.length() < min || normalized.length() > max) {
            throw new ServiceException(ErrorCode.VALIDATION_ERROR, "Invalid " + name);
        }
        return normalized;
    }
}
