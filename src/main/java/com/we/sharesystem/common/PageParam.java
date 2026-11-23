package com.we.sharesystem.common;

/**
 * 分页参数归一化。
 *
 * 背景：不是所有列表都交给 MyBatis-Plus 分页（照片墙、通知、我点赞过等都是先取全量再内存切分），
 * 这些地方把 page 直接代进 (page-1)*size，一旦前端传 page=0 或负数就会算出负的 from，
 * subList 抛 IndexOutOfBounds、SQL 报 offset 非法，用户看到的是 500「系统繁忙」。
 * 统一从这里取值，顺手把 size 也压到上限，避免 ?size=100000 把库拖死。
 */
public final class PageParam {

    /** 单页上限，与 MybatisPlusConfig 的 maxLimit 保持一致 */
    public static final int MAX_SIZE = 50;
    /** 页码上限，防止深翻页与 (page-1)*size 溢出 */
    public static final int MAX_PAGE = 1000;

    private PageParam() {
    }

    public static int page(Integer page) {
        if (page == null || page < 1) {
            return 1;
        }
        return Math.min(page, MAX_PAGE);
    }

    public static int size(Integer size, int defaultSize) {
        if (size == null || size < 1) {
            return defaultSize;
        }
        return Math.min(size, MAX_SIZE);
    }

    /** limit 型参数（例如「每个好友取几张」），钳制到 [min, max] */
    public static int limit(Integer value, int defaultLimit, int min, int max) {
        if (value == null) {
            return defaultLimit;
        }
        return Math.max(min, Math.min(value, max));
    }
}
