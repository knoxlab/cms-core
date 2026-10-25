package com.powercms.cms.identity;

import com.powercms.cms.identity.entity.UserEntity;
import com.powercms.cms.identity.repo.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * OBS-11-04：建议池 / 版本历史里的 actor 解析——用户 ID 转用户名，用户名原样返回。
 */
@ExtendWith(MockitoExtension.class)
class ActorNameResolverTest {

    @Mock UserRepository userRepo;

    private ActorNameResolver resolver() {
        return new ActorNameResolver(userRepo);
    }

    private static UserEntity user(UUID id, String username) {
        UserEntity user = new UserEntity();
        user.setId(id);
        user.setUsername(username);
        return user;
    }

    @Test
    void resolvesUserIdToUsername() {
        UUID id = UUID.randomUUID();
        when(userRepo.findById(id)).thenReturn(java.util.Optional.of(user(id, "editor.s1")));

        assertThat(resolver().resolve(id.toString())).isEqualTo("editor.s1");
    }

    @Test
    void keepsUsernameActorAsIsWithoutQuery() {
        assertThat(resolver().resolve("siteadmin.s1")).isEqualTo("siteadmin.s1");
        verify(userRepo, never()).findById(any());
    }

    @Test
    void keepsUnknownOrNonUuidActorAsIs() {
        UUID missing = UUID.randomUUID();
        when(userRepo.findById(missing)).thenReturn(java.util.Optional.empty());

        assertThat(resolver().resolve(missing.toString())).isEqualTo(missing.toString());
        assertThat(resolver().resolve("system")).isEqualTo("system");
        assertThat(resolver().resolve("")).isEqualTo("");
        assertThat(resolver().resolve(null)).isNull();
        // 36 位但第 9 位不是 '-'：不按 UUID 处理
        assertThat(resolver().resolve("abcdefgh" + "ijklmnopqrstuvwxyz0123456789"))
            .isEqualTo("abcdefghijklmnopqrstuvwxyz0123456789");
        // 36 位且形态像 UUID 但非法（非 16 进制）：解析失败后原样返回
        String malformed = "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz";
        assertThat(resolver().resolve(malformed)).isEqualTo(malformed);
    }

    @Test
    void batchResolvesWithSingleLookupAndFallsBackToInput() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        when(userRepo.findAllById(any())).thenReturn(List.of(user(known, "editor.s1")));

        Map<String, String> names = resolver().resolveAll(List.of(
            known.toString(), unknown.toString(), "siteadmin.s1", "system"));

        assertThat(names).containsEntry(known.toString(), "editor.s1")
            .containsEntry(unknown.toString(), unknown.toString())
            .containsEntry("siteadmin.s1", "siteadmin.s1")
            .containsEntry("system", "system");
        verify(userRepo).findAllById(any());
    }

    @Test
    void batchSkipsLookupWhenNoUserIdActor() {
        Map<String, String> names = resolver().resolveAll(List.of("editor", "system"));

        assertThat(names).containsEntry("editor", "editor").containsEntry("system", "system");
        assertThat(names).hasSize(2);
        verify(userRepo, never()).findAllById(any());
    }

    @Test
    void batchOnEmptyInputReturnsEmptyMap() {
        assertThat(resolver().resolveAll(List.of())).isEmpty();
        assertThat(resolver().resolveAll(null)).isEmpty();
        verify(userRepo, never()).findAllById(any());
    }
}
