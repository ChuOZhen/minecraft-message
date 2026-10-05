package com.msgboard.delivery;

import java.util.List;
import java.util.UUID;

import com.msgboard.MessageBoardMod;
import com.msgboard.data.MsgEntry;
import com.msgboard.data.MsgStore;
import com.msgboard.util.ChatUtil;

import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.level.ServerPlayer;

/**
 * 上线投递：玩家进入服务器时，把尚未送达的留言合并成一条可点击提示发给他。
 */
public final class DeliveryService {
	private DeliveryService() {
	}

	/**
	 * 立即向在线玩家送达一条留言（发同款可点击提示并标记已投递）。
	 * 给在线玩家留言时由 {@code /msgboard send} 直接调用。
	 */
	public static void deliverNow(ServerPlayer target, MsgEntry entry, MsgStore store) {
		target.sendSystemMessage(ChatUtil.joinNotice(List.of(entry)));
		store.markDelivered(List.of(entry), target.getUUID());
	}

	public static void register() {
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			MsgStore store = MessageBoardMod.store();

			if (store == null) {
				return;
			}

			ServerPlayer player = handler.getPlayer();

			if (player == null) {
				return;
			}

			UUID id = player.getUUID();
			String name = player.getGameProfile().name();

			// 名册里只有名字的条目，在这里补上 UUID，之后投递一律走 UUID。
			if (store.hasName(name)) {
				store.addName(name, id);
			}

			List<MsgEntry> pending = store.pendingFor(id, name);

			if (pending.isEmpty()) {
				return;
			}

			player.sendSystemMessage(ChatUtil.joinNotice(pending));
			store.markDelivered(pending, id);

			MessageBoardMod.LOGGER.info("[留言板] 向 {} 投递了 {} 条留言。", name, pending.size());
		});
	}
}
