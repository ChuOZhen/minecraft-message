package com.msgboard.gametest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import net.minecraft.network.chat.Component;

/**
 * 捕获客户端**真实收到**的服务端系统聊天消息原始组件。
 *
 * <p>由 {@code ClientPacketListenerMixin} 在 {@code handleSystemChat} 的头部写入，
 * 记录的是客户端网络层拿到的组件对象本身（含颜色与点击事件），
 * 而不是重新构造出来的等价物——所以断言的对象就是玩家实际会看到的东西。
 */
public final class CapturedChat {
	private static final List<Component> MESSAGES = new CopyOnWriteArrayList<>();

	private CapturedChat() {
	}

	public static void record(Component content) {
		if (content != null) {
			MESSAGES.add(content);
		}
	}

	public static void clear() {
		MESSAGES.clear();
	}

	public static List<Component> messages() {
		return List.copyOf(MESSAGES);
	}

	/** 取出包含指定文本的最早一条消息。 */
	public static Component firstContaining(String needle) {
		for (Component c : MESSAGES) {
			if (flatten(c).contains(needle)) {
				return c;
			}
		}

		return null;
	}

	public static String flatten(Component root) {
		StringBuilder sb = new StringBuilder();
		collect(root, sb);
		return sb.toString();
	}

	private static void collect(Component c, StringBuilder sb) {
		sb.append(c.getString());

		for (Component sibling : c.getSiblings()) {
			collect(sibling, sb);
		}
	}
}
