package com.powercms.cms.delivery;

import freemarker.cache.StringTemplateLoader;
import freemarker.core.TemplateClassResolver;
import freemarker.ext.beans.BeansWrapper;
import freemarker.template.Configuration;
import freemarker.template.DefaultObjectWrapperBuilder;
import freemarker.template.Template;
import freemarker.template.TemplateException;
import freemarker.template.TemplateExceptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.StringWriter;
import java.util.Map;
import java.util.UUID;

/**
 * FreeMarker 沙箱渲染（FR-LANG-003/004、FR-SANDBOX-008/010/011）：
 * 禁止静态方法与危险 new、关闭 ?api；预览可诊断行号，生产隐藏细节并附 traceId。
 */
@Component
public class FreeMarkerSandbox {
    private static final Logger log = LoggerFactory.getLogger(FreeMarkerSandbox.class);

    /**
     * 模板静态解析结果（FR-AGT-TPL-003）：只做 AST 解析，不执行、不注入数据模型。
     *
     * @param valid   是否解析通过（无未知标签 / 语法错误）
     * @param line    错误行号（1 起，解析通过为 null）
     * @param column  错误列号（解析通过为 null）
     * @param message 错误明细（解析通过为 null）
     */
    public record ParseResult(boolean valid, Integer line, Integer column, String message) {
        public static ParseResult ok() {
            return new ParseResult(true, null, null, null);
        }
    }

    /**
     * 静态解析 FreeMarker 模板（不渲染）：复用与渲染一致的沙箱配置（SAFER_RESOLVER、关闭 ?api），
     * 未知标签 / 语法错误在解析阶段即被拒绝，供 Agent 落库前校验与代码生成自检使用。
     */
    public ParseResult parse(String templateName, String source) {
        Configuration config = secureConfiguration(true);
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate(templateName, source);
        config.setTemplateLoader(loader);
        try {
            config.getTemplate(templateName);
            return ParseResult.ok();
        } catch (IOException ex) {
            int line = -1;
            int col = -1;
            String detail = ex.getMessage();
            if (ex instanceof freemarker.core.ParseException parseEx) {
                line = parseEx.getLineNumber();
                col = parseEx.getColumnNumber();
                detail = parseEx.getMessage();
            }
            log.debug("FreeMarker 静态解析失败 template={} line={} col={}", templateName, line, col);
            return new ParseResult(false, line > 0 ? line : null, col > 0 ? col : null,
                detail == null ? "模板解析失败" : detail.trim());
        } catch (RuntimeException ex) {
            return new ParseResult(false, null, null,
                ex.getMessage() == null ? "模板解析失败" : ex.getMessage());
        }
    }

    /**
     * 渲染期校验（回归缺陷 ②）：以 RETHROW 语义试渲染模板，捕获缺失引用 / 类型错误 / 解析失败，
     * 供可视化模板 CODE 落库前校验。不产生任何数据副作用，返回与 {@link ParseResult} 同形诊断。
     */
    public ParseResult renderCheck(String templateName, String source, Map<String, Object> model) {
        Configuration config = secureConfiguration(false);
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate(templateName, source);
        config.setTemplateLoader(loader);
        try {
            Template template = config.getTemplate(templateName);
            StringWriter output = new StringWriter();
            template.process(model, output);
            return ParseResult.ok();
        } catch (TemplateException ex) {
            String detail = ex.getMessageWithoutStackTop();
            return new ParseResult(false,
                ex.getLineNumber() > 0 ? ex.getLineNumber() : null,
                ex.getColumnNumber() > 0 ? ex.getColumnNumber() : null,
                detail == null || detail.isBlank() ? "模板渲染失败" : detail.trim());
        } catch (IOException ex) {
            int line = -1;
            int col = -1;
            String detail = ex.getMessage();
            if (ex instanceof freemarker.core.ParseException parseEx) {
                line = parseEx.getLineNumber();
                col = parseEx.getColumnNumber();
                detail = parseEx.getMessage();
            }
            return new ParseResult(false, line > 0 ? line : null, col > 0 ? col : null,
                detail == null ? "模板解析失败" : detail.trim());
        } catch (RuntimeException ex) {
            return new ParseResult(false, null, null,
                ex.getMessage() == null ? "模板渲染失败" : ex.getMessage());
        }
    }

    public String render(String templateName, String source, Map<String, Object> model, boolean preview) {
        Configuration config = secureConfiguration(preview);
        StringTemplateLoader loader = new StringTemplateLoader();
        loader.putTemplate(templateName, source);
        config.setTemplateLoader(loader);
        try {
            Template template = config.getTemplate(templateName);
            StringWriter output = new StringWriter();
            template.process(model, output);
            return output.toString();
        } catch (TemplateException ex) {
            String traceId = currentTraceId();
            log.warn("FreeMarker 渲染失败 template={} line={} col={} traceId={}",
                templateName, ex.getLineNumber(), ex.getColumnNumber(), traceId, ex);
            if (preview) {
                throw new IllegalArgumentException(diagnosticMessage(templateName, ex.getLineNumber(),
                    ex.getColumnNumber(), ex.getMessageWithoutStackTop(), traceId), ex);
            }
            throw new IllegalArgumentException(
                "页面暂时无法渲染。请稍后重试或联系管理员（traceId=" + traceId + "）", ex);
        } catch (IOException ex) {
            String traceId = currentTraceId();
            int line = -1;
            int col = -1;
            String detail = ex.getMessage();
            if (ex instanceof freemarker.core.ParseException parseEx) {
                line = parseEx.getLineNumber();
                col = parseEx.getColumnNumber();
                detail = parseEx.getMessage();
            }
            log.warn("FreeMarker 读取/解析模板失败 template={} line={} col={} traceId={}",
                templateName, line, col, traceId, ex);
            if (preview) {
                throw new IllegalArgumentException(
                    diagnosticMessage(templateName, line, col, detail, traceId), ex);
            }
            throw new IllegalArgumentException(
                "页面暂时无法渲染。请稍后重试或联系管理员（traceId=" + traceId + "）", ex);
        }
    }

    Configuration secureConfiguration(boolean preview) {
        Configuration config = new Configuration(Configuration.VERSION_2_3_34);
        config.setDefaultEncoding("UTF-8");
        config.setAPIBuiltinEnabled(false);
        config.setRecognizeStandardFileExtensions(true);
        try {
            config.setOutputFormat(freemarker.core.HTMLOutputFormat.INSTANCE);
        } catch (Exception ignored) {
            // older FreeMarker builds may not expose HTMLOutputFormat the same way
        }
        config.setNewBuiltinClassResolver(TemplateClassResolver.SAFER_RESOLVER);
        DefaultObjectWrapperBuilder wrapper = new DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_34);
        wrapper.setExposureLevel(BeansWrapper.EXPOSE_PROPERTIES_ONLY);
        config.setObjectWrapper(wrapper.build());
        config.setTemplateExceptionHandler(
            preview ? TemplateExceptionHandler.DEBUG_HANDLER : TemplateExceptionHandler.RETHROW_HANDLER);
        config.setLogTemplateExceptions(false);
        config.setWrapUncheckedExceptions(true);
        return config;
    }

    private static String diagnosticMessage(String templateName, int line, int col, String detail, String traceId) {
        StringBuilder sb = new StringBuilder("页面模板渲染失败: ").append(templateName);
        if (line > 0) {
            sb.append("（行 ").append(line);
            if (col > 0) sb.append(" 列 ").append(col);
            sb.append('）');
        }
        if (detail != null && !detail.isBlank()) sb.append(": ").append(detail.trim());
        sb.append(" [traceId=").append(traceId).append(']');
        return sb.toString();
    }

    private static String currentTraceId() {
        String traceId = MDC.get("traceId");
        return traceId != null && !traceId.isBlank() ? traceId : UUID.randomUUID().toString();
    }
}
