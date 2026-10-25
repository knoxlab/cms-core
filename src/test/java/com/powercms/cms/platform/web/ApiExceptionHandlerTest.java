package com.powercms.cms.platform.web;

import com.powercms.common.error.BusinessException;
import com.powercms.common.error.ErrorCodes;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler(
        new org.springframework.context.support.StaticMessageSource());

    @Test
    void missingStaticResourceIsNotInternalError() {
        var response = handler.handleMissingResource(new NoResourceFoundException(HttpMethod.GET, "."));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.NOT_FOUND);
    }

    /**
     * OBS-PKG-03：已服务过的静态文件在运行期被删除后，资源解析链抛
     * {@code FileNotFoundException: … cannot be resolved in the file system for checking its last-modified timestamp}，
     * 语义是「资源不存在」，必须 404 而不是 500。
     */
    @Test
    void deletedStaticFileIsNotFoundNotInternalError() {
        var missingFile = handler.handleMissingResource(new java.io.FileNotFoundException(
            "URL [file:/app/admin-ui/dist/assets/app-1.js] cannot be resolved in the file system "
                + "for checking its last-modified timestamp"));
        assertThat(missingFile.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missingFile.getBody()).isNotNull();
        assertThat(missingFile.getBody().code()).isEqualTo(ErrorCodes.NOT_FOUND);

        var noSuchFile = handler.handleMissingResource(
            new java.nio.file.NoSuchFileException("/app/admin-ui/dist/assets/app-1.js"));
        assertThat(noSuchFile.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(noSuchFile.getBody()).isNotNull();
        assertThat(noSuchFile.getBody().code()).isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void missingRequestParameterIsBadRequest() {
        var response = handler.handleBadRequest(new MissingServletRequestParameterException("username", "String"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.BAD_REQUEST);
    }

    /**
     * DEF-MS-03：路径变量类型不匹配（如 {@code /media/knox/bindings/not-a-uuid} 的 siteId 不是 UUID）
     * 是客户端错误，必须 400 而不是落到兜底 500 —— 后者会把参数拼错误报成服务端故障并刷 ERROR 日志。
     */
    @Test
    void typeMismatchedPathVariableIsBadRequestNotInternalError() {
        var ex = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
            "not-a-uuid", java.util.UUID.class, "siteId", null, null);

        var response = handler.handleBadRequest(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.BAD_REQUEST);
    }

    /**
     * OBS-01：路径存在但方法不支持（{@code PATCH /api/v1/contents/{id}}）必须是 405 + Allow，
     * 而不是被兜底处理器吞掉成 500。
     */
    @Test
    void methodNotSupportedIsMethodNotAllowedWithAllowHeader() {
        var ex = new HttpRequestMethodNotSupportedException("PATCH", java.util.List.of("GET", "PUT", "DELETE"));
        var response = handler.handleMethodNotSupported(ex);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.METHOD_NOT_ALLOWED);
        assertThat(response.getHeaders().getAllow())
            .containsExactlyInAnyOrder(HttpMethod.GET, HttpMethod.PUT, HttpMethod.DELETE);
    }

    @Test
    void mediaTypeNotSupportedIsUnsupportedMediaType() {
        var response = handler.handleMediaTypeNotSupported(
            new HttpMediaTypeNotSupportedException(org.springframework.http.MediaType.TEXT_PLAIN,
                java.util.List.of(org.springframework.http.MediaType.APPLICATION_JSON)));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.UNSUPPORTED_MEDIA_TYPE);
    }

    @Test
    void mediaTypeNotAcceptableIsNotAcceptable() {
        var response = handler.handleMediaTypeNotAcceptable(new HttpMediaTypeNotAcceptableException("no match"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_ACCEPTABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.NOT_ACCEPTABLE);
    }

    @Test
    void missingSiteForeignKeyIsNotFound() {
        var response = handler.handleDataIntegrity(new DataIntegrityViolationException(
            "could not execute",
            new RuntimeException("FOREIGN KEY(site_id) REFERENCES public.site(id)")));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.NOT_FOUND);
    }

    @Test
    void oversizedUploadIsPayloadTooLarge() {
        var response = handler.handleMaxUpload(new org.springframework.web.multipart.MaxUploadSizeExceededException(10_485_760));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().code()).isEqualTo(ErrorCodes.MEDIA_FILE_TOO_LARGE);
    }

    /**
     * OBS-MS-04：携带稳定消息键的业务异常按**键 + 参数**本地化，语序与空格由目标语言文案
     * 决定，不再落到「中文原文即键」的逐段重组（后者会把运行期拼装句拼成
     * {@code "PREVIEWenvironment Knoxendpoint"}）。
     */
    @Test
    void keyedBusinessMessageIsLocalizedByKeyAndArgsNotBySegmentedText() {
        var source = new org.springframework.context.support.StaticMessageSource();
        source.addMessage("error.media.env.not.configured", Locale.ROOT,
            "Site has not configured a Knox endpoint for environment {0}");
        var keyedHandler = new ApiExceptionHandler(source);

        LocaleContextHolder.setLocale(Locale.ROOT);
        try {
            var response = keyedHandler.handleBusiness(BusinessException.keyed(
                ErrorCodes.MEDIA_ENV_NOT_CONFIGURED, "站点尚未配置 PREVIEW 环境的 Knox 端点",
                "error.media.env.not.configured", "PREVIEW"));

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(response.getBody()).isNotNull();
            assertThat(response.getBody().code()).isEqualTo(ErrorCodes.MEDIA_ENV_NOT_CONFIGURED);
            assertThat(response.getBody().message())
                .isEqualTo("Site has not configured a Knox endpoint for environment PREVIEW");
        } finally {
            LocaleContextHolder.resetLocaleContext();
        }
    }

    /** 未携带稳定键的业务异常仍走「中文原文即键」（此处目录为空，原文原样返回）。 */
    @Test
    void unkeyedBusinessMessageStillGoesThroughSourceTextTranslation() {
        var response = handler.handleBusiness(new BusinessException(ErrorCodes.CONFLICT, "数据约束冲突"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().message()).isEqualTo("数据约束冲突");
    }
}
