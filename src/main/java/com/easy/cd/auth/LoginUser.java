package com.easy.cd.auth;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class LoginUser {
    private Long userId;
    private String username;
    private List<String> roles;
    private String token;
}
