package com.powercms.cms.platform.web;

import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * FR-CV-VER-010（时间机器只读）：「只读态写请求返回 403」的服务端落实。
 *
 * <p>时间机器页面在挂载期间会给每个 API 请求带上 {@value #HEADER} 头（值为所浏览的时间点），
 * 本拦截器据此拒绝一切**状态变更**请求：只要带了这个头且方法不是 GET/HEAD/OPTIONS，一律 403
 * {@link ErrorCodes#TIME_MACHINE_READ_ONLY}。这样即使在只读视图下被脚本或手工构造出写请求，
 * 也不会真的落到业务写链路（界面本身不提供任何编辑/发布入口，见 §15.5）。</p>
 *
 * <h2>为什么是请求头而不是「服务端只读模式」</h2>
 * <p>只读是**这一次浏览**的属性，不该变成会话级状态：若把「进入时间机器」做成服务端开关，
 * 用户直接关掉标签页就会把开关留在打开状态，之后所有编辑都莫名 403，且没有自然的恢复时机。
 * 用请求头表达则天然随页面生命周期生效/失效（组件卸载即撤销），不会残留。</p>
 *
 * <p><b>边界</b>：该头由客户端声明，因此这是「防误操作」的护栏（配合界面禁用入口），
 * 不是权限边界——真正的写权限仍由各接口的 {@code @PreAuthorize} 负责。认证接口
 * （{@code /auth/**}）不在拦截范围内：登出属于会话管理，若被拦会导致用户在只读视图里无法退出。</p>
 */
@Configuration
public class TimeMachineReadOnlyInterceptor implements WebMvcConfigurer {

    /** 时间机器页面挂载期间随请求下发的只读标记头；值即所浏览的时间点（ISO-8601）。 */
    public static final String HEADER = "X-PowerCMS-Read-Only-Snapshot";

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new Guard()).addPathPatterns("/api/v1/**")
            .excludePathPatterns("/api/v1/auth/**");
    }

    static final class Guard implements HandlerInterceptor {
        @Override
        public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
            String snapshotAt = request.getHeader(HEADER);
            if (snapshotAt == null || snapshotAt.isBlank()) return true;
            String method = request.getMethod();
            if ("GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method)) return true;
            throw new BusinessException(ErrorCodes.TIME_MACHINE_READ_ONLY, "时间机器视图只读，禁止写入操作");
        }
    }
}
