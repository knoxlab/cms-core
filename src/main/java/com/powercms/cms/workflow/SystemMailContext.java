package com.powercms.cms.workflow;

import java.util.Optional;

/**
 * 系统邮件上下文（DEF-CFG-03）：组织级「发件身份 + 系统自引用 URL」的读取端口。
 *
 * <p>本接口是 **workflow → identity 的依赖倒置**：组织设置（{@code organization_settings}）
 * 归 identity 模块，而邮件通道在本模块，且 {@code identity} 已依赖 {@code workflow}
 * （见两模块的 {@code package-info}），直接反向引用会构成模块环、被 {@code ModularityTests}
 * 拦下。因此把「读什么」定义在这里、「从哪读」由 identity 提供实现
 * （{@link com.powercms.cms.identity.OrganizationSystemMailContext}），与既有的
 * {@link RecipientEmailResolver} 同一套路。
 *
 * <p>两个方法都返回 {@link Optional}：**未配置是正常状态**，调用方各自回退到静态配置或省略。
 * 不使用 {@code null} 以免把「未配置」与「NPE」混淆。
 */
public interface SystemMailContext {

    /**
     * 组织配置的系统邮件发件地址（spec 5.5：{@code fromEmail}，缺省回退 {@code mailDomain}）。
     *
     * <p>空 ⇒ 调用方沿用静态配置 {@code powercms.mail.from}。
     */
    Optional<String> fromAddress();

    /**
     * 组织配置的系统自引用 URL（spec 5.5：{@code portalUrl}）。
     *
     * <p>用途是拼装**绝对**深链：邮件客户端里 `"/tasks/xxx"` 这种相对路径无处可去，
     * 必须由发信方补全为 `portalUrl + "/tasks/" + taskId`。空 ⇒ 模板省略链接行。
     */
    Optional<String> portalUrl();

    /** 无组织配置时的空实现（单测、无 identity 模块的上下文、未配置环境）。 */
    SystemMailContext EMPTY = new SystemMailContext() {
        @Override
        public Optional<String> fromAddress() {
            return Optional.empty();
        }

        @Override
        public Optional<String> portalUrl() {
            return Optional.empty();
        }
    };
}
