package com.msgboard.util;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.msgboard.data.MsgEntry;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/**
 * 聊天栏文本构造：所有绿色可点击「留言」入口都在这里生成。
 *
 * <p>点击行为用 {@link ClickEvent.RunCommand} 直接执行 {@code /msgboard read <id>}，
 * 而不是把指令填进输入框，避免误发到公屏。
 */
public final class ChatUtil {
	/** 「留言」二字使用的绿色。 */
	public static final ChatFormatting LINK_COLOR = ChatFormatting.GREEN;

	private ChatUtil() {
	}

	public static MutableComponent gray(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GRAY);
	}

	public static MutableComponent white(String text) {
		return Component.literal(text).withStyle(ChatFormatting.WHITE);
	}

	public static MutableComponent yellow(String text) {
		return Component.literal(text).withStyle(ChatFormatting.YELLOW);
	}

	public static MutableComponent red(String text) {
		return Component.literal(text).withStyle(ChatFormatting.RED);
	}

	public static MutableComponent green(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GREEN);
	}

	public static MutableComponent aqua(String text) {
		return Component.literal(text).withStyle(ChatFormatting.AQUA);
	}

	/** 可点击的绿色「留言」，点击后展开对应留言内容。 */
	public static MutableComponent readLink(long id, String hover) {
		return Component.literal("留言")
				.withStyle(Style.EMPTY
						.withColor(LINK_COLOR)
						.withClickEvent(new ClickEvent.RunCommand("/msgboard read " + id))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/**
	 * 上线提示，按发送者合并成一行：
	 * {@code [玩家A] 向你 [留言]、[玩家B] 向你 [留言]。}
	 */
	public static MutableComponent joinNotice(List<MsgEntry> pending) {
		Map<String, List<MsgEntry>> bySender = new LinkedHashMap<>();

		for (MsgEntry m : pending) {
			String sender = m.senderName == null || m.senderName.isEmpty() ? "未知玩家" : m.senderName;
			bySender.computeIfAbsent(sender, k -> new java.util.ArrayList<>()).add(m);
		}

		MutableComponent root = Component.empty();

		if (pending.size() > 1) {
			root.append(gray("[留言板] 你有 " + pending.size() + " 条新留言："));
		} else {
			root.append(gray("[留言板] "));
		}

		int index = 0;

		for (Map.Entry<String, List<MsgEntry>> e : bySender.entrySet()) {
			List<MsgEntry> group = e.getValue();
			MsgEntry first = group.get(0);

			if (index > 0) {
				root.append(gray("、"));
			}

			Long firstId = group.stream().map(m -> m.id).min(Long::compareTo).orElse(first.id);
			String hover = group.size() > 1
					? "点击查看 " + e.getKey() + " 的 " + group.size() + " 条留言"
					: "点击查看 " + e.getKey() + " 的留言";

			root.append(gray("[" + e.getKey() + "] 向你 "))
					.append(readLink(firstId, hover))
					.append(gray(group.size() > 1 ? "（" + group.size() + " 条）" : ""));

			index++;
		}

		root.append(gray("。"));
		return root;
	}

	/** 点开后的详情：发送者、时间、正文。 */
	public static MutableComponent detail(MsgEntry m, boolean showReadHint) {
		MutableComponent root = Component.empty();
		root.append(gray("[留言板] 来自 "));
		root.append(aqua(m.senderName == null ? "未知玩家" : m.senderName));
		root.append(gray(" 的留言（" + m.formattedTime() + "）："));

		if (showReadHint) {
			root.append(gray(" ")).append(green("（已标记为已读）"));
		}

		root.append(Component.literal("\n"));
		root.append(white(m.content == null ? "" : m.content));
		return root;
	}

	/** 点击查看后的一行式展开（用于 /msgboard read 单条）。 */
	public static MutableComponent inlineDetail(MsgEntry m) {
		MutableComponent root = Component.empty();
		root.append(gray("[留言板] "));
		root.append(aqua(m.senderName == null ? "未知玩家" : m.senderName));
		root.append(gray(" (" + m.formattedTime() + ") 留言："));
		root.append(white(m.content == null ? "" : m.content));
		return root;
	}

	public static MutableComponent clickableRun(String label, ChatFormatting color, String command, String hover) {
		return Component.literal(label)
				.withStyle(Style.EMPTY
						.withColor(color)
						.withClickEvent(new ClickEvent.RunCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	public static MutableComponent clickableSuggest(String label, ChatFormatting color, String command, String hover) {
		return Component.literal(label)
				.withStyle(Style.EMPTY
						.withColor(color)
						.withClickEvent(new ClickEvent.SuggestCommand(command))
						.withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}
}
