package com.btl.auction.common.dto;

import com.btl.auction.common.model.UserRole;

public record UserDto(long id, String username, String displayName, UserRole role) {
}
