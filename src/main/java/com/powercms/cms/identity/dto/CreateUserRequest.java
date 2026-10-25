package com.powercms.cms.identity.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateUserRequest(
    @NotBlank @Size(min = 3, max = 100)
    @Pattern(regexp = "[A-Za-z0-9._-]+", message = "用户名仅允许字母数字和 ._-")
    String username,
    @NotBlank @Size(min = 8, max = 100) String password,
    @NotBlank @Size(max = 200) String displayName,
    @Email String email,
    /** BACKEND（默认）或 FRONTEND；FRONTEND 必须指定 homeSiteId（spec 5.3）。 */
    String kind,
    UUID homeSiteId
) {
    public CreateUserRequest(String username, String password, String displayName, String email) {
        this(username, password, displayName, email, null, null);
    }
}
