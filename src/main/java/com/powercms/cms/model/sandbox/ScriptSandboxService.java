package com.powercms.cms.model.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.powercms.common.error.ApiError;
import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;
import javax.script.SimpleBindings;
import org.springframework.stereotype.Service;

/**
 * 服务端脚本沙箱（FR-CM-FTSUP-008、FR-TPL-013）：为 CUSTOM_CODE 等字段的渲染/校验代码提供受限执行。
 *
 * <p>安全策略分层：
 * <ol>
 *   <li>静态校验：语言白名单、禁止标记黑名单、花括号平衡、长度与超时上限——可确定性阻断逃逸；
 *   <li>运行时隔离：仅注入 JSON 原生值（字符串/数字/布尔/列表/Map），绝不注入 Java 对象或类引用；
 *   <li>超时中断：在独立线程中执行并强制中断，防止死循环。
 * </ol>
 * 无可用脚本引擎时退化为“仅校验”模式，不执行任意代码。
 */
@Service
public class ScriptSandboxService {
  private static final Set<String> ALLOWED_LANGS = Set.of("JS", "JAVASCRIPT", "GROOVY");
  private static final Set<String> FORBIDDEN =
      Set.of(
          "system.exit",
          "runtime.getruntime",
          "processbuilder",
          "java.io",
          "java.nio",
          "java.net",
          "java.lang.reflect",
          "socket",
          "urlconnection",
          "classloader",
          "class.forname",
          "getruntime",
          "exec(",
          "nashorn",
          "import ",
          "require(",
          "eval(",
          "function(",
          "=>");

  private static final int MAX_CODE_LENGTH = 16_384;
  private static final long DEFAULT_TIMEOUT_MS = 3_000;

  private final ObjectMapper om;
  private final ScriptEngineManager engineManager = new ScriptEngineManager();
  private final ExecutorService executor = Executors.newCachedThreadPool();

  ScriptSandboxService(ObjectMapper om) {
    this.om = om;
  }

  public record ScriptResult(
      boolean valid, boolean executed, String output, List<String> errors, String message) {}

  /** 执行渲染代码，返回渲染结果字符串（FR-CM-FTSUP-008）。 */
  public ScriptResult render(String code, String lang, JsonNode input) {
    return run(code, lang, input);
  }

  /** 执行校验代码；期望返回布尔值或字符串（非空/非 "false" 视为通过）。 */
  public ScriptResult validate(String code, String lang, JsonNode input) {
    ScriptResult result = run(code, lang, input);
    if (!result.valid()) return result;
    if (!result.executed()) return result;
    String output = result.output() == null ? "" : result.output().trim();
    boolean passed = !output.isBlank() && !"false".equalsIgnoreCase(output);
    return new ScriptResult(
        passed, true, output, passed ? List.of() : List.of("校验未通过"), passed ? "校验通过" : "校验未通过");
  }

  /** 执行脚本并返回原生结果（Map/List/标量），供 compose 等结构化输出使用。 */
  public Object evaluate(String code, String lang, Map<String, Object> extraBindings) {
    List<String> errors = validateSource(code, lang);
    if (!errors.isEmpty())
      throw new BusinessException(
          ErrorCodes.CUSTOM_CODE_COMPILE_ERROR,
          errors.get(0),
          List.of(new ApiError.FieldError("code", errors.get(0))));

    String engineName = normalizeLang(lang);
    ScriptEngine engine = engineManager.getEngineByName(engineName);
    if (engine == null)
      throw new BusinessException(
          ErrorCodes.CUSTOM_CODE_EXECUTION_ERROR, "未检测到脚本引擎: " + engineName);

    Bindings bindings = new SimpleBindings();
    if (extraBindings != null) {
      for (Map.Entry<String, Object> e : extraBindings.entrySet()) {
        bindings.put(e.getKey(), sanitizeBinding(e.getValue()));
      }
    }

    Future<Object> future =
        executor.submit(
            () -> {
              try {
                return engine.eval(code, bindings);
              } catch (ScriptException e) {
                throw new RuntimeException(e);
              }
            });
    try {
      return future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new BusinessException(ErrorCodes.CUSTOM_CODE_EXECUTION_ERROR, "脚本执行超时");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new BusinessException(ErrorCodes.CUSTOM_CODE_EXECUTION_ERROR, "脚本执行被中断");
    } catch (ExecutionException e) {
      throw new BusinessException(
          ErrorCodes.CUSTOM_CODE_EXECUTION_ERROR, "脚本执行失败: " + rootMessage(e));
    }
  }

  /** 仅允许 JSON 原生值进入脚本绑定，防止注入 Java 类型能力。 */
  @SuppressWarnings("unchecked")
  private Object sanitizeBinding(Object value) {
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> out = new java.util.LinkedHashMap<>();
      for (Map.Entry<?, ?> e : map.entrySet()) {
        if (e.getKey() == null) continue;
        out.put(String.valueOf(e.getKey()), sanitizeBinding(e.getValue()));
      }
      return out;
    }
    if (value instanceof List<?> list) {
      List<Object> out = new ArrayList<>();
      for (Object item : list) out.add(sanitizeBinding(item));
      return out;
    }
    // Drop callables / Java objects — compose helpers are inlined in script preamble
    return String.valueOf(value);
  }

  private ScriptResult run(String code, String lang, JsonNode input) {
    List<String> errors = validateSource(code, lang);
    if (!errors.isEmpty())
      throw new BusinessException(
          ErrorCodes.CUSTOM_CODE_COMPILE_ERROR, errors.get(0), List.of(new ApiError.FieldError("code", errors.get(0))));

    String engineName = normalizeLang(lang);
    ScriptEngine engine = engineManager.getEngineByName(engineName);
    if (engine == null)
      return new ScriptResult(true, false, null, List.of(), "未检测到脚本引擎，已通过静态安全校验但未执行");

    Object value = toNative(input);
    Bindings bindings = new SimpleBindings();
    bindings.put("value", value);
    bindings.put("input", value);

    Future<Object> future =
        executor.submit(
            () -> {
              try {
                return engine.eval(code, bindings);
              } catch (ScriptException e) {
                throw new RuntimeException(e);
              }
            });
    try {
      Object result = future.get(DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
      return new ScriptResult(true, true, String.valueOf(result), List.of(), "执行完成");
    } catch (TimeoutException e) {
      future.cancel(true);
      throw new BusinessException(ErrorCodes.CUSTOM_CODE_EXECUTION_ERROR, "脚本执行超时");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      future.cancel(true);
      throw new BusinessException(ErrorCodes.CUSTOM_CODE_EXECUTION_ERROR, "脚本执行被中断");
    } catch (ExecutionException e) {
      throw new BusinessException(ErrorCodes.CUSTOM_CODE_EXECUTION_ERROR, "脚本执行失败: " + rootMessage(e));
    }
  }

  private static List<String> validateSource(String code, String lang) {
    List<String> errors = new ArrayList<>();
    if (code == null || code.isBlank()) {
      errors.add("代码不能为空");
      return errors;
    }
    if (code.length() > MAX_CODE_LENGTH) {
      errors.add("代码长度超过上限 " + MAX_CODE_LENGTH);
      return errors;
    }
    String normalized = lang == null ? "JS" : lang.trim().toUpperCase(Locale.ROOT);
    if (!ALLOWED_LANGS.contains(normalized)) errors.add("不支持的脚本语言: " + lang);
    String lower = code.toLowerCase(Locale.ROOT);
    for (String token : FORBIDDEN)
      if (lower.contains(token)) errors.add("代码包含禁止标记: " + token);
    int braces = 0;
    for (char c : code.toCharArray()) {
      if (c == '{') braces++;
      else if (c == '}' && --braces < 0) break;
    }
    if (braces != 0) errors.add("花括号不平衡");
    return errors;
  }

  private Object toNative(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isBoolean()) return node.asBoolean();
    if (node.isInt()) return node.asInt();
    if (node.isLong()) return node.asLong();
    if (node.isDouble() || node.isFloat()) return node.asDouble();
    if (node.isTextual()) return node.asText();
    if (node.isArray()) {
      List<Object> list = new ArrayList<>();
      node.forEach(child -> list.add(toNative(child)));
      return list;
    }
    if (node.isObject()) {
      Map<String, Object> map = new java.util.LinkedHashMap<>();
      node.fields().forEachRemaining(e -> map.put(e.getKey(), toNative(e.getValue())));
      return map;
    }
    return node.asText();
  }

  private static String normalizeLang(String lang) {
    return "GROOVY".equalsIgnoreCase(lang == null ? "" : lang.trim()) ? "groovy" : "js";
  }

  private static String rootMessage(Throwable t) {
    Throwable cur = t;
    while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
    return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
  }
}
