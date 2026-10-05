package com.msgboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.msgboard.data.MsgEntry;
import com.msgboard.data.MsgStore;
import com.msgboard.util.ChatUtil;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;

/**
 * 核心验收点的自动化测试：
 * 「留言」二字必须是绿色、可点击，且点击能立即展开对应留言。
 *
 * <p>这些断言直接跑在 Minecraft 的文本组件上（Fabric Loader JUnit），
 * 因此验证的是**客户端真正收到的数据**，不是我们自己拼的字符串。
 */
class ChatUtilTest {
	private static final UUID RECIPIENT = UUID.fromString("069a79f4-44e9-4726-a5be-fca90e38aaf5");

	private static MsgEntry entry(long id, String sender, String content) {
		return new MsgEntry(id, UUID.randomUUID(), sender, RECIPIENT, "Bob", content);
	}

	/** 在组件树里找到第一个带 RunCommand 点击事件的节点。 */
	private static Component findRunCommand(Component root) {
		if (root.getStyle() != null && root.getStyle().getClickEvent() != null
				&& root.getStyle().getClickEvent() instanceof ClickEvent.RunCommand) {
			return root;
		}

		for (Component sibling : root.getSiblings()) {
			Component found = findRunCommand(sibling);

			if (found != null) {
				return found;
			}
		}

		return null;
	}

	/** Style.getColor() 返回 TextColor；绿色必须等值于 legacy GREEN。 */
	private static void assertGreen(Style style, String message) {
		assertNotNull(style.getColor(), message + "（颜色为空）");
		assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN), style.getColor(), message);
		assertEquals(TextColor.GREEN, style.getColor(), message);
	}

	private static String plainText(Component root) {
		StringBuilder sb = new StringBuilder();
		collect(root, sb);
		return sb.toString();
	}

	private static void collect(Component c, StringBuilder sb) {
		sb.append(c.getString());

		for (Component s : c.getSiblings()) {
			collect(s, sb);
		}
	}

	// ------------------------------------------------------------------ 点击展开（核心）

	@Test
	@DisplayName("readLink：「留言」是绿色，且点击直接执行 /msgboard read <id>")
	void readLinkIsGreenAndClickable() {
		MutableComponent link = ChatUtil.readLink(7L, "点击查看 Alice 的留言");

		assertEquals("留言", link.getString(), "文字必须正好是「留言」两个字");

		Style style = link.getStyle();
		assertNotNull(style, "必须有样式");
		assertGreen(style, "「留言」必须是绿色");

		assertNotNull(style.getClickEvent(), "必须可点击");
		assertTrue(style.getClickEvent() instanceof ClickEvent.RunCommand, "点击必须是「直接执行指令」而不是填入输入框");
		assertEquals("/msgboard read 7", ((ClickEvent.RunCommand) style.getClickEvent()).command(),
				"点击必须精确指向这条留言");

		assertNotNull(style.getHoverEvent(), "鼠标悬停必须有提示");
		assertTrue(style.getHoverEvent() instanceof HoverEvent.ShowText, "悬停提示必须是文字");
	}

	@Test
	@DisplayName("上线提示：单个发送者时，绿色「留言」指向该条留言")
	void joinNoticeSingleSender() {
		MsgEntry m = entry(3L, "Alice", "明天八点一起打末影龙");
		Component notice = ChatUtil.joinNotice(List.of(m));

		String text = plainText(notice);
		assertTrue(text.contains("[留言板]"), "要有 [留言板] 前缀：" + text);
		assertTrue(text.contains("[Alice] 向你 留言。"), "提示文案不符合预期：" + text);

		Component link = findRunCommand(notice);
		assertNotNull(link, "必须有一个可点击的「留言」");
		assertGreen(link.getStyle(), "「留言」必须是绿色");
		assertEquals("/msgboard read 3", ((ClickEvent.RunCommand) link.getStyle().getClickEvent()).command());
	}

	@Test
	@DisplayName("上线提示：同一发送者的多条留言只给一个绿色入口，并标注条数")
	void joinNoticeMergesSameSender() {
		Component notice = ChatUtil.joinNotice(List.of(
				entry(5L, "Alice", "第一条"),
				entry(6L, "Alice", "第二条")));

		String text = plainText(notice);
		assertTrue(text.contains("（2 条）"), "同发送者多条要标注条数：" + text);

		// 只应有一个可点击入口，且指向组内最小 ID
		assertEquals(1, countRunCommands(notice), "同一发送者只应有一个绿色入口");
		assertEquals("/msgboard read 5",
				((ClickEvent.RunCommand) findRunCommand(notice).getStyle().getClickEvent()).command());
	}

	@Test
	@DisplayName("上线提示：多个发送者合并成一行，各自可点")
	void joinNoticeMergesDifferentSenders() {
		Component notice = ChatUtil.joinNotice(List.of(
				entry(1L, "Alice", "A 的留言"),
				entry(2L, "Bob", "B 的留言")));

		String text = plainText(notice);
		assertTrue(text.contains("你有 2 条新留言"), "多条时应给出总数：" + text);
		assertTrue(text.contains("[Alice] 向你 留言"), text);
		assertTrue(text.contains("[Bob] 向你 留言"), text);
		assertTrue(text.endsWith("。"), "应以句号结尾：" + text);
		assertEquals(2, countRunCommands(notice), "两个发送者应有各自的可点击入口");
	}

	private static int countRunCommands(Component root) {
		int n = 0;

		if (root.getStyle() != null && root.getStyle().getClickEvent() instanceof ClickEvent.RunCommand) {
			n++;
		}

		for (Component s : root.getSiblings()) {
			n += countRunCommands(s);
		}

		return n;
	}

	// ------------------------------------------------------------------ 收件人匹配

	@Test
	@DisplayName("收件人匹配：绑定 UUID 后按 UUID 匹配，且大小写不敏感")
	void matchesByUuid() {
		MsgEntry m = entry(1L, "Alice", "hi");

		assertTrue(MsgStore.matchesRecipient(m, RECIPIENT, "bob"), "UUID 相同即匹配");
		assertFalse(MsgStore.matchesRecipient(m, UUID.randomUUID(), "Bob"), "UUID 不同不匹配");
	}

	@Test
	@DisplayName("收件人匹配：UUID 未知时退回按名字匹配（不区分大小写）")
	void matchesByFallbackName() {
		MsgEntry m = new MsgEntry(1L, UUID.randomUUID(), "Alice", null, "Bob", "hi");

		assertTrue(MsgStore.matchesRecipient(m, UUID.randomUUID(), "bob"), "名字匹配应忽略大小写");
		assertFalse(MsgStore.matchesRecipient(m, UUID.randomUUID(), "Carol"), "名字不同不匹配");
	}

	// ------------------------------------------------------------------ 输入清洗

	@Test
	@DisplayName("清理：去掉颜色符号、把换行压成单行")
	void sanitize() {
		assertEquals("hello world", MsgStore.sanitizeContent("hello\nworld"));
		assertEquals("a b", MsgStore.sanitizeContent("  a\r\n\r\nb  "));
		assertEquals("red", MsgStore.sanitizeContent("§cred"));
		assertEquals("Steve", MsgStore.sanitizePlayerName("§aSteve "));
	}

	@Test
	@DisplayName("玩家名合法性：3-16 位字母数字下划线")
	void playerNameValidation() {
		assertTrue(MsgStore.isValidPlayerName("Steve"));
		assertTrue(MsgStore.isValidPlayerName("a_1"));
		assertFalse(MsgStore.isValidPlayerName("ab"), "太短");
		assertFalse(MsgStore.isValidPlayerName("a".repeat(17)), "太长");
		assertFalse(MsgStore.isValidPlayerName("a b"), "不能有空格");
		assertFalse(MsgStore.isValidPlayerName("玩家"), "不能有中文");
	}

	// ------------------------------------------------------------------ 已读回执展示

	@Test
	@DisplayName("已读回执：未读时显示未读提示，读过之后显示已读时间")
	void readReceipt() {
		MsgEntry m = entry(1L, "Alice", "hi");

		String before = plainText(ChatUtil.detail(m, !m.read));
		assertTrue(before.contains("（已标记为已读）"), "首次点开应提示已标记为已读：" + before);
		assertTrue(before.contains("hi"), "必须包含留言正文：" + before);

		m.read = true;
		m.readAt = System.currentTimeMillis();

		String after = plainText(ChatUtil.detail(m, false));
		assertFalse(after.contains("（已标记为已读）"), "再次查看不应重复提示");

		assertNotNull(m.formattedReadTime(), "已读时间应可格式化");
		assertNull(entry(2L, "Alice", "x").formattedReadTime(), "未读应没有已读时间");
	}
}
