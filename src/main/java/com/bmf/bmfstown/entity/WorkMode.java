package com.bmf.bmfstown.entity;

/**
 * 战士的工作模式。
 *
 * <h2>两种模式互斥</h2>
 * 由 {@link Resident#setWorkMode} 保证：设置其中一种时，另一种（以及旧岗哨）
 * 一并清除。UI 上切一个，另一个自动关——服务端是唯一权威。
 *
 * <table border="1">
 *   <tr><th>模式</th><th>锚点</th><th>允许出领地</th><th>吃饭/睡觉</th></tr>
 *   <tr><td>{@link #FOLLOW}</td><td>指派的玩家</td><td>允许</td><td>暂停（去饮食区/回家睡）</td></tr>
 *   <tr><td>{@link #GUARD_POST}</td><td>岗哨方块</td><td>不允许</td><td>照常</td></tr>
 * </table>
 */
public enum WorkMode {

    /** 无工作：日常溜达、吃饭、睡觉。 */
    NONE,

    /** 跟随指派他的玩家，可离开领地，暂停饮食区/住宅相关的行动。 */
    FOLLOW,

    /** 在岗哨方块周围巡逻，只攻击进入警戒半径的敌人。 */
    GUARD_POST
}
