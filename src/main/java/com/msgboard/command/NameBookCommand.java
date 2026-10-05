package com.msgboard.command;

import java.util.List;
import java.util.UUID;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.msgboard.MessageBoardMod;
import com.msgboard.data.MsgStore;
import com.msgboard.util.ChatUtil;
import com.msgboard.util.NameSuggestions;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /messagename} —— OP 维护「名称册子」。
 *
 * <ul>
 *   <li>{@code /messagename <玩家名>} 添加</li>
 *   <li>{@code /messagename remove <玩家名>} 移除</li>
 *   <li>{@code /messagename list} 查看</li>
 * </ul>
 *
 * <p>需要权限等级 2（OP）。添加时会尝试解析 UUID：优先取在线玩家资料，
 * 其次查 Mojang 的玩家档案缓存；都拿不到就先只记名字，等他上线后自动回填。
 */
public final class NameBookCommand {
	private NameBookCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("messagename")
				.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
				.then(Commands.literal("list")
						.executes(NameBookCommand::executeList))
				.then(Commands.literal("remove")
						.then(Commands.argument("name", StringArgumentType.word())
								.suggests(NameSuggestions.INSTANCE)
								.executes(NameBookCommand::executeRemove)))
				.then(Commands.argument("name", StringArgumentType.word())
						.suggests(NameSuggestions.INSTANCE)
						.executes(NameBookCommand::executeAdd)));
	}

	// ------------------------------------------------------------------ add

	private static int executeAdd(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		MsgStore store = MessageBoardMod.store();

		if (store == null) {
			source.sendFailure(ChatUtil.red("留言板数据尚未加载。"));
			return 0;
		}

		String raw = StringArgumentType.getString(ctx, "name");
		String name = MsgStore.sanitizePlayerName(raw);

		if (!MsgStore.isValidPlayerName(name)) {
			source.sendFailure(ChatUtil.red("玩家名不合法：仅允许 3-16 位字母、数字、下划线。"));
			return 0;
		}

		if (store.hasName(name)) {
			source.sendFailure(ChatUtil.red("名称册子里已经有 " + name + " 了。"));
			return 0;
		}

		MinecraftServer server = source.getServer();
		UUID uuid = resolveUuid(server, name);
		store.addName(name, uuid);

		final String display = name;
		final boolean resolved = uuid != null;

		source.sendSuccess(() -> Component.empty()
				.append(ChatUtil.gray("[留言板] 已把 "))
				.append(ChatUtil.aqua(display))
				.append(ChatUtil.gray(" 加入名称册子"))
				.append(ChatUtil.gray(resolved ? "。" : "（暂未解析到 UUID，等他上线会自动补上）。")), true);

		return 1;
	}

	// ------------------------------------------------------------------ remove

	private static int executeRemove(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		MsgStore store = MessageBoardMod.store();

		if (store == null) {
			source.sendFailure(ChatUtil.red("留言板数据尚未加载。"));
			return 0;
		}

		String name = MsgStore.sanitizePlayerName(StringArgumentType.getString(ctx, "name"));

		if (!store.removeName(name)) {
			source.sendFailure(ChatUtil.red("名称册子里没有 " + name + "。"));
			return 0;
		}

		final String display = name;
		source.sendSuccess(() -> Component.empty()
				.append(ChatUtil.gray("[留言板] 已把 "))
				.append(ChatUtil.aqua(display))
				.append(ChatUtil.gray(" 移出名称册子（已有留言不受影响）。")), true);

		return 1;
	}

	// ------------------------------------------------------------------ list

	private static int executeList(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		MsgStore store = MessageBoardMod.store();

		if (store == null) {
			source.sendFailure(ChatUtil.red("留言板数据尚未加载。"));
			return 0;
		}

		List<String> names = store.names();

		if (names.isEmpty()) {
			source.sendSuccess(() -> ChatUtil.gray("[留言板] 名称册子为空。用 /messagename <玩家名> 添加。"), false);
			return 1;
		}

		source.sendSuccess(() -> ChatUtil.gray("[留言板] 名称册子（" + names.size() + " 人）："), false);

		for (String name : names) {
			final String display = name;
			boolean known = store.uuidOf(name) != null;
			source.sendSuccess(() -> Component.empty()
					.append(ChatUtil.white(" - " + display + " "))
					.append(known ? ChatUtil.green("[已绑定 UUID]") : ChatUtil.yellow("[待上线绑定]"))
					.append(ChatUtil.gray("  "))
					.append(ChatUtil.clickableRun("[移除]", ChatFormatting.RED,
							"/messagename remove " + display, "把 " + display + " 移出名称册子")), false);
		}

		return 1;
	}

	// ------------------------------------------------------------------ 工具

	/**
	 * 解析玩家 UUID：在线玩家优先，其次本地「名字 → UUID」缓存，
	 * 最后向 Mojang 查询在线档案（失败或超时都不影响加名册）。
	 * 全都拿不到时返回 null，等该玩家上线时由投递逻辑回填。
	 */
	private static UUID resolveUuid(MinecraftServer server, String name) {
		if (server == null) {
			return null;
		}

		ServerPlayer online = MessageCommand.findOnline(server, name);

		if (online != null) {
			return online.getUUID();
		}

		try {
			var cached = server.services().nameToIdCache().get(name);

			if (cached.isPresent()) {
				return cached.get().id();
			}
		} catch (Exception e) {
			MessageBoardMod.LOGGER.debug("[留言板] 查询 {} 的本地名字缓存时出错，继续尝试联网查询。", name, e);
		}

		try {
			var fetched = server.services().profileResolver().fetchByName(name);

			if (fetched.isPresent()) {
				return fetched.get().id();
			}
		} catch (Exception e) {
			MessageBoardMod.LOGGER.warn("[留言板] 向 Mojang 查询 {} 的档案失败，等他上线再绑定。", name, e);
		}

		return null;
	}
}
