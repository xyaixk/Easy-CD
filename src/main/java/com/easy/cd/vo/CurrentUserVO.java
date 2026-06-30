package com.easy.cd.vo;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class CurrentUserVO {
    private Long id;
    private String username;
    private List<String> roles;
    private String token;
}
