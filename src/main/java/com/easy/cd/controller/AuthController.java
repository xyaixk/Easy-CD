package com.easy.cd.controller;

import com.easy.cd.common.Result;
import com.easy.cd.dto.LoginRequestDTO;
import com.easy.cd.service.AuthService;
import com.easy.cd.vo.CurrentUserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public Result<CurrentUserVO> login(@RequestBody LoginRequestDTO requestDTO) {
        return Result.success(authService.login(requestDTO));
    }

    @PostMapping("/logout")
    public Result<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        String token = null;
        if (authorization != null && authorization.startsWith("Bearer ")) {
            token = authorization.substring(7);
        }
        authService.logout(token);
        return Result.success();
    }

    @GetMapping("/me")
    public Result<CurrentUserVO> me() {
        return Result.success(authService.currentUser());
    }
}
