package com.bmf.bmfstown.command;

import com.bmf.bmfstown.entity.Resident;
import com.bmf.bmfstown.entity.domain.Status;
import com.mojang.brigadier.CommandDispatcher;
import com.bmf.bmfstown.book.BookContent;
import net.minecraft.world.item.ItemStack;
import com.bmf.bmfstown.town.Town;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, Status.MAX_HUNGER))
                                .executes(ctx -> setHunger(
                                        ctx,
                                        EntityArgument.getEntities(ctx, "targets"),
                                        IntegerArgumentType.getInteger(ctx, "value"))))));

        // ---- /bmfstown bookprogress <目标> <0-100> ----
        // 学者调试：直接设置写作中书的研究进度（百分比）。走 gameTime 的
        // 在岗累计用 /time 快进不了——测试全靠这个命令。
        root.then(Commands.literal("bookprogress")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("percent", IntegerArgumentType.integer(0, 100))
                                .executes(ctx -> setBookProgress(
                                        ctx,
                                        EntityArgument.getEntities(ctx, "targets"),
                                        IntegerArgumentType.getInteger(ctx, "percent"))))));
        // ---- /bmfstown booklearn <目标> <书id> ----
        // 学者调试：直接把一本登记的特殊书标记为"已学并装备"——读书系统
        // 重设计挂起期间，习得效果的测试通路。
        root.then(Commands.literal("booklearn")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("book", StringArgumentType.string())
                                .executes(ctx -> learnBook(
                                        ctx,
                                        EntityArgument.getEntities(ctx, "targets"),
                                        StringArgumentType.getString(ctx, "book"))))));

        // ---- /bmfstown mood <目标> <0-100> ----
        root.then(Commands.literal("mood")
                .then(Commands.argument("targets", EntityArgument.entities())
                        .then(Commands.argument("value", IntegerArgumentType.integer(0, Status.MAX_MOOD))
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

        // ---- /bmfstown say <文字> ----
        // 台词调试命令：让最近的镇民头顶显示这句原文（验证展示链路用）。
        root.then(Commands.literal("say")
                .then(Commands.argument("text", StringArgumentType.greedyString())
                        .executes(ctx -> sayNearest(
                                ctx, StringArgumentType.getString(ctx, "text")))));

        // ---- /bmfstown date ----
        // 历法调试命令：报当前镇民历日期（年/月/日/月相）。
        root.then(Commands.literal("date")
                .executes(ResidentCommands::showDate));

        // ---- /bmfstown timespeed <倍率> ----
        // 昼夜流速：0=冻结，1=原版，>1 加速，<1 减速——睡觉跳夜任何倍率下都可用。
        root.then(Commands.literal("timespeed")
                .then(Commands.argument("multiplier",
                                DoubleArgumentType.doubleArg(0.0,
                                        com.bmf.bmfstown.calendar.TownCalendar.MAX_SPEED))
                        .executes(ctx -> {
                            net.minecraft.server.level.ServerLevel level =
                                    ctx.getSource().getLevel();
                            double v = DoubleArgumentType.getDouble(ctx, "multiplier");
                            com.bmf.bmfstown.calendar.TownCalendar.setSpeed(level, v);
                            double shown = com.bmf.bmfstown.calendar.TownCalendar.speedOf(level);
                            ctx.getSource().sendSuccess(() -> Component.translatable(
                                    "msg.bmfstown.timespeed.set",
                                    String.format(java.util.Locale.ROOT, "%.2f", shown)), false);
                            return 1;
                        })));

        // ---- /bmfstown unlock|lock|unlocks：解锁系统调试 ----
        // 访问空间 = 城镇（用户定稿）。<城镇> 可省略 → 按玩家站立位置解析；
        // 显式给出 → 接受城镇名（忽略大小写，须唯一）或 UUID。
        root.then(Commands.literal("unlock")
                .then(Commands.argument("id", StringArgumentType.string())
                        .executes(ctx -> doUnlock(ctx.getSource(), null,
                                StringArgumentType.getString(ctx, "id"))))
                .then(Commands.argument("城镇", StringArgumentType.string())
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(ctx -> doUnlock(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "城镇"),
                                        StringArgumentType.getString(ctx, "id"))))));
        root.then(Commands.literal("lock")
                .then(Commands.argument("id", StringArgumentType.string())
                        .executes(ctx -> doLock(ctx.getSource(), null,
                                StringArgumentType.getString(ctx, "id"))))
                .then(Commands.argument("城镇", StringArgumentType.string())
                        .then(Commands.argument("id", StringArgumentType.string())
                                .executes(ctx -> doLock(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "城镇"),
                                        StringArgumentType.getString(ctx, "id"))))));
        root.then(Commands.literal("unlocks")
                .executes(ctx -> doUnlocks(ctx.getSource(), null))
                .then(Commands.argument("城镇", StringArgumentType.string())
                        .executes(ctx -> doUnlocks(ctx.getSource(),
                                StringArgumentType.getString(ctx, "城镇")))));
        // ---- /bmfstown town add|remove|list|leave|disband ----
        // 领地权限（v0.11.1）：成员白名单。城镇按**站位**解析（沿用 unlocks
        // 的惯例）；add 走 GameProfileArgument（离线玩家名也能解析）。
        root.then(Commands.literal("town")
                .then(Commands.literal("add")
                        .then(Commands.argument("玩家", net.minecraft.commands.arguments.GameProfileArgument.gameProfile())
                                .executes(ctx -> townAdd(ctx.getSource(),
                                        net.minecraft.commands.arguments.GameProfileArgument
                                                .getGameProfiles(ctx, "玩家")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("名字", StringArgumentType.word())
                                .executes(ctx -> townRemove(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "名字")))))
                .then(Commands.literal("list")
                        .executes(ctx -> townList(ctx.getSource())))
                .then(Commands.literal("leave")
                        .executes(ctx -> townLeave(ctx.getSource())))
                .then(Commands.literal("disband")
                        .executes(ctx -> townDisband(ctx.getSource()))));

        // ---- /bmfstown snowfall <秒> [半径] ----
        // 魔法降雪调试命令：以玩家为中心限时局部下雪（任何群系）。
        root.then(Commands.literal("snowfall")
                .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 600))
                        .executes(ctx -> startSnowfall(
                                ctx, IntegerArgumentType.getInteger(ctx, "seconds"), 32))
                        .then(Commands.argument("radius", IntegerArgumentType.integer(4, 128))
                                .executes(ctx -> startSnowfall(
                                        ctx, IntegerArgumentType.getInteger(ctx, "seconds"),
                                        IntegerArgumentType.getInteger(ctx, "radius"))))));

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
                    ? com.bmf.bmfstown.entity.profile.TraitType.weightedRandom(resident.getRandom())
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
            resident.getTraitState().setTraitForCommand(trait);
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


    /**
     * 设置学者写作/抄写进度（百分比）。按当前工作模式取槽（写书 → 写作槽，
     * 抄书 → 抄写槽；当前槽空则看另一个槽）——进度直接写进书的组件 NBT
     * （继承/掉落语义不变）。
     */
    private static int setBookProgress(CommandContext<CommandSourceStack> ctx,
                                       Collection<? extends Entity> targets, int percent) {
        int changed = 0;
        for (Entity entity : targets) {
            if (!(entity instanceof Resident resident)) {
                continue;
            }
            boolean copying = resident.getWorkMode() == com.bmf.bmfstown.entity.WorkMode.COPY;
            // 记住从哪个槽读的——写回必须回同一个槽！（曾因无脑写回写作槽，
            // 把抄写进度 100 塞进写作槽 + 抄写槽原地 100，双槽卡死疯狂复制）
            boolean slotIsCopy = copying;
            ItemStack wip = copying ? resident.getCopyingBook() : resident.getWritingBook();
            if (wip.isEmpty()) {
                // 当前模式槽空：看另一个槽（别让"写了半本切到抄书"这种状态卡住命令）
                slotIsCopy = !copying;
                wip = slotIsCopy ? resident.getCopyingBook() : resident.getWritingBook();
            }
            BookContent content = wip.get(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get());
            if (content == null) {
                continue; // 这位学者手里没有写作/抄写中的书
            }
            long progress = Math.round(content.target() * percent / 100.0);
            wip.set(com.bmf.bmfstown.ModDataComponents.BOOK_CONTENT.get(),
                    new BookContent(content.townId(), content.authors(), content.draft(),
                            progress, content.target(), content.checkpointPassed(),
                            content.annotated()));
            if (slotIsCopy) {
                resident.setCopyingBook(wip);
            } else {
                resident.setWritingBook(wip);
            }
            ctx.getSource().sendSuccess(() -> Component.literal(
                    resident.getName().getString() + " 写作进度 → " + percent + "%"), false);
            changed++;
        }
        if (changed == 0) {
            ctx.getSource().sendFailure(Component.literal(
                    "目标身上没有写作/抄写中的书（先给学者配好图书馆和原料）"));
        }
        return changed;
    }

    /**
     * 直接学会一本特殊书（调试）：标记已学 + 装备——装备已学即效果生效。
     * 书 id 必须在特殊书登记表里（如 wulun / sunzi / shanjing）。
     */
    private static int learnBook(CommandContext<CommandSourceStack> ctx,
                                 Collection<? extends Entity> targets, String bookId) {
        var book = com.bmf.bmfstown.book.SpecialBookRegistry.byId(bookId);
        if (book == null) {
            ctx.getSource().sendFailure(Component.literal(
                    "登记表里没有这本书：" + bookId + "（可用：" +
                    String.join("、", com.bmf.bmfstown.book.SpecialBookRegistry.all().keySet()) + "）"));
            return 0;
        }
        int changed = 0;
        for (Entity entity : targets) {
            if (!(entity instanceof Resident resident)) {
                continue;
            }
            resident.getKnowledge().forceLearn(bookId);
            resident.syncClient();
            if (resident.level() instanceof net.minecraft.server.level.ServerLevel sl) {
                com.bmf.bmfstown.book.BookBroadcast.discovered(
                        sl, resident.getIdentity().townId(), bookId);
                com.bmf.bmfstown.book.BookBroadcast.learned(
                        sl, resident.getName().getString(), bookId);
            }
            ctx.getSource().sendSuccess(() -> Component.literal(
                    resident.getName().getString() + " 已学会并装备《" + bookId + "》"), false);
            changed++;
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
            resident.getStatus().setHunger(value);
            resident.syncClient();
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
            resident.getStatus().setMood(value);
            resident.syncClient();
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
            var profession = resident.getIdentity().profession();
            if (profession == null || !profession.isReal() || profession.mainSkill() == null) {
                ctx.getSource().sendFailure(Component.translatable(
                        "msg.bmfstown.command.no_profession", resident.getName().getString()));
                continue;
            }
            var skill = profession.mainSkill();
            resident.getGrowth().setSkillXp(skill, value);
            resident.syncClient();
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
    /** 台词调试：最近的镇民说这句原文。 */
    private static int sayNearest(CommandContext<CommandSourceStack> ctx, String text)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        com.bmf.bmfstown.entity.Resident nearest = null;
        double best = Double.MAX_VALUE;
        for (com.bmf.bmfstown.entity.Resident resident : player.serverLevel()
                .getEntitiesOfClass(com.bmf.bmfstown.entity.Resident.class,
                        player.getBoundingBox().inflate(32))) {
            double d = resident.distanceToSqr(player);
            if (d < best) {
                best = d;
                nearest = resident;
            }
        }
        if (nearest == null) {
            ctx.getSource().sendFailure(Component.translatable(
                    "msg.bmfstown.command.no_resident"));
            return 0;
        }
        com.bmf.bmfstown.entity.Resident said = nearest;
        com.bmf.bmfstown.social.DialogEngine.sayRaw(said, text);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "msg.bmfstown.say.done", said.getName().getString(), text), false);
        return 1;
    }


    /**
     * 城镇参数解析：显式给出 → 城镇名（忽略大小写，须唯一）或 UUID；
     * 传 null → 按执行者站立位置解析（站在哪个镇就是哪个镇）。
     * 失败时已向执行者发送原因，返回 null。
     */
    // ==========================================================
    //  领地权限：成员白名单命令（v0.11.1）
    // ==========================================================

    /** 解析执行者脚下的城镇 + 校验成员身份；失败已发提示，返回 null。 */
    private static com.bmf.bmfstown.town.Town memberTown(CommandSourceStack source) {
        com.bmf.bmfstown.town.Town town = resolveTown(source, null);
        if (town == null) {
            return null;
        }
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("该命令需要玩家执行"));
            return null;
        }
        // 空成员表 = 未启用保护的老城镇：任何人可执行 add（首个 add 启用保护）
        if (!town.isMember(player.getUUID())) {
            source.sendFailure(Component.translatable(
                    "msg.bmfstown.town.not_member", town.name()));
            return null;
        }
        return town;
    }

    private static int townAdd(CommandSourceStack source,
            java.util.Collection<com.mojang.authlib.GameProfile> profiles) {
        com.bmf.bmfstown.town.Town town = memberTown(source);
        if (town == null) {
            return 0;
        }
        for (com.mojang.authlib.GameProfile profile : profiles) {
            com.bmf.bmfstown.town.TownSaveData.get(source.getLevel()).addMember(
                    town, profile.getId(), profile.getName());
            source.sendSuccess(() -> Component.translatable(
                    "msg.bmfstown.town.add_ok", profile.getName(), town.name()), false);
        }
        return profiles.size();
    }

    private static int townRemove(CommandSourceStack source, String name) {
        com.bmf.bmfstown.town.Town town = memberTown(source);
        if (town == null) {
            return 0;
        }
        String removed = com.bmf.bmfstown.town.TownSaveData.get(source.getLevel()).removeMemberByName(town, name);
        if (removed == null) {
            source.sendFailure(Component.translatable(
                    "msg.bmfstown.town.remove_none", town.name(), name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "msg.bmfstown.town.remove_ok", removed, town.name()), false);
        return 1;
    }

    private static int townList(CommandSourceStack source) {
        com.bmf.bmfstown.town.Town town = memberTown(source);
        if (town == null) {
            return 0;
        }
        String names = String.join("、", town.members().values());
        source.sendSuccess(() -> Component.translatable(
                "msg.bmfstown.town.list_head", town.name(),
                town.members().size(), names.isEmpty() ? "（无）" : names), false);
        return town.members().size();
    }

    private static int townLeave(CommandSourceStack source) {
        com.bmf.bmfstown.town.Town town = resolveTown(source, null);
        if (town == null) {
            return 0;
        }
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("该命令需要玩家执行"));
            return 0;
        }
        String removed = com.bmf.bmfstown.town.TownSaveData.get(source.getLevel()).removeMember(
                town, player.getUUID());
        if (removed == null) {
            source.sendFailure(Component.translatable(
                    "msg.bmfstown.town.not_member", town.name()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable(
                "msg.bmfstown.town.leave_ok", town.name()), false);
        return 1;
    }

    private static int townDisband(CommandSourceStack source) {
        com.bmf.bmfstown.town.Town town = resolveTown(source, null);
        if (town == null) {
            return 0;
        }
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = source.getPlayerOrException();
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            source.sendFailure(Component.literal("该命令需要玩家执行"));
            return 0;
        }
        if (town.ownerId() == null) {
            // 老城镇没登记创建者：解散权无从判定，引导走手册删除（原有能力不变）
            source.sendFailure(Component.translatable("msg.bmfstown.town.disband_legacy"));
            return 0;
        }
        if (!town.ownerId().equals(player.getUUID())) {
            source.sendFailure(Component.translatable(
                    "msg.bmfstown.town.disband_denied", town.name()));
            return 0;
        }
        boolean ok = com.bmf.bmfstown.town.TownSaveData.get(source.getLevel()).remove(
                town.id().toString(), null);
        if (ok) {
            com.bmf.bmfstown.network.ModNetworking.sendHandbookSync(player, false);
            source.sendSuccess(() -> Component.translatable(
                    "msg.bmfstown.town.disband_ok", town.name()), true);
        }
        return ok ? 1 : 0;
    }

    private static com.bmf.bmfstown.town.Town resolveTown(
            net.minecraft.commands.CommandSourceStack source,
            @javax.annotation.Nullable String townText) {
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        var data = com.bmf.bmfstown.town.TownSaveData.get(level);
        if (townText == null) {
            net.minecraft.server.level.ServerPlayer player;
            try {
                player = source.getPlayerOrException();
            } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
                source.sendFailure(Component.literal("该命令需要玩家执行（或显式指定 <城镇>）"));
                return null;
            }
            var pos = player.blockPosition();
            var dimension = level.dimension().location().toString();
            Town at = data.findOverlapping(dimension, pos, pos);
            if (at == null) {
                source.sendFailure(Component.literal(
                        "你当前不在任何城镇范围内，请站进城镇或显式指定 <城镇>"));
            }
            return at;
        }
        Town byId = data.findTown(townText); // UUID 优先
        if (byId != null) {
            return byId;
        }
        var matches = data.allTowns().stream()
                .filter(t -> t.name().equalsIgnoreCase(townText)).toList();
        if (matches.size() == 1) {
            return matches.get(0);
        }
        if (matches.isEmpty()) {
            source.sendFailure(Component.literal("找不到城镇: " + townText));
        } else {
            source.sendFailure(Component.literal(
                    "存在多个同名城镇（" + matches.size() + " 个），请改用 UUID"));
        }
        return null;
    }

    /** 解锁（调试）：反馈 = 聊天公告本身；重复解锁给轻提示。 */
    private static int doUnlock(net.minecraft.commands.CommandSourceStack source,
            @javax.annotation.Nullable String townText, String id) {
        Town town = resolveTown(source, townText);
        if (town == null) {
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        if (com.bmf.bmfstown.unlock.UnlockState.get(level)
                .unlockIfNew(level, town.id(), id)) {
            return 1;
        }
        source.sendFailure(Component.literal(
                "「" + town.name() + "」已解锁过: " + id));
        return 0;
    }

    /** 重新锁定（调试）：静默操作，仅命令回显。 */
    private static int doLock(net.minecraft.commands.CommandSourceStack source,
            @javax.annotation.Nullable String townText, String id) {
        Town town = resolveTown(source, townText);
        if (town == null) {
            return 0;
        }
        net.minecraft.server.level.ServerLevel level = source.getLevel();
        boolean removed = com.bmf.bmfstown.unlock.UnlockState.get(level)
                .lock(level, town.id(), id);
        if (removed) {
            source.sendSuccess(() -> Component.literal(
                    "「" + town.name() + "」已重新锁定: " + id), false);
        } else {
            source.sendFailure(Component.literal(
                    "「" + town.name() + "」本就未解锁: " + id));
        }
        return removed ? 1 : 0;
    }

    /** 列出城镇已解锁的 id（调试）。 */
    private static int doUnlocks(net.minecraft.commands.CommandSourceStack source,
            @javax.annotation.Nullable String townText) {
        Town town = resolveTown(source, townText);
        if (town == null) {
            return 0;
        }
        var ids = com.bmf.bmfstown.unlock.UnlockState.get(source.getLevel())
                .allIds(town.id());
        source.sendSuccess(() -> Component.literal(
                "「" + town.name() + "」已解锁 " + ids.size() + " 项: "
                        + String.join(", ", ids.stream().sorted().toList())), false);
        return ids.size();
    }


    /** 历法调试：报当前镇民历日期（年/月/日/月相）。 */
    private static int showDate(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.level.ServerLevel level = ctx.getSource().getLevel();
        com.bmf.bmfstown.calendar.TownCalendar.CalendarDate date =
                com.bmf.bmfstown.calendar.TownCalendar.get(level).today(level);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "msg.bmfstown.calendar.today",
                date.year(), date.month(), date.day(),
                com.bmf.bmfstown.calendar.TownCalendar.moonPhaseName(date.moonPhase())), false);
        return 1;
    }

    /** 魔法降雪：以命令执行者脚下为中心开始。 */
    private static int startSnowfall(CommandContext<CommandSourceStack> ctx, int seconds, int radius)
            throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean ok = com.bmf.bmfstown.weather.SnowfallManager.start(
                player.serverLevel(), player.blockPosition(), radius, seconds);
        if (!ok) {
            ctx.getSource().sendFailure(Component.translatable("msg.bmfstown.snowfall.rejected"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "msg.bmfstown.snowfall.started", radius, seconds), false);
        return 1;
    }

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
            resident.getStatus().reroll(resident, serverLevel);
            resident.syncClient();
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
