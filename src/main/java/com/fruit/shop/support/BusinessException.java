package com.fruit.shop.support;

/**
 * 业务异常：{@code message} 必须是能直接给店主看的人话（不要堆栈、不要英文枚举），
 * 由 {@link GlobalExceptionHandler} 转成统一响应体。
 */
public class BusinessException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int code;

    public BusinessException(String message) {
        this(ApiResult.CODE_BAD_REQUEST, message);
    }

    public BusinessException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(ApiResult.CODE_BAD_REQUEST, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(ApiResult.CODE_NOT_FOUND, message);
    }
}
