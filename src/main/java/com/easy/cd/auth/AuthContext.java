package com.easy.cd.auth;

public class AuthContext {
    private static final ThreadLocal<LoginUser> CURRENT_USER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void setCurrentUser(LoginUser loginUser) {
        CURRENT_USER.set(loginUser);
    }

    public static LoginUser getCurrentUser() {
        return CURRENT_USER.get();
    }

    public static void clear() {
        CURRENT_USER.remove();
    }
}
