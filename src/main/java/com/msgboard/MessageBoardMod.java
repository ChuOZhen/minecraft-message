package com.msgboard;

import com.msgboard.command.MessageCommand;
import com.msgboard.command.NameBookCommand;
import com.msgboard.data.MsgStore;
import com.msgboard.delivery.DeliveryService;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 留言板 主入口（服务端模组）。
 *
 * <p>玩家 1 用 /msgboard send &lt;玩家2&gt; &lt;内容&gt; 给离线玩家留言；
 * 玩家 2 下次进入服务器时在聊天栏收到可点击的绿色「留言」提示，点击即可展开内容。
 */
public class MessageBoardMod implements ModInitializer {
	public static final String MOD_ID = "msgboard";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	/** 存档级存储：<世界存档目录>/msgboard.json */
	private static MsgStore store;

	public static MsgStore store() {
		return store;
	}

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTING.register(server -> {
			store = new MsgStore(server);
			store.load();
			LOGGER.info("[留言板] 已加载，共 {} 条留言、{} 个名册玩家。",
					store.data().messages.size(), store.data().names.size());
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			if (store != null) {
				store.save();
				store = null;
			}
		});

		DeliveryService.register();

		CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
			MessageCommand.register(dispatcher);
			NameBookCommand.register(dispatcher);
		});

		LOGGER.info("[留言板] 初始化完成。");
	}
}
