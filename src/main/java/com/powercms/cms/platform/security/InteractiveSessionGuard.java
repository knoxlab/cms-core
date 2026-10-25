package com.powercms.cms.platform.security;

import com.powercms.cms.identity.AuthenticatedUserPrincipal;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * 凭据管理面的会话门槛（规格 §10.2「刻意不工具化的边界」）。
 *
 * <p>「API Token 与 OAuth 客户端管理」刻意不提供 Agent 工具出口，目的是防止 Agent 自我扩权；
 * 但凭据本身是全域 Bearer，工具面的边界并不自动等于 HTTP 面的边界。本守卫把该治理边界落到
 * REST 上：Token 管理与 OAuth 客户端/授权同意管理只允许<b>交互式登录会话</b>
 * （密码或会话登录，{@code tokenId == null}）。
 *
 * <p>若允许机读凭据调用这些端点，收敛形同虚设：
 * <ul>
 *   <li>{@code POST /api/v1/me/tokens} 可签出 scope 更宽、且不绑定站点的 Token，
 *       绕过 {@link SiteAuthorization} 的 {@code tokenPermits} 收敛；</li>
 *   <li>{@code POST /api/v1/oauth/clients} 可注册任意 scope 的 OAuth 客户端并换取对应凭据；</li>
 *   <li>{@code POST /oauth/authorize} 可代替人工完成 consent，换取更高 MCP 级别的授权码。</li>
 * </ul>
 *
 * <p>注意：API Token 与 OAuth access token 均以带 {@code tokenId} 的
 * {@link AuthenticatedUserPrincipal} 认证，故 {@code isApiToken()} 同时覆盖两者。
 */
@Component
public class InteractiveSessionGuard {

    /**
     * 断言调用者是交互式会话。非交互式（机读）凭据调用时抛
     * {@code TOKEN_SCOPE_DENIED}（由 {@code ApiExceptionHandler} 映射为 HTTP 403）。
     */
    public boolean requireInteractive(Authentication authentication) {
        if (authentication != null
            && authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal
            && principal.isApiToken()) {
            throw new BusinessException(ErrorCodes.TOKEN_SCOPE_DENIED,
                "凭据管理仅限交互式登录会话，API Token / OAuth access token 不得用于签发或管理凭据");
        }
        return true;
    }
}
