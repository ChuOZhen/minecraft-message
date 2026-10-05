package com.msgboard.util;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.msgboard.MessageBoardMod;
import com.msgboard.data.MsgStore;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 「名称册子」Tab 补全：候选 = 名册里登记过的玩家 + 当前在线玩家。
 *
 * <p>去重（不区分大小写）、按字典序排序，并让 Brigadier 自己按已输入前缀过滤。
 */
public final class NameSuggestions implements SuggestionProvider<CommandSourceStack> {
	public static final NameSuggestions INSTANCE = new NameSuggestions();

	private NameSuggestions() {
	}

	@Override
	public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> context,
			SuggestionsBuilder builder) throws CommandSyntaxException {
		for (String name : candidates(context.getSource().getServer())) {
			builder.suggest(name);
		}

		return builder.buildFuture();
	}

	/** 名册 + 在线的玩家名，去重排序。 */
	public static List<String> candidates(MinecraftServer server) {
		Set<String> seen = new LinkedHashSet<>();

		if (MessageBoardMod.store() != null) {
			for (String name : MessageBoardMod.store().names()) {
				if (MsgStore.isValidPlayerName(name)) {
					seen.add(name);
				}
			}
		}

		if (server != null) {
			for (String name : server.getPlayerList().getPlayerNamesArray()) {
				if (name != null && !name.isEmpty()) {
					seen.add(name);
				}
			}
		}

		List<String> out = new ArrayList<>(seen);
		out.sort(String.CASE_INSENSITIVE_ORDER);
		return out;
	}

	/** 供指令帮助/统计用：名册人数。 */
	public static int bookSize() {
		return MessageBoardMod.store() == null ? 0 : MessageBoardMod.store().nameCount();
	}

	/** 在线玩家名（不含名册）。 */
	public static List<String> onlineNames(MinecraftServer server) {
		List<String> out = new ArrayList<>();

		if (server != null) {
			for (ServerPlayer p : server.getPlayerList().getPlayers()) {
				out.add(p.getGameProfile().name());
			}
		}

		return out;
	}
}
