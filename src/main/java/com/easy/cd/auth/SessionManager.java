package com.easy.cd.auth;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionManager {
    private final Map<String, LoginUser> sessions = new ConcurrentHashMap<>();

    public String createSession(LoginUser loginUser) {
        String token = UUID.randomUUID().toString().replace("-", "");
        loginUser.setToken(token);
        sessions.put(token, loginUser);
        return token;
    }

    public LoginUser getByToken(String token) {
        return sessions.get(token);
    }

    public void remove(String token) {
        if (token != null && !token.isEmpty()) {
            sessions.remove(token);
        }
    }
}
