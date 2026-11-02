package com.bmf.bmfstown.entity;

/**
 * 工作模式（战士/学者共用枚举）。
 *
 * <h2>模式互斥</h2>
 * 由 {@link Resident#setWorkMode} 保证：设置其中一种时，其他模式绑定的
 * 状态一并清除。UI 上切一个，另一个自动关——服务端是唯一权威。
 *
 * <table border="1">
 *   <tr><th>模式</th><th>归属</th><th>锚点</th><th>允许出领地</th><th>吃饭/睡觉</th></tr>
 *   <tr><td>{@link #NONE}</td><td>通用</td><td>—</td><td>—</td><td>—</td></tr>
 *   <tr><td>{@link #FOLLOW}</td><td>战士</td><td>指派的玩家</td><td>允许</td><td>吃饭去饮食区；<b>不睡</b>（贴身守夜）</td></tr>
 *   <tr><td>{@link #GUARD_POST}</td><td>战士</td><td>岗哨方块</td><td>不允许</td><td>吃饭去饮食区；<b>不睡</b>（守岗到天亮）</td></tr>
 *   <tr><td>{@link #WRITE}</td><td>学者</td><td>图书馆</td><td>不允许</td><td>照常吃饭睡觉（工作时段外回睡）</td></tr>
 *   <tr><td>{@link #COPY}</td><td>学者</td><td>图书馆</td><td>不允许</td><td>照常吃饭睡觉（工作时段外回睡）</td></tr>
 * </table>
 *
 * <p>⚠ 存档按 {@code ordinal()} 落盘——新值只能<b>追加在尾部</b>，
 * 中间插值会让老存档的模式错位。
 */
public enum WorkMode {

    /** 无工作：日常溜达、吃饭、睡觉。 */
    NONE,

    /** 跟随指派他的玩家，可离开领地，暂停饮食区/住宅相关的行动。 */
    FOLLOW,

    /** 在岗哨方块周围巡逻，只攻击进入警戒半径的敌人。 */
    GUARD_POST,

    /** 写书模式（学者，v0.10.3）：从奖池抽内容凭空写。 */
    WRITE,

    /** 抄书模式（学者，v0.10.3）：以背包第一本成书为底本抄副本。 */
    COPY
}
