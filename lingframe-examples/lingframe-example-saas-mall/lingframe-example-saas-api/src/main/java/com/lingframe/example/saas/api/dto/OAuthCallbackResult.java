package com.lingframe.example.saas.api.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class OAuthCallbackResult implements Serializable {
    private static final long serialVersionUID = 1L;

    private String openId;
    private String nickname;
    private String avatar;
    private String platform;
    private String tenantId;
}
