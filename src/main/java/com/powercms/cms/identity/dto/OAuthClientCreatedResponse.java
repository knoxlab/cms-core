package com.powercms.cms.identity.dto;

import java.time.Instant;
import java.util.List;

/** 客户端创建响应：client_secret 仅此一次返回明文。 */
public record OAuthClientCreatedResponse(
    String clientId,
    String name,
    List<String> redirectUris,
    List<String> scopes,
    Instant createdAt,
    String clientSecret
) {}
