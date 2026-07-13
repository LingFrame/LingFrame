package com.lingframe.example.saas.api;

import com.lingframe.example.saas.api.dto.OAuthRenderResult;
import com.lingframe.example.saas.api.dto.OAuthCallbackResult;

public interface OAuthAbility {

    OAuthRenderResult render(String tenantId, String platform);

    OAuthCallbackResult callback(String tenantId, String platform, String code);
}
