package com.lingframe.example.saas.api.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class OAuthUserInfo implements Serializable {
    private static final long serialVersionUID = 1L;

    private String openId;
    private String nickname;
    private String avatar;
}
