package com.fruit.shop.support;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 全局异常处理：把参数校验失败 / 业务异常统一转换成 {@link ApiResult}。
 *
 * <p>HTTP 状态码与 body 里的 code 保持一致：
 * <ul>
 *   <li>400：参数校验失败（字段级 message 拼成一句话）或业务规则拒绝；</li>
 *   <li>404：目标对象不存在（例如 suggestionId 查不到）；</li>
 *   <li>500：未预期异常，body 里只给一句人话，细节写日志，不把堆栈抛给前端。</li>
 * </ul>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：message 已经是人话，直接透出。 */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResult<Void>> handleBusiness(BusinessException e) {
        int code = e.getCode() <= 0 ? ApiResult.CODE_BAD_REQUEST : e.getCode();
        log.warn("[业务异常] code={} message={}", code, e.getMessage());
        return ResponseEntity.status(code).body(ApiResult.fail(code, e.getMessage()));
    }

    /** @Valid @RequestBody 校验失败。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResult<Void>> handleBodyInvalid(MethodArgumentNotValidException e) {
        return badRequest(collect(e.getBindingResult().getFieldErrors(), e.getBindingResult().getGlobalErrors()));
    }

    /** 表单/查询参数绑定校验失败。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResult<Void>> handleBind(BindException e) {
        return badRequest(collect(e.getBindingResult().getFieldErrors(), e.getBindingResult().getGlobalErrors()));
    }

    /** @Validated 写在 Controller 上时，@RequestParam 上的约束失败。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResult<Void>> handleConstraint(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .map(this::describe)
                .collect(Collectors.joining("；"));
        return badRequest("参数校验失败：" + detail);
    }

    /** 缺少必填查询参数。 */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResult<Void>> handleMissingParam(MissingServletRequestParameterException e) {
        return badRequest("参数校验失败：缺少必填参数 " + e.getParameterName());
    }

    /** 参数类型不对，例如把 date 传成 2024/13/45。 */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResult<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return badRequest("参数校验失败：" + e.getName() + " 的值 '" + e.getValue() + "' 格式不正确");
    }

    /** 请求体不是合法 JSON 或日期格式不对。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResult<Void>> handleUnreadable(HttpMessageNotReadableException e) {
        String hint = e.getMostSpecificCause() == null ? "" : e.getMostSpecificCause().getMessage();
        return badRequest("请求体解析失败，请检查 JSON 与日期格式（日期 yyyy-MM-dd，时间 yyyy-MM-dd HH:mm:ss）"
                + (hint == null || hint.isBlank() ? "" : "：" + hint));
    }

    /** 路径写错：给 404 + 提示照着契约清单核对，而不是笼统的 500。 */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResult<Void>> handleNoResource(NoResourceFoundException e) {
        String path = e.getResourcePath() == null ? "" : e.getResourcePath();
        log.warn("[接口不存在] {}", path);
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResult.fail(ApiResult.CODE_NOT_FOUND, "接口不存在：/" + path + "，请对照 docs/01 第 5 节的接口清单"));
    }

    /** 方法用错（例如对 /api/batches 用了 GET 之外的写法）。 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiResult<Void>> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        String supported = e.getSupportedHttpMethods() == null ? "" : e.getSupportedHttpMethods().toString();
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(ApiResult.fail(ApiResult.CODE_METHOD_NOT_ALLOWED,
                        "请求方法不支持：" + e.getMethod() + "，该接口支持 " + supported));
    }

    /** 兜底：不把堆栈交给前端，但日志留全。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResult<Void>> handleUnexpected(Exception e) {
        log.error("[未预期异常] {}", e.getMessage(), e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResult.fail(ApiResult.CODE_SERVER_ERROR, "服务器内部错误，请查看后端日志：" + e.getClass().getSimpleName()));
    }

    private ResponseEntity<ApiResult<Void>> badRequest(String message) {
        log.warn("[参数/业务校验] {}", message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResult.fail(ApiResult.CODE_BAD_REQUEST, message));
    }

    private String collect(List<FieldError> fieldErrors, List<ObjectError> globalErrors) {
        List<String> parts = new ArrayList<>();
        for (FieldError fe : fieldErrors) {
            String msg = fe.getDefaultMessage();
            parts.add(msg == null || msg.isBlank() ? fe.getField() + " 不合法" : msg);
        }
        for (ObjectError ge : globalErrors) {
            parts.add(ge.getDefaultMessage() == null ? "请求不合法" : ge.getDefaultMessage());
        }
        if (parts.isEmpty()) {
            parts.add("请求参数不合法");
        }
        return "参数校验失败：" + String.join("；", parts);
    }

    private String describe(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
        String msg = violation.getMessage();
        return path.isBlank() ? msg : msg + "（" + path + "）";
    }
}
