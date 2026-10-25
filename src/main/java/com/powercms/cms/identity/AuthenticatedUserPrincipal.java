package com.powercms.cms.identity;

import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

public final class AuthenticatedUserPrincipal implements UserDetails {
    private final UUID userId;
    private final UserDetails delegate;
    private final UUID tokenId;
    private final UUID tokenSiteId;
    private final Set<String> tokenScopes;

    public AuthenticatedUserPrincipal(UUID userId, UserDetails delegate) {
        this(userId, delegate, null, null, null);
    }

    public AuthenticatedUserPrincipal(UUID userId, UserDetails delegate,
                                      UUID tokenId, UUID tokenSiteId, Set<String> tokenScopes) {
        this.userId = userId;
        this.delegate = delegate;
        this.tokenId = tokenId;
        this.tokenSiteId = tokenSiteId;
        this.tokenScopes = tokenScopes;
    }

    public UUID getUserId() { return userId; }
    public boolean isApiToken() { return tokenId != null; }
    public UUID getTokenId() { return tokenId; }
    public UUID getTokenSiteId() { return tokenSiteId; }
    public Set<String> getTokenScopes() { return tokenScopes; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() { return delegate.getAuthorities(); }
    @Override public String getPassword() { return delegate.getPassword(); }
    @Override public String getUsername() { return delegate.getUsername(); }
    @Override public boolean isAccountNonExpired() { return delegate.isAccountNonExpired(); }
    @Override public boolean isAccountNonLocked() { return delegate.isAccountNonLocked(); }
    @Override public boolean isCredentialsNonExpired() { return delegate.isCredentialsNonExpired(); }
    @Override public boolean isEnabled() { return delegate.isEnabled(); }
}
