package com.powercms.cms.delivery;

import freemarker.template.TemplateCollectionModel;
import freemarker.template.TemplateHashModelEx;
import freemarker.template.TemplateMethodModelEx;
import freemarker.template.TemplateModel;
import freemarker.template.TemplateScalarModel;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * FreeMarker 渲染模型契约（回归缺陷 ②落库校验用）。
 *
 * <p>「渲染模型顶层键全集」是 {@link RenderContextFactory}、{@code PageRenderService.render}
 * 与 {@code wrapWithTheme} 提供给模板的顶层名字并集。落库前试渲染采用「宽松数据 + 严格顶层」：
 * 已登记键返回万能桩（任何属性 / 调用都继续执行），未登记的顶层名字在严格模式下报
 * 「evaluated to null or missing」——这正是 {@code @powercms.zone} 这类引用不存在命名空间
 * 的坏模板的真实失败形态。数据字段缺失属正常（数据依赖），由调用方按报错根名放行。</p>
 */
public final class FreeMarkerModelContract {

    /**
     * 渲染模型顶层键全集（RenderContextFactory + render() 的 zones/renderedZones/renderComponent
     * + wrapWithTheme 的 pageBody/body/htmlHead/header/footer）。
     */
    public static final Set<String> ROOTS = Set.of(
        "application", "applicationContext", "category", "content", "contentModel",
        "cookies", "crafterEnv", "currentPage", "currentUser", "fields", "flags",
        "globalProperties", "id", "language", "locale", "modePreview",
        "navBreadcrumbBuilder", "navigation", "navTreeBuilder", "now", "page",
        "pagination", "renderBreadcrumb", "request", "requestParameters", "route",
        "routePath", "session", "site", "siteConfig", "siteContext", "siteItemService",
        "title", "url", "urlTransformationService",
        "zones", "renderedZones", "renderComponent",
        "pageBody", "body", "htmlHead", "header", "footer");

    private FreeMarkerModelContract() {
    }

    /** 顶层严格、数据万能的试渲染桩模型：每个登记键均为 {@link PermissiveNode}。 */
    public static Map<String, Object> stubModel() {
        Map<String, Object> model = new LinkedHashMap<>();
        for (String key : ROOTS) {
            model.put(key, PermissiveNode.INSTANCE);
        }
        return model;
    }

    /**
     * 从 FreeMarker 报错文本中提取缺失引用的顶层标识（{@code ==> contentModel.foo} →
     * {@code contentModel}）；非缺失引用类错误返回 {@code null}。
     */
    public static String missingRoot(String errorMessage) {
        if (errorMessage == null) return null;
        java.util.regex.Matcher matcher =
            java.util.regex.Pattern.compile("==>\\s*([A-Za-z_][A-Za-z0-9_]*)").matcher(errorMessage);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 万能桩：标量（空串）/ 哈希（自映射）/ 方法（返回自身）三栖，保证试渲染能走完所有代码路径。 */
    private static final class PermissiveNode
        implements TemplateScalarModel, TemplateHashModelEx, TemplateMethodModelEx {

        static final PermissiveNode INSTANCE = new PermissiveNode();

        @Override
        public String getAsString() {
            return "";
        }

        @Override
        public TemplateModel get(String key) {
            return this;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }

        @Override
        public int size() {
            return 0;
        }

        @Override
        public TemplateCollectionModel keys() {
            return EMPTY_COLLECTION;
        }

        @Override
        public TemplateCollectionModel values() {
            return EMPTY_COLLECTION;
        }

        @Override
        public Object exec(List arguments) {
            return this;
        }
    }

    /** 空集合桩：?keys / ?values 返回空列表，相关循环体跳过但执行继续。 */
    private static final TemplateCollectionModel EMPTY_COLLECTION = new TemplateCollectionModel() {
        @Override
        public freemarker.template.TemplateModelIterator iterator() {
            return new freemarker.template.TemplateModelIterator() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public TemplateModel next() {
                    return null;
                }
            };
        }
    };
}
