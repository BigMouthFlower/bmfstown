package com.bmf.bmfstown.social;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 事件总线（v0.9.1 表达层地基）：镇上的广播站。
 *
 * <p>产生方（心情结算/感知扫描/行为钩子）只负责 {@link #dispatch}——
 * 不需要知道谁在听；订阅方（台词引擎/记忆采集/将来的图鉴、传闻）
 * 声明感兴趣的事件类型，消息自动送达。
 *
 * <p>纯服务端；单线程（服务器主线程）调用，无需并发保护。
 */
public final class TownEventBus {

    private TownEventBus() {
    }

    private static final List<Consumer<TownEvent>> SUBSCRIBERS = new ArrayList<>();

    /** 登记一个订阅者（收全部事件，自行按类型过滤——引擎/记忆仓本就是 switch 型）。 */
    public static void register(Consumer<TownEvent> subscriber) {
        SUBSCRIBERS.add(subscriber);
    }

    /** 喊一嗓子：把事件投递给所有订阅者。 */
    public static void dispatch(TownEvent event) {
        if (SUBSCRIBERS.isEmpty()) {
            return;
        }
        for (Consumer<TownEvent> subscriber : SUBSCRIBERS) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                // 单个订阅者出错不连坐其他订阅者，也不炸产生事件的调用链
                com.bmf.bmfstown.BmfTown.LOGGER.error("[BmfTown] 事件订阅者处理 {} 出错",
                        event.type, e);
            }
        }
    }
}
