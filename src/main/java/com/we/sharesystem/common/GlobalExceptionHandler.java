package com.we.sharesystem.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。
 * 业务异常回 400 + 原始 message（给前端提示）；
 * 其余异常一律记录完整堆栈后再回 500，避免线上出问题查不到原因。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public Result<Void> handleBusiness(BusinessException e) {
        log.warn("业务异常：{}", e.getMessage());
        return Result.fail(400, e.getMessage());
    }

    /** 路径不存在（含静态资源）：回 404，别再伪装成 500「系统繁忙」 */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public Result<Void> handleNotFound(NoResourceFoundException e) {
        log.warn("资源不存在：{}", e.getResourcePath());
        return Result.fail(404, "接口或资源不存在");
    }

    /** 请求方法不对（例如对 /moments 发 PUT）：回 405 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public Result<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("请求方法不支持：{}", e.getMethod());
        return Result.fail(405, "请求方法不支持");
    }

    /* ---------- 参数类异常：一律回 400 + 人能看懂的原因 ----------
       这一组原先是缺失的：@Valid 校验失败会掉进最下面的 Exception 兜底，
       前端只能看到「系统繁忙」，看不到「内容不能为空」。 */

    /** @RequestBody / @RequestPart 上的 @Valid 校验失败 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public Result<Void> handleInvalidBody(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .filter(m -> m != null && !m.isBlank())
                .findFirst()
                .orElse("参数校验失败");
        log.warn("参数校验失败：{}", msg);
        return Result.fail(400, msg);
    }

    /** @Validated 作用在方法参数（如 @RequestParam）上的约束失败 */
    @ExceptionHandler(ConstraintViolationException.class)
    public Result<Void> handleConstraintViolation(ConstraintViolationException e) {
        String msg = e.getConstraintViolations().stream()
                .map(v -> v.getMessage())
                .findFirst()
                .orElse("参数不合法");
        log.warn("参数约束失败：{}", msg);
        return Result.fail(400, msg);
    }

    /** 必填查询参数缺失，例如漏传 targetId */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public Result<Void> handleMissingParam(MissingServletRequestParameterException e) {
        log.warn("缺少参数：{}", e.getParameterName());
        return Result.fail(400, "缺少必要参数：" + e.getParameterName());
    }

    /** 请求体不是合法 JSON 或字段类型不匹配 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public Result<Void> handleUnreadable(HttpMessageNotReadableException e) {
        log.warn("请求体解析失败：{}", e.getMessage());
        return Result.fail(400, "请求体格式错误，请检查提交的内容");
    }

    /** 路径变量 / 查询参数类型不匹配，例如 /moments/abc */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public Result<Void> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型错误：{}", e.getName());
        return Result.fail(400, "参数 " + e.getName() + " 的格式不正确");
    }

    /** 服务层主动抛出的参数问题（如点赞 targetType 非法），按 400 返回而非 500 */
    @ExceptionHandler(IllegalArgumentException.class)
    public Result<Void> handleIllegalArgument(IllegalArgumentException e) {
        log.warn("参数不合法：{}", e.getMessage());
        return Result.fail(400, e.getMessage() == null ? "参数不合法" : e.getMessage());
    }

    /**
     * 上传体积超限。
     * 不接这个异常会落到兜底的 Exception 分支，用户看到的是「系统繁忙」，
     * 而真实原因是自己选了 9 张手机原图 —— 提示与原因完全对不上，只能反复重试。
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public Result<Void> handleUploadTooLarge(MaxUploadSizeExceededException e) {
        log.warn("上传体积超限：{}", e.getMessage());
        return Result.fail(413, "图片太大了，请少选几张或压缩后再试");
    }

    @ExceptionHandler(Exception.class)
    public Result<Void> handleSystem(Exception e) {
        log.error("未捕获异常", e);
        return Result.fail(500, "系统繁忙，请稍后再试");
    }
}
