package com.msgboard.gametest;

import java.util.Properties;
import java.util.UUID;

import com.msgboard.MessageBoardMod;
import com.msgboard.data.MsgEntry;
import com.msgboard.data.MsgStore;
import com.msgboard.delivery.DeliveryService;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;

/**
 * 端到端验证：真实 26.3 客户端连上真实专用服务端后，
 * 「给离线玩家留言 → 对方上线收到绿色可点击提示」这一整条链路。
 *
 * <p>断言的对象是 {@link CapturedChat} 从客户端网络层抄下来的**原始组件**，
 * 即玩家真正收到的数据，而不是服务端自己拼出来的等价物。
 *
 * <p>运行方式：{@code gradlew runClientGameTest}（需要能创建真实窗口的桌面环境）。
 */
@SuppressWarnings("UnstableApiUsage")
public class MessageBoardClientGameTest implements FabricClientGameTest {
	/** 发留言的玩家：从没上线过，用来走「离线留言」分支。 */
	private static final String SENDER = "OfflineSender";
	private static final String CONTENT = "明天八点一起打末影龙";

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("断言失败：" + message);
		}
	}

	@Override
	public void runTest(ClientGameTestContext context) {
		Properties props = new Properties();
		props.setProperty("online-mode", "false");
		props.setProperty("level-type", "minecraft:flat");
		props.setProperty("spawn-protection", "0");
		props.setProperty("max-players", "5");

		try (TestDedicatedServerContext server = context.worldBuilder()
				.setUseConsistentSettings(false)
				.createServer(props);
				TestDedicatedServerConnection connection = server.connect()) {
			try {
				connection.waitForChunksRender();
			} catch (Throwable t) {
				System.out.println("[E2E] waitForChunksRender 未完成（继续）：" + t);
			}

			String clientName = context.computeOnClient(
					mc -> mc.player == null ? null : mc.player.getGameProfile().name());

			System.out.println("[E2E] 客户端玩家名 = " + clientName);
			check(clientName != null, "客户端应已进入世界");

			// ---------------------------------------------------------- 1. 离线留言
			MsgStore store = MessageBoardMod.store();
			check(store != null, "服务端应已加载留言板数据");

			MsgEntry entry = server.computeOnServer(s -> store.addMessage(
					UUID.randomUUID(), SENDER, null, clientName, CONTENT));

			System.out.println("[E2E] 已种下留言 id=" + entry.id + " 收件人=" + entry.recipientName);
			check(!entry.delivered, "刚种下的留言不应是已投递状态");

			// ---------------------------------------------------------- 2. 上线投递
			CapturedChat.clear();

			server.runOnServer(s -> {
				ServerPlayer p = s.getPlayerList().getPlayer(clientName);
				check(p != null, "服务端应能找到玩家 " + clientName);

				// 与 DeliveryService 的 JOIN 回调走完全相同的投递函数。
				DeliveryService.deliverNow(p, entry, store);
			});

			try {
				context.waitFor(mc -> CapturedChat.firstContaining("向你") != null, 200);
			} catch (Throwable t) {
				System.out.println("[E2E] 没等到提示。已捕获 " + CapturedChat.messages().size() + " 条：");

				for (Component c : CapturedChat.messages()) {
					System.out.println("   - " + CapturedChat.flatten(c));
				}

				throw new AssertionError("客户端没有收到留言提示", t);
			}

			Component notice = CapturedChat.firstContaining("向你");
			String text = CapturedChat.flatten(notice);
			System.out.println("[E2E] 客户端收到提示：" + text);

			check(text.contains("[" + SENDER + "]"), "提示里应包含发送者名：" + text);
			check(text.contains("向你"), "提示里应包含「向你」：" + text);
			check(text.contains("留言"), "提示里应包含「留言」：" + text);

			Component link = findRunCommand(notice);
			check(link != null, "提示里应有一个 run_command 可点击节点");
			check("留言".equals(link.getString()), "可点击节点文字应正好是「留言」，实际=" + link.getString());
			check(TextColor.GREEN.equals(link.getStyle().getColor()),
					"「留言」必须是绿色，实际=" + link.getStyle().getColor());
			check(link.getStyle().getClickEvent() instanceof ClickEvent.RunCommand, "点击动作必须是 run_command");
			check(link.getStyle().getHoverEvent() != null, "应带鼠标悬停提示");

			String command = ((ClickEvent.RunCommand) link.getStyle().getClickEvent()).command();
			System.out.println("[E2E] 点击将执行：" + command);
			check(command.equals("/msgboard read " + entry.id), "点击应精确指向这条留言，实际=" + command);

			// ---------------------------------------------------------- 3. 投递状态与指令同步
			server.runOnServer(s -> check(entry.delivered, "投递后应标记为已投递"));

			boolean clientKnowsCommand = context.computeOnClient(
					mc -> mc.player != null && mc.player.connection.getCommands().getRoot().getChildren().stream()
							.anyMatch(node -> "msgboard".equals(node.getName())));
			check(clientKnowsCommand, "客户端应已同步到 /msgboard 指令");

			System.out.println("[E2E] 全部断言通过：客户端收到了正确的绿色可点击「留言」提示。");

			// 收尾交给框架：它要求先关掉服务端上下文再断开连接，
			// 资源顺序反了会报 "Disconnected from server before closing the test server connection"。
		}
	}

	/** 在组件树里找第一个 run_command 节点。 */
	private static Component findRunCommand(Component root) {
		Style style = root.getStyle();

		if (style != null && style.getClickEvent() instanceof ClickEvent.RunCommand) {
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
}
