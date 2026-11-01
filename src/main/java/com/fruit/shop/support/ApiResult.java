package com.fruit.shop.support;

import lombok.Data;

/**
 * 统一响应体：{@code {"code":0,"message":"ok","data":{...}}}（见 docs/01-技术方案.md 第 5 节）。
 *
 * <p>约定：
 * <ul>
 *   <li>{@code code=0} 表示成功；非 0 表示失败，{@code message} 是可直接展示给店主的中文说明；</li>
 *   <li>业务异常与参数校验失败由 {@link GlobalExceptionHandler} 统一转换成 400/404，
 *       并且 HTTP 状态码与 body 里的 code 保持一致（curl / 浏览器 / axios 拦截器都能直接看出失败）；</li>
 *   <li>所有接口（含 {@code /api/health}）都返回本结构，不再返回裸 Map/List，前端只需一套解包逻辑。</li>
 * </ul>
 */
@Data
public class ApiResult<T> {

    public static final int CODE_OK = 0;
    public static final int CODE_BAD_REQUEST = 400;
    public static final int CODE_NOT_FOUND = 404;
    public static final int CODE_METHOD_NOT_ALLOWED = 405;
    public static final int CODE_CONFLICT = 409;
    public static final int CODE_SERVER_ERROR = 500;

    private int code;

    private String message;

    private T data;

    public static <T> ApiResult<T> ok(T data) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(CODE_OK);
        result.setMessage("ok");
        result.setData(data);
        return result;
    }

    public static <T> ApiResult<T> ok() {
        return ok(null);
    }

    public static <T> ApiResult<T> fail(int code, String message) {
        ApiResult<T> result = new ApiResult<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
