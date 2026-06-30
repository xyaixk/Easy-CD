package com.easy.cd.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.easy.cd.auth.AuthContext;
import com.easy.cd.auth.LoginUser;
import com.easy.cd.auth.SessionManager;
import com.easy.cd.dto.LoginRequestDTO;
import com.easy.cd.entity.CdUser;
import com.easy.cd.exception.BusinessException;
import com.easy.cd.mapper.CdUserMapper;
import com.easy.cd.service.AuthService;
import com.easy.cd.vo.CurrentUserVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final CdUserMapper cdUserMapper;
    private final SessionManager sessionManager;

    @Override
    public CurrentUserVO login(LoginRequestDTO requestDTO) {
        if (requestDTO.getUsername() == null || requestDTO.getUsername().trim().isEmpty()) {
            throw new BusinessException("用户名不能为空");
        }
        if (requestDTO.getPassword() == null || requestDTO.getPassword().trim().isEmpty()) {
            throw new BusinessException("密码不能为空");
        }

        CdUser user = cdUserMapper.selectOne(new LambdaQueryWrapper<CdUser>()
            .eq(CdUser::getUsername, requestDTO.getUsername().trim()));
        if (user == null) {
            throw new BusinessException("用户名或密码错误");
        }
        if (!requestDTO.getPassword().equals(user.getPassword())) {
            throw new BusinessException("用户名或密码错误");
        }

        LoginUser loginUser = LoginUser.builder()
            .userId(user.getId())
            .username(user.getUsername())
            .roles(Collections.emptyList())
            .build();
        String token = sessionManager.createSession(loginUser);

        return CurrentUserVO.builder()
            .id(user.getId())
            .username(user.getUsername())
            .roles(Collections.emptyList())
            .token(token)
            .build();
    }

    @Override
    public void logout(String token) {
        sessionManager.remove(token);
    }

    @Override
    public CurrentUserVO currentUser() {
        LoginUser loginUser = AuthContext.getCurrentUser();
        if (loginUser == null) {
            throw new BusinessException("未登录");
        }
        return CurrentUserVO.builder()
            .id(loginUser.getUserId())
            .username(loginUser.getUsername())
            .roles(Collections.emptyList())
            .token(loginUser.getToken())
            .build();
    }
}
