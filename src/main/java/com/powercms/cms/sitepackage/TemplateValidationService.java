package com.powercms.cms.sitepackage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.cms.delivery.FreeMarkerSandbox;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 模板落库前静态校验（FR-AGT-TPL-003）：安全黑名单 + FreeMarker AST 解析（或设计器 JSON 结构）双重校验。
 *
 * <p>单一实现来源：
 * <ul>
 *   <li>安全黑名单与体积上限复用 {@link TemplateSecurityValidator}（与 {@code save_template} 同源）；</li>
 *   <li>FreeMarker 语法复用 {@link FreeMarkerSandbox#parse}（与线上渲染同一沙箱配置：SAFER_RESOLVER、关闭 ?api）。</li>
 * </ul>
 * 本服务只读、无副作用，不落库、不渲染、不执行模板逻辑。
 */
@Service
public class TemplateValidationService {

    /** 模板形态：FreeMarker 代码模板。 */
    public static final String MODE_FREEMARKER = "freemarker";
    /** 模板形态：可视化设计器 JSON 布局。 */
    public static final String MODE_JSON = "json";

    private final FreeMarkerSandbox sandbox;
    private final ObjectMapper objectMapper;

    public TemplateValidationService(FreeMarkerSandbox sandbox, ObjectMapper objectMapper) {
        this.sandbox = sandbox;
        this.objectMapper = objectMapper;
    }

    /** 校验结果：安全项 + 语法/结构项 + 是否整体通过。 */
    public record ValidationResult(boolean valid, String mode, List<String> securityIssues,
                                   boolean syntaxValid, Integer line, Integer column, String message) {
        public List<String> issues() {
            List<String> all = new ArrayList<>(securityIssues);
            if (!syntaxValid && message != null && !message.isBlank()) {
                all.add(message);
            }
            return all;
        }
    }

    /** 规范化形态（未知值回退 FreeMarker，由调用方决定是否先前置拒绝）。 */
    public static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return MODE_FREEMARKER;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        return MODE_JSON.equals(value) ? MODE_JSON : MODE_FREEMARKER;
    }

    /** 校验模板内容；{@code mode} 取 {@code freemarker}（默认）或 {@code json}。 */
    public ValidationResult validate(String content, String mode, String templateName) {
        String normalized = normalizeMode(mode);
        List<String> securityIssues = TemplateSecurityValidator.validate(content);
        if (!securityIssues.isEmpty()) {
            return new ValidationResult(false, normalized, securityIssues, false, null, null, null);
        }
        if (MODE_JSON.equals(normalized)) {
            return validateJson(content, normalized);
        }
        FreeMarkerSandbox.ParseResult parsed =
            sandbox.parse(templateName == null || templateName.isBlank() ? "preview.ftl" : templateName, content);
        return new ValidationResult(parsed.valid(), normalized, List.of(),
            parsed.valid(), parsed.line(), parsed.column(), parsed.message());
    }

    private ValidationResult validateJson(String content, String mode) {
        try {
            JsonNode node = objectMapper.readTree(content);
            if (node == null || !node.isObject()) {
                return new ValidationResult(false, mode, List.of(), false, null, null,
                    "设计器 JSON 必须为对象（含 type 字段）");
            }
            if (node.path("type").asText("").isBlank()) {
                return new ValidationResult(false, mode, List.of(), false, null, null,
                    "设计器 JSON 缺少 type 字段");
            }
            return new ValidationResult(true, mode, List.of(), true, null, null, null);
        } catch (Exception e) {
            return new ValidationResult(false, mode, List.of(), false, null, null,
                "设计器 JSON 解析失败: " + (e.getMessage() == null ? "非法 JSON" : e.getMessage()));
        }
    }
}
