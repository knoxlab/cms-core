package com.powercms.cms.identity;

import com.powercms.cms.audit.AuditService;
import com.powercms.cms.identity.entity.OrganizationEntity;
import com.powercms.cms.identity.repo.BackendLayoutRepository;
import com.powercms.cms.identity.repo.OrganizationRepository;
import com.powercms.cms.identity.repo.OrganizationSettingsRepository;
import com.powercms.cms.identity.repo.RoleLayoutRepository;
import com.powercms.cms.identity.repo.RoleRepository;
import com.powercms.cms.identity.repo.SiteAuthorizationPolicyRepository;
import com.powercms.cms.identity.repo.UserPasswordHistoryRepository;
import com.powercms.cms.identity.repo.UserRepository;
import com.powercms.cms.identity.repo.UserRoleRepository;
import com.powercms.cms.site.repo.SiteRepository;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * DEF-CFG-01：组织供应的**并发安全**与「当前组织」**唯一口径**。
 *
 * <p>域 11 实测：新装/首启时 {@code IdentityBootstrap}（ApplicationReadyEvent）与
 * {@code OrganizationContextFilter}（@Order(1)，服务首个请求）会并发进入空表分支，各建一行同名
 * 「默认组织」（createdAt 相差 8ms）。此后写入侧 {@code ensureOrganization} 取
 * {@code findAll()}（**无序**）的第一行，读取侧 {@code OrganizationSettingsService.defaultOrganizationId}
 * 取 {@code findAllIds()}（createdAt 升序）的第一行 —— 两者指向不同组织，组织设置便「保存成功但
 * 完全不生效」（loginIdentifier / 登录页品牌 / 组织默认语言三者同时失效）。
 *
 * <p>本用例用「模拟真实仓储」的落库集合 + 门闩制造确定性的并发窗口，判别力有两点：
 * <ol>
 *   <li>并发供应只允许产生**一个**组织（去掉临界区/锁内复查即会建成两个）；</li>
 *   <li>读写两侧必须指向**同一个**组织，即使无序 {@code findAll()} 恰好奇偶相反（去掉统一口径即不一致）。</li>
 * </ol>
 */
class OrganizationProvisioningTest {

    private final OrganizationRepository orgRepo = mock(OrganizationRepository.class);
    private final RoleRepository roleRepo = mock(RoleRepository.class);

    /** 模拟真实仓储「提交后才可见」的落库集合。 */
    private final List<UUID> stored = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger existenceChecks = new AtomicInteger();
    private final CountDownLatch bothSawEmptyTable = new CountDownLatch(2);
    private volatile boolean holdFirstCheckers = false;

    private IdentityServiceImpl service;

    private IdentityServiceImpl newService() {
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        // REQUIRES_NEW 建组织事务：真实 TransactionTemplate 只要求能开/提交一个状态对象。
        when(txManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        return new IdentityServiceImpl(mock(UserRepository.class), roleRepo,
            mock(UserRoleRepository.class), orgRepo,
            mock(SiteAuthorizationPolicyRepository.class), mock(SiteRepository.class),
            mock(BackendLayoutRepository.class), mock(RoleLayoutRepository.class),
            mock(SiteCapabilityEvaluator.class), encoder, mock(AuditService.class),
            mock(UserPasswordHistoryRepository.class), mock(UserProfileService.class),
            new IdentityProperties(), mock(UserReferenceMigrator.class), txManager);
    }

    /** 存在性检查：返回当前已落库的组织，并按需制造「两个创建者同时看到空表」的窗口。 */
    private List<UUID> existenceCheck() {
        if (holdFirstCheckers && existenceChecks.incrementAndGet() <= 2) {
            bothSawEmptyTable.countDown();
            try {
                bothSawEmptyTable.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        return List.copyOf(stored);
    }

    private OrganizationEntity persist(OrganizationEntity org) {
        org.setId(UUID.randomUUID());
        stored.add(org.getId());
        return org;
    }

    private static OrganizationEntity entity(UUID id) {
        OrganizationEntity e = new OrganizationEntity();
        e.setId(id);
        e.setName("默认组织");
        return e;
    }

    private void stubRepository() {
        when(orgRepo.findAllIds()).thenAnswer(inv -> existenceCheck());
        // 无序 findAll：域 11 现场就是它与 createdAt 升序结果不一致
        when(orgRepo.findAll()).thenAnswer(inv -> existenceCheck().stream()
            .map(OrganizationProvisioningTest::entity).toList());
        when(orgRepo.save(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
        when(orgRepo.saveAndFlush(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
    }

    @Test
    void concurrentProvisioningCreatesExactlyOneOrganization() throws Exception {
        stubRepository();
        service = newService();
        holdFirstCheckers = true;

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<UUID>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return service.ensureOrganization("默认组织");
                }));
            }
            start.countDown();
            List<UUID> resolved = new ArrayList<>();
            for (Future<UUID> future : futures) resolved.add(future.get(20, TimeUnit.SECONDS));

            assertThat(stored)
                .as("引导事件与首个请求过滤器并发进入空表分支时，只允许创建一个组织（DEF-CFG-01）")
                .hasSize(1);
            assertThat(resolved)
                .as("两个并发创建者必须拿到同一个组织，而不是各建一个")
                .containsOnly(stored.get(0));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void readSideAndWriteSideResolveTheSameOrganization() {
        UUID oldest = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        stored.add(oldest);
        stored.add(newer);
        when(orgRepo.findAllIds()).thenReturn(List.of(oldest, newer));
        // 无序 findAll 恰好奇偶相反 —— 这正是让「写一个组织、读另一个组织」显形的现场
        when(orgRepo.findAll()).thenReturn(List.of(entity(newer), entity(oldest)));
        when(orgRepo.save(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
        when(orgRepo.saveAndFlush(any())).thenAnswer(inv -> persist(inv.getArgument(0)));
        service = newService();
        OrganizationSettingsService settingsService = new OrganizationSettingsService(
            mock(OrganizationSettingsRepository.class), orgRepo, mock(UserRepository.class));

        assertThat(settingsService.defaultOrganizationId())
            .as("读取侧（组织设置/登录解析/公开品牌）取最早创建的组织")
            .isEqualTo(oldest);
        assertThat(service.ensureOrganization("默认组织"))
            .as("写入侧（请求属性 organizationId）必须与读取侧同源，否则组织设置会静默失效")
            .isEqualTo(oldest);
        assertThat(stored).as("已存在组织时不得再创建").hasSize(2);
    }

    @Test
    void currentOrganizationIsStableAcrossRepeatedResolution() {
        UUID only = UUID.randomUUID();
        stored.add(only);
        stubRepository();
        service = newService();

        assertThat(service.ensureOrganization("默认组织")).isEqualTo(only);
        assertThat(service.ensureOrganization("另一个名字")).isEqualTo(only);
        assertThat(stored).as("重复供应是幂等的").hasSize(1);
    }
}
