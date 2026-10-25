package com.powercms.cms.sitepackage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 站点包导入策略配置（{@code powercms.site-package.*}）。
 *
 * <p>对齐 FR-PACKAGE-002/003/004：清单必填项、版本兼容、安全阈值与签名策略都必须可运维调整，
 * 生产默认阻断未签名包（{@link SignaturePolicy#ENFORCE}）。</p>
 */
@Component
@ConfigurationProperties(prefix = "powercms.site-package")
public class SitePackageProperties {

    /** FR-PACKAGE-004：未签名包的处理策略。生产默认 {@code ENFORCE}（阻断）。 */
    public enum SignaturePolicy {
        /** 必须携带可验证签名，缺失即阻断（生产默认）。 */
        ENFORCE,
        /** 允许缺失签名（记录并告警）；签名存在但校验失败一律阻断。 */
        WARN,
        /** 不做签名校验（仅允许用于离线取证/迁移场景），仍校验文件摘要。 */
        OFF
    }

    /** 当前 CMS 版本，用于校验清单 {@code cmsVersionRange}。 */
    private String cmsVersion = "12.0.0";

    private int maxZipSizeMb = 512;

    private int maxFileCount = 10_000;

    private int maxSingleFileMb = 50;

    /** FR-PACKAGE-003：解压后总大小上限（MB）。 */
    private int maxUncompressedMb = 1024;

    /** FR-PACKAGE-003：压缩比上限（解压总量 / 包大小），超出视为压缩炸弹。 */
    private int maxCompressionRatio = 100;

    /** 签名策略。 */
    private SignaturePolicy signaturePolicy = SignaturePolicy.ENFORCE;

    /** keyId → HMAC 密钥；生产应改由密钥管理/环境变量注入。 */
    private Map<String, String> signingKeys = new LinkedHashMap<>(Map.of("dev-2026", ""));

    /** 导入前冲突报告的确认令牌有效期（秒）。 */
    private long consentTtlSeconds = 900;

    /** 确认令牌签名密钥（生产应改由密钥管理注入）。 */
    private String consentSecret = "dev-only-change-this-site-package-consent-secret";

    /**
     * 本实例声明的能力清单（FR-PACKAGE-005 能力冲突）。
     * 清单 {@code requiredCapabilities} 中形如 {@code name:detail} 的条目按 {@code name} 前缀匹配。
     */
    private List<String> supportedCapabilities = List.of(
        "content-type", "template-react", "template-vue", "template-ftl",
        "media", "search", "workflow", "interaction", "analytics", "i18n");

    /** FR-PACKAGE-005：保留（系统）模型编码前缀，站点包不得覆盖。 */
    private List<String> reservedModelPrefixes = List.of("system.");

    /** 入口命令白名单前缀（FR-PACKAGE-003「导入过程不执行站点包中的安装脚本」）。 */
    private List<String> allowedEntrypointPrefixes = List.of(
        "npm run", "npm ci", "npm install", "pnpm run", "pnpm build", "yarn build", "yarn run",
        "vite build", "vue-cli-service build", "next build", "nuxt build", "astro build",
        "tsc", "node build.js");

    /**
     * 需要按命令校验的 entrypoint 键。{@code entrypoints} 同时承载构建命令与产物路径
     * （如 {@code output: dist}），只对命令型键做白名单/元字符校验，避免把目录名当命令误拦。
     */
    private List<String> commandEntrypoints = List.of("build", "preview", "dev", "start", "generate", "ssg");

    /** 站点包内禁止出现的可执行/脚本扩展名（FR-PACKAGE-003 恶意脚本门禁）。 */
    private List<String> forbiddenExtensions = List.of(
        ".sh", ".bash", ".zsh", ".bat", ".cmd", ".ps1", ".exe", ".dll", ".so", ".dylib", ".node", ".jar", ".war");

    /** 完整性校验开关：清单 checksums 与包内文件必须逐一对齐。 */
    private boolean enforceChecksums = true;

    public String getCmsVersion() { return cmsVersion; }
    public void setCmsVersion(String cmsVersion) { this.cmsVersion = cmsVersion; }
    public int getMaxZipSizeMb() { return maxZipSizeMb; }
    public void setMaxZipSizeMb(int maxZipSizeMb) { this.maxZipSizeMb = maxZipSizeMb; }
    public int getMaxFileCount() { return maxFileCount; }
    public void setMaxFileCount(int maxFileCount) { this.maxFileCount = maxFileCount; }
    public int getMaxSingleFileMb() { return maxSingleFileMb; }
    public void setMaxSingleFileMb(int maxSingleFileMb) { this.maxSingleFileMb = maxSingleFileMb; }
    public int getMaxUncompressedMb() { return maxUncompressedMb; }
    public void setMaxUncompressedMb(int maxUncompressedMb) { this.maxUncompressedMb = maxUncompressedMb; }
    public int getMaxCompressionRatio() { return maxCompressionRatio; }
    public void setMaxCompressionRatio(int maxCompressionRatio) { this.maxCompressionRatio = maxCompressionRatio; }
    public SignaturePolicy getSignaturePolicy() { return signaturePolicy; }
    public void setSignaturePolicy(SignaturePolicy signaturePolicy) { this.signaturePolicy = signaturePolicy; }
    public Map<String, String> getSigningKeys() { return signingKeys; }
    public void setSigningKeys(Map<String, String> signingKeys) { this.signingKeys = signingKeys; }
    public long getConsentTtlSeconds() { return consentTtlSeconds; }
    public void setConsentTtlSeconds(long consentTtlSeconds) { this.consentTtlSeconds = consentTtlSeconds; }
    public String getConsentSecret() { return consentSecret; }
    public void setConsentSecret(String consentSecret) { this.consentSecret = consentSecret; }
    public List<String> getSupportedCapabilities() { return supportedCapabilities; }
    public void setSupportedCapabilities(List<String> supportedCapabilities) { this.supportedCapabilities = supportedCapabilities; }
    public List<String> getReservedModelPrefixes() { return reservedModelPrefixes; }
    public void setReservedModelPrefixes(List<String> reservedModelPrefixes) { this.reservedModelPrefixes = reservedModelPrefixes; }
    public List<String> getAllowedEntrypointPrefixes() { return allowedEntrypointPrefixes; }
    public void setAllowedEntrypointPrefixes(List<String> allowedEntrypointPrefixes) { this.allowedEntrypointPrefixes = allowedEntrypointPrefixes; }
    public List<String> getCommandEntrypoints() { return commandEntrypoints; }
    public void setCommandEntrypoints(List<String> commandEntrypoints) { this.commandEntrypoints = commandEntrypoints; }
    public List<String> getForbiddenExtensions() { return forbiddenExtensions; }
    public void setForbiddenExtensions(List<String> forbiddenExtensions) { this.forbiddenExtensions = forbiddenExtensions; }
    public boolean isEnforceChecksums() { return enforceChecksums; }
    public void setEnforceChecksums(boolean enforceChecksums) { this.enforceChecksums = enforceChecksums; }

    /** 取签名密钥；未配置时返回 {@code null}。 */
    String signingKey(String keyId) {
        if (keyId == null) return null;
        String key = signingKeys.get(keyId);
        return key == null || key.isBlank() ? null : key;
    }

    /** 默认签名 keyId（打包/导出使用）。 */
    String defaultSigningKeyId() {
        return signingKeys.keySet().stream().findFirst().orElse(null);
    }
}
