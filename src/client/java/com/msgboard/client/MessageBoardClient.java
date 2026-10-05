package com.msgboard.client;

import net.fabricmc.api.ClientModInitializer;

/**
 * 客户端入口。
 *
 * <p>本模组是纯服务端模组：玩家无需安装任何东西即可收到留言提示。
 * 这个入口只是为了让 fabric.mod.json 里的 client 入口点有效，
 * 方便以后需要时加客户端侧的自定义渲染。
 */
public class MessageBoardClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 故意留空：所有逻辑都在服务端完成。
	}
}
