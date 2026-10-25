package com.bmf.bmfstown.command;

import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.ResidentProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

import java.util.Collection;

/**
 * 调试命令：{@code /bmfstown hunger <目标> <值>} 与 {@code /bmfstown mood ...}。
 *
 * <h2>为什么必须做成命令，而不是用 {@code /data}</h2>
 * 饥饿值存在 {@link Resident#getProfile()} 里，那是
 * {@code SynchedEntityData}（同步字段），<b>不是 NBT</b>。
 * {@code /data get entity} 能看到它只是因为 {@code addAdditionalSaveData}
 * 临时把档案写进了 NBT 快照——那是一次性的副本，改它不会回写内存。
 * 想改真正的值，只能走 Java 代码：{@code setProfile(...)}。
 *
 * <h2>为什么不做成"改完就同步一遍"的 hack</h2>
 * {@code SynchedEntityData.set} 内部就比较新旧值决定是否发包，
 * 所以直接 {@code setProfile} 客户端立刻就能看到，不需要额外发包。
 */
public final class ResidentCommands {

    private ResidentCommands() {
    }

    /**
     * 注册入口。由 {@code RegisterCommandsEvent} 调用（见 {@code BmfTown}）。
     */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // 每条子命令独立 then() 再统一注册，避免一条超长链式调用里
        // 数不清的右括号（之前连续两次"需要';'"就是这么来的）。
        var root = Commands.literal("bmfstown")
                .requires(source -> source.hasPermission(2));

        // ---- /bmfstown hunger <目标> <0-20> ----
        root.then(Commands.literal("hunger")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, ResidentProfile.MAX_HUNGER))
                                .executes(ctx -> setHunger(
                                        ctx,
                                        EntityArgument.getEntities(ctx, "targets"),
                                        IntegerArgumentType.getInteger(ctx, "value"))))));

        // ---- /bmfstown mood <目标> <0-100> ----
        root.then(Commands.literal("mood")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, ResidentProfile.MAX_MOOD))
                                .executes(ctx -> setMood(
                                        ctx,
                                        EntityArgument.getEntities(ctx, "targets"),
                                        IntegerArgumentType.getInteger(ctx, "value"))))));

        // ---- /bmfstown skillxp <目标> <0-7500> ----
        // 设置目标当前职业主技能的累计经验（调试用，只动主技能条）
        root.then(Commands.literal("skillxp")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("value", IntegerArgumentType.integer(
                                        0, com.bmf.bmfstown.entity.profile.SkillTiers
                                                .XP_THRESHOLDS[com.bmf.bmfstown.entity.profile.SkillTiers.MAX_TIER]))
                                .executes(ctx -> setSkillXp(
                                        ctx,
                                        EntityArgument.getEntities(ctx, "targets"),
                                        IntegerArgumentType.getInteger(ctx, "value"))))));

        // ---- /bmfstown reroll <目标> ----
        // 重摇目标镇民的 v0.8 偏好与性格（测试专用：建材/动物/颜色/音乐各 1/2、
        // 天气 1/3、生物 1/4、性格 7 选 1，手刷几十个镇民凑不齐组合）。
        // 抽取结果落 NBT，客户端重开面板即可看到（想法/偏好的同步时机是打开面板）。
        root.then(Commands.literal("reroll")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .executes(ctx -> rerollPrefs(
                                ctx,
                                EntityArgument.getEntities(ctx, "targets")))));

        // ---- /bmfstown trait <目标> <特性|random> ----
        // 直接指定目标镇民的特性（调试用；特性 14 选 1，靠 reroll 抽太慢）。
        // "random" = 等概率重抽；id 见 lang 里 trait.bmfstown.*（如 walrus、flighty）。
        root.then(Commands.literal("trait")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("trait", StringArgumentType.word())
                                .executes(ctx -> setTrait(
                                        ctx,
                                        EntityArgument.getEntities(ctx, "targets"),
                                        StringArgumentType.getString(ctx, "trait"))))));

        dispatcher.register(root);
    }

    /** 直接指定特性（"random" = 等概率重抽）。生效即落 NBT，重开面板可见。 */
    private static int setTrait(CommandContext<CommandSourceStack> ctx,
                                Collection<? extends Entity> targets,
                                String traitArg) {
        int changed = 0;
        for (Entity entity : targets) {
            if (!(entity instanceof Resident resident)) {
                continue;
            }
            com.bmf.bmfstown.entity.profile.TraitType trait = "random".equalsIgnoreCase(traitArg)
                    ? com.bmf.bmfstown.entity.profile.TraitType.random(resident.getRandom())
                    : com.bmf.bmfstown.entity.profile.TraitType.byId(traitArg.toLowerCase());
            if (trait == null) {
                ctx.getSource().sendFailure(Component.literal(
                        "未知特性: " + traitArg + "（可用: random 或 "
                                + String.join("/",
                                java.util.Arrays.stream(com.bmf.bmfstown.entity.profile.TraitType.values())
                                        .map(com.bmf.bmfstown.entity.profile.TraitType::id)
                                        .toArray(String[]::new)) + "）"));
                return 0;
            }
            resident.getMoodState().setTraitForCommand(trait);
            changed++;
            ctx.getSource().sendSuccess(() -> Component.literal(
                    resident.getName().getString() + " 的特性 → "
                            + trait.displayName() + "（重开面板查看）"), false);
        }
        if (changed == 0) {
            ctx.getSource().sendFailure(Component.translatable("msg.bmfstown.command.no_resident"));
        }
        return changed;
    }


    /** 设置饥饿值。 */
    private static int setHunger(CommandContext<CommandSourceStack> ctx,
                                 Collection<? extends Entity> targets,
                                 int value) {
        int changed = 0;
        for (Entity entity : targets) {
            if (!(entity instanceof Resident resident)) {
                continue;
            }
            resident.setProfile(resident.getProfile().withHunger(value));
            changed++;
            // 反馈：告诉执行者改成了什么（单人游戏里就是你自己看到这条）
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "msg.bmfstown.command.hunger_set",
                    resident.getName().getString(), value), false);
        }
        if (changed == 0) {
            ctx.getSource().sendFailure(Component.translatable("msg.bmfstown.command.no_resident"));
        }
        return changed;
    }

    /** 设置心情值。 */
    private static int setMood(CommandContext<CommandSourceStack> ctx,
                               Collection<? extends Entity> targets,
                               int value) {
        int changed = 0;
        for (Entity entity : targets) {
            if (!(entity instanceof Resident resident)) {
                continue;
            }
            resident.setProfile(resident.getProfile().withMood(value));
            changed++;
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "msg.bmfstown.command.mood_set",
                    resident.getName().getString(), value), false);
        }
        if (changed == 0) {
            ctx.getSource().sendFailure(Component.translatable("msg.bmfstown.command.no_resident"));
        }
        return changed;
    }
    /**
     * 设置目标当前职业主技能的累计经验。
     *
     * <p>走 {@code withSkillXp}（数据层已有该方法，注释里就写着"命令用"）。
     * 无职业的镇民直接跳过并提示——没有主技能条可写。
     */
    private static int setSkillXp(CommandContext<CommandSourceStack> ctx,
                                  Collection<? extends Entity> targets,
                                  int value) {
        int changed = 0;
        for (Entity entity : targets) {
            if (!(entity instanceof Resident resident)) {
                continue;
            }
            var profession = resident.getProfile().profession();
            if (profession == null || !profession.isReal() || profession.mainSkill() == null) {
                ctx.getSource().sendFailure(Component.translatable(
                        "msg.bmfstown.command.no_profession", resident.getName().getString()));
                continue;
            }
            var skill = profession.mainSkill();
            resident.setProfile(resident.getProfile().withSkillXp(skill, value));
            changed++;
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "msg.bmfstown.command.skillxp_set",
                    resident.getName().getString(),
                    Component.translatable(skill.displayName()),
                    value,
                    com.bmf.bmfstown.entity.profile.SkillTiers.levelOf(value)), false);
        }
        if (changed == 0) {
            ctx.getSource().sendFailure(Component.translatable("msg.bmfstown.command.no_resident"));
        }
        return changed;
    }

    /**
     * 重摇目标镇民的 v0.8 偏好与性格（{@code /bmfstown reroll}）。
     *
     * <p>测试专用入口——v0.8 偏好是"建材×2 / 动物 / 颜色 / 音乐各 1/2 概率 +
     * 天气 1/3 + 生物 1/4 + 性格 7 选 1"的乘法分布，想凑齐一个
     * "贪吃 + 胆小 + 讨厌僵尸"的镇民靠刷怪蛋要刷很久，reroll 一秒搞定。
     *
     * <p>只走 MoodState 的懒生成路径，不动档案其他字段（饥饿/心情/职业不动）。
     */
    private static int rerollPrefs(CommandContext<CommandSourceStack> ctx,
                                   Collection<? extends Entity> targets) {
        int changed = 0;
        for (Entity entity : targets) {
            if (!(entity instanceof Resident resident)) {
                continue;
            }
            if (!(resident.level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) {
                continue; // 理论上命令只在服务端执行，防御姿态
            }
            resident.getMoodState().reroll(resident, serverLevel);
            changed++;
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "msg.bmfstown.command.reroll_done",
                    resident.getName().getString()), false);
        }
        if (changed == 0) {
            ctx.getSource().sendFailure(Component.translatable("msg.bmfstown.command.no_resident"));
        }
        return changed;
    }
}
