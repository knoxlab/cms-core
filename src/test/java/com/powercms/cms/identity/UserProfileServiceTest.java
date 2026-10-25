package com.powercms.cms.identity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.content.ContentService;
import com.powercms.cms.content.repo.ContentVersionRepository;
import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.model.SystemContentTypeSeedService;
import com.powercms.cms.model.repo.ContentTypeRepository;
import com.powercms.cms.site.repo.SiteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * 用户资料更新的 <b>sys_user 双向同步</b>：资料内容（system.user-profile /
 * system.frontend-user）与账号表是两份数据，会员/站点成员/后台用户列表读的是
 * sys_user 的 displayName / email。历史上只同步 email 不同步 displayName，
 * 导致「会员详情里改显示名，列表不变」——本测试守住这条回归线。
 */
class UserProfileServiceTest {

    private final ContentService contents = mock(ContentService.class);
    private UserProfileService service;
    private UserEntity user;

    @BeforeEach
    void setUp() {
        service = new UserProfileService(mock(SystemContentTypeSeedService.class),
            mock(ContentTypeRepository.class), contents, mock(ContentVersionRepository.class),
            mock(SiteRepository.class), new ObjectMapper());
        user = new UserEntity();
        user.setId(UUID.randomUUID());
        user.setUsername("alice");
        user.setDisplayName("旧显示名");
        user.setEmail("old@example.com");
        user.setProfileContentId(UUID.randomUUID());
    }

    @Test
    void updateProfileSyncsDisplayNameBackToAccount() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("displayName_s", "新显示名");
        values.put("email_s", "new@example.com");

        service.updateProfile(user, values, "admin");

        assertThat(user.getDisplayName()).isEqualTo("新显示名");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
        verify(contents).saveVersion(user.getProfileContentId(), "{\"displayName_s\":\"新显示名\",\"email_s\":\"new@example.com\"}",
            "资料更新", "admin");
    }

    /** 空显示名回置 null：列表与 displayNameOf 按「无显示名」回退到用户名，而不是保留旧值。 */
    @Test
    void updateProfileBlankDisplayNameResetsToNull() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("displayName_s", "   ");

        service.updateProfile(user, values, "admin");

        assertThat(user.getDisplayName()).isNull();
    }

    /** 未携带 displayName_s 的更新（如只改电话）不得动显示名。 */
    @Test
    void updateProfileWithoutDisplayNameLeavesAccountUntouched() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("phone_s", "13800000000");

        service.updateProfile(user, values, "admin");

        assertThat(user.getDisplayName()).isEqualTo("旧显示名");
        assertThat(user.getEmail()).isEqualTo("old@example.com");
    }
}
