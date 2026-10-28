package com.btl.auction.common.model;

import java.util.Objects;

public abstract class User extends Entity {
    private final String username;
    private final String displayName;

    protected User(long id, String username, String displayName) {
        super(id);
        this.username = requireText(username, "username");
        this.displayName = requireText(displayName, "displayName");
    }

    private static String requireText(String text, String name) {
        Objects.requireNonNull(text, name);
        if (text.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return text;
    }

    public final String getUsername() {
        return username;
    }

    public final String getDisplayName() {
        return displayName;
    }

    public abstract UserRole getRole();
}
