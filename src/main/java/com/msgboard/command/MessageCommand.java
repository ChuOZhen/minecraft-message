package com.msgboard.command;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.msgboard.MessageBoardMod;
import com.msgboard.data.MsgEntry;
import com.msgboard.data.MsgStore;
import com.msgboard.delivery.DeliveryService;
import com.msgboard.util.ChatUtil;
import com.msgboard.util.NameSuggestions;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /msgboard} 指令树。
 *
 * <ul>
 *   <li>{@code /msgboard send <玩家> <内容>} —— 给（多在离线的）玩家留言，玩家名支持 Tab 补全</li>
 *   <li>{@code /msgboard read <ID>} —— 点开某条留言（聊天栏绿色「留言」的点击目标）</li>
 *   <li>{@code /msgboard sent} —— 查看自己发出的留言与已读回执</li>
 *   <li>{@code /msgboard names} —— 查看名称册子</li>
 * </ul>
 */
public final class MessageCommand {
	/** 单条留言的长度上限。 */
	public static final int MAX_LENGTH = 200;

	/** 同一玩家两次留言的最小间隔（毫秒）。 */
	public static final long COOLDOWN_MS = 3_000L;

	private static final long SENT_PAGE_SIZE = 8L;

	private static final Map<UUID, Long> LAST_SENT = new HashMap<>();

	private MessageCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("msgboard")
				.then(Commands.literal("send")
						.then(Commands.argument("target", StringArgumentType.word())
								.suggests(NameSuggestions.INSTANCE)
								.then(Commands.argument("content", StringArgumentType.greedyString())
										.executes(MessageCommand::executeSend))))
				.then(Commands.literal("read")
						.then(Commands.argument("id", LongArgumentType.longArg(1L))
								.executes(MessageCommand::executeRead)))
				.then(Commands.literal("sent")
						.executes(ctx -> executeSent(ctx, 1))
						.then(Commands.argument("page", LongArgumentType.longArg(1L))
								.executes(ctx -> executeSent(ctx, LongArgumentType.getLong(ctx, "page")))))
				.then(Commands.literal("names")
						.executes(MessageCommand::executeNames))
				.then(Commands.literal("help")
						.executes(MessageCommand::executeHelp))
				.executes(MessageCommand::executeHelp));
	}

	// ------------------------------------------------------------------ send

	private static int executeSend(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer sender = source.getPlayerOrException();
		MsgStore store = requireStore();

		String target = MsgStore.sanitizePlayerName(StringArgumentType.getString(ctx, "target"));
		final String content;

		{
			String raw = MsgStore.sanitizeContent(StringArgumentType.getString(ctx, "content"));

			if (raw.isEmpty()) {
				source.sendFailure(ChatUtil.red("留言内容不能为空。"));
				return 0;
			}

			if (raw.length() > MAX_LENGTH) {
				content = raw.substring(0, MAX_LENGTH);
				source.sendSuccess(() -> ChatUtil.yellow("留言过长，已截断为 " + MAX_LENGTH + " 字。"), false);
			} else {
				content = raw;
			}
		}

		if (!MsgStore.isValidPlayerName(target)) {
			source.sendFailure(ChatUtil.red("玩家名不合法：仅允许 3-16 位字母、数字、下划线。"));
			return 0;
		}

		String selfName = sender.getGameProfile().name();

		if (target.equalsIgnoreCase(selfName)) {
			source.sendFailure(ChatUtil.red("不能给自己留言。"));
			return 0;
		}

		long now = System.currentTimeMillis();
		Long last = LAST_SENT.get(sender.getUUID());

		if (last != null && now - last < COOLDOWN_MS) {
			long wait = (COOLDOWN_MS - (now - last) + 999L) / 1000L;
			source.sendFailure(ChatUtil.red("发言太快了，请 " + wait + " 秒后再试。"));
			return 0;
		}

		MinecraftServer server = source.getServer();

		// 目标是否在线：在线则立即送达，不在线则等其上线。
		// 名字册子未收录的玩家也允许留言（按名字记录），对方上线时仍能收到。
		ServerPlayer online = findOnline(server, target);
		boolean isOnline = online != null;

		UUID recipientId = isOnline ? online.getUUID() : store.uuidOf(target);
		String recipientName = isOnline ? online.getGameProfile().name() : target;

		MsgEntry entry = store.addMessage(sender.getUUID(), selfName, recipientId, recipientName, content);
		LAST_SENT.put(sender.getUUID(), now);

		if (isOnline) {
			DeliveryService.deliverNow(online, entry, store);
			source.sendSuccess(() -> Component.empty()
					.append(ChatUtil.gray("[留言板] 已把留言发给在线的 "))
					.append(ChatUtil.aqua(recipientName))
					.append(ChatUtil.gray("。")), false);
		} else {
			int waiting = store.pendingFor(recipientId, recipientName).size();
			source.sendSuccess(() -> Component.empty()
					.append(ChatUtil.gray("[留言板] 已给 "))
					.append(ChatUtil.aqua(recipientName))
					.append(ChatUtil.gray(" 留言（当前有 " + waiting + " 条待接收），他上线时会看到提示。")), false);
		}

		return 1;
	}

	// ------------------------------------------------------------------ read

	private static int executeRead(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		MsgStore store = MessageBoardMod.store();

		if (store == null) {
			source.sendFailure(ChatUtil.red("留言板数据尚未加载。"));
			return 0;
		}

		long id;

		try {
			id = LongArgumentType.getLong(ctx, "id");
		} catch (IllegalArgumentException e) {
			source.sendFailure(ChatUtil.red("留言 ID 不合法。"));
			return 0;
		}

		MsgEntry entry = store.byId(id);

		if (entry == null) {
			source.sendFailure(ChatUtil.red("找不到 ID 为 " + id + " 的留言。"));
			return 0;
		}

		ServerPlayer player;

		try {
			player = source.getPlayerOrException();
		} catch (CommandSyntaxException e) {
			// 控制台/命令方块调用时直接打印，不做归属与已读处理。
			source.sendSuccess(() -> ChatUtil.inlineDetail(entry), false);
			return 1;
		}

		UUID pid = player.getUUID();
		String pname = player.getGameProfile().name();

		if (!MsgStore.matchesRecipient(entry, pid, pname)) {
			source.sendFailure(ChatUtil.red("这条留言不是给你的。"));
			return 0;
		}

		boolean wasUnread = !entry.read;

		// 上线提示按发送者合并，只给了组内 ID 最小的那一条的链接。
		// 因此点开时要把同一发送者、同一次投递的其他留言一并展示，并一起标记已读。
		List<MsgEntry> group = new ArrayList<>();

		for (MsgEntry m : store.sentBy(entry.senderUuid())) {
			if (m.delivered && !m.read && MsgStore.matchesRecipient(m, pid, pname)) {
				group.add(m);
			}
		}

		if (group.isEmpty()) {
			group.add(entry);
		}

		player.sendSystemMessage(ChatUtil.detail(entry, wasUnread));

		List<Long> ids = new ArrayList<>();

		for (MsgEntry m : group) {
			ids.add(m.id);

			if (m.id != entry.id) {
				player.sendSystemMessage(ChatUtil.inlineDetail(m));
			}
		}

		store.markRead(pid, pname, ids);
		return 1;
	}

	// ------------------------------------------------------------------ sent

	private static int executeSent(CommandContext<CommandSourceStack> ctx, long page) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayerOrException();
		MsgStore store = requireStore();

		List<MsgEntry> sent = store.sentBy(player.getUUID());

		if (sent.isEmpty()) {
			source.sendSuccess(() -> ChatUtil.gray("[留言板] 你还没有发出过留言。"), false);
			return 1;
		}

		long pages = Math.max(1L, (sent.size() + SENT_PAGE_SIZE - 1) / SENT_PAGE_SIZE);
		long current = Math.max(1L, Math.min(page, pages));
		int from = (int) ((current - 1) * SENT_PAGE_SIZE);
		int to = (int) Math.min(sent.size(), from + SENT_PAGE_SIZE);

		source.sendSuccess(() -> ChatUtil.gray("[留言板] 我发出的留言（第 " + current + "/" + pages + " 页，共 "
				+ sent.size() + " 条）："), false);

		for (int i = from; i < to; i++) {
			MsgEntry m = sent.get(i);
			source.sendSuccess(() -> sentLine(m), false);
		}

		if (pages > 1) {
			source.sendSuccess(() -> Component.empty()
					.append(ChatUtil.gray("上一页 "))
					.append(ChatUtil.clickableRun("◀", ChatFormatting.YELLOW,
							"/msgboard sent " + Math.max(1L, current - 1), "查看上一页"))
					.append(ChatUtil.gray("  下一页 "))
					.append(ChatUtil.clickableRun("▶", ChatFormatting.YELLOW,
							"/msgboard sent " + Math.min(pages, current + 1), "查看下一页")), false);
		}

		return 1;
	}

	private static Component sentLine(MsgEntry m) {
		Component status = m.read
				? ChatUtil.green("[已读 " + m.formattedReadTime() + "]")
				: m.delivered
						? ChatUtil.yellow("[已送达未读]")
						: ChatUtil.gray("[未送达]");

		return Component.empty()
				.append(ChatUtil.gray(" #" + m.id + " → "))
				.append(ChatUtil.aqua(m.recipientName == null ? "?" : m.recipientName))
				.append(ChatUtil.gray(" " + m.formattedTime() + " "))
				.append(status)
				.append(Component.literal("\n"))
				.append(ChatUtil.white(m.content == null ? "" : m.content));
	}

	// ------------------------------------------------------------------ names / help

	private static int executeNames(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		MsgStore store = requireStore();
		List<String> names = store.names();

		if (names.isEmpty()) {
			source.sendSuccess(() -> ChatUtil.gray("[留言板] 名称册子为空。OP 可用 /messagename <玩家名> 添加。"), false);
			return 1;
		}

		source.sendSuccess(() -> ChatUtil.gray("[留言板] 名称册子（" + names.size() + " 人）："), false);
		source.sendSuccess(() -> ChatUtil.white(String.join("、", names)), false);
		return 1;
	}

	private static int executeHelp(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		source.sendSuccess(() -> ChatUtil.gray("[留言板] 用法："), false);
		source.sendSuccess(() -> ChatUtil.clickableSuggest("/msgboard send <玩家名> <留言内容>",
				ChatFormatting.YELLOW, "/msgboard send ", "点击填入指令，玩家名可用 Tab 补全"), false);
		source.sendSuccess(() -> ChatUtil.clickableRun("/msgboard sent",
				ChatFormatting.YELLOW, "/msgboard sent", "查看我发出的留言与已读状态"), false);
		source.sendSuccess(() -> ChatUtil.clickableRun("/msgboard names",
				ChatFormatting.YELLOW, "/msgboard names", "查看名称册子"), false);

		if (Commands.LEVEL_GAMEMASTERS.check(source.permissions())) {
			source.sendSuccess(() -> ChatUtil.clickableSuggest("/messagename <玩家名>",
					ChatFormatting.GREEN, "/messagename ", "把玩家加入名称册子（OP）"), false);
		}

		return 1;
	}

	// ------------------------------------------------------------------ 工具

	private static MsgStore requireStore() {
		MsgStore store = MessageBoardMod.store();

		if (store == null) {
			throw new IllegalStateException("留言板数据尚未加载");
		}

		return store;
	}

	/** 在在线玩家中按名字查找（不区分大小写）。 */
	public static ServerPlayer findOnline(MinecraftServer server, String name) {
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (p.getGameProfile().name().equalsIgnoreCase(name)) {
				return p;
			}
		}

		return null;
	}
}
