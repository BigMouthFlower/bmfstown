package com.bmf.bmfstown.command;

import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.profile.ResidentProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
        dispatcher.register(
                Commands.literal("bmfstown")
                        .requires(source -> source.hasPermission(2))

                        // ---- /bmfstown hunger <目标> <0-20> ----
                        .then(Commands.literal("hunger")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("value", IntegerArgumentType.integer(0, ResidentProfile.MAX_HUNGER))
                                                .executes(ctx -> setHunger(
                                                        ctx,
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        IntegerArgumentType.getInteger(ctx, "value"))))))

                        // ---- /bmfstown mood <目标> <0-100> ----
                        .then(Commands.literal("mood")
                                .then(Commands.argument("targets", EntityArgument.entities())
                                        .then(Commands.argument("value", IntegerArgumentType.integer(0, ResidentProfile.MAX_MOOD))
                                                .executes(ctx -> setMood(
                                                        ctx,
                                                        EntityArgument.getEntities(ctx, "targets"),
                                                        IntegerArgumentType.getInteger(ctx, "value")))))));
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
}
