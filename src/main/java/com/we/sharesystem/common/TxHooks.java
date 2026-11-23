package com.we.sharesystem.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 事务钩子。
 *
 * 缓存失效必须在事务提交之后做，而不是在方法体里：
 * 如果在事务内先把缓存删了，提交前那一小段时间里并发请求会重新查库，
 * 把「旧数据」又写回缓存，于是脏数据被固定住一整个 TTL。
 */
@Slf4j
public final class TxHooks {

    private TxHooks() {
    }

    /**
     * 有事务则在提交后执行，无事务则立即执行。
     * 钩子里的异常只记日志，不能反过来影响已经成功的业务操作。
     */
    public static void afterCommit(Runnable action) {
        Runnable safe = () -> {
            try {
                action.run();
            } catch (Exception e) {
                log.warn("事务提交后回调执行失败（不影响业务结果）", e);
            }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    safe.run();
                }
            });
        } else {
            safe.run();
        }
    }
}
