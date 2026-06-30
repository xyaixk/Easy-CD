package com.easy.cd.service;

import com.easy.cd.dto.LoginRequestDTO;
import com.easy.cd.vo.CurrentUserVO;

public interface AuthService {
    CurrentUserVO login(LoginRequestDTO requestDTO);

    void logout(String token);

    CurrentUserVO currentUser();
}
