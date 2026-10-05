package com.msgboard.data;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.msgboard.MessageBoardMod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

/**
 * 留言板持久化存储：{@code <世界存档目录>/msgboard.json}。
 *
 * <p>写入使用「临时文件 + 原子替换」，避免服务器崩溃时把存档里的 JSON 写坏。
 * 所有读写都在一个锁内完成，可安全地从服务器主线程与网络线程调用。
 */
public final class MsgStore {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final DateTimeFormatter TIME_FORMAT =
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private static final LevelResource FILE = new LevelResource("msgboard.json");

	private final Path path;
	private final Object lock = new Object();
	private MsgData data = new MsgData();

	public MsgStore(MinecraftServer server) {
		this.path = server.getWorldPath(FILE);
	}

	public static String formatTime(long millis) {
		return TIME_FORMAT.format(Instant.ofEpochMilli(millis));
	}

	/** 只读快照（用于日志/统计）。 */
	public MsgData data() {
		synchronized (lock) {
			return data;
		}
	}

	public Path path() {
		return path;
	}

	// ------------------------------------------------------------------ 载入 / 保存

	public void load() {
		synchronized (lock) {
			if (!Files.exists(path)) {
				data = new MsgData();
				saveLocked();
				return;
			}

			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
				MsgData loaded = GSON.fromJson(reader, MsgData.class);
				data = normalize(loaded);
			} catch (Exception e) {
				MessageBoardMod.LOGGER.error("[留言板] 读取 {} 失败，将使用空数据（原文件已备份为 .bak）。",
						path, e);
				backupCorrupted();
				data = new MsgData();
			}
		}
	}

	public void save() {
		synchronized (lock) {
			saveLocked();
		}
	}

	private void saveLocked() {
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");

			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(data, writer);
			}

			try {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (IOException atomicFailed) {
				Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			MessageBoardMod.LOGGER.error("[留言板] 写入 {} 失败。", path, e);
		}
	}

	private void backupCorrupted() {
		try {
			Path bak = path.resolveSibling(path.getFileName() + ".bak");
			Files.move(path, bak, StandardCopyOption.REPLACE_EXISTING);
			MessageBoardMod.LOGGER.warn("[留言板] 损坏的数据已备份到 {}。", bak);
		} catch (IOException e) {
			MessageBoardMod.LOGGER.error("[留言板] 备份损坏数据失败。", e);
		}
	}

	/** 修复手工编辑 JSON 后可能出现的 null 字段。 */
	private static MsgData normalize(MsgData loaded) {
		MsgData d = loaded == null ? new MsgData() : loaded;

		if (d.messages == null) {
			d.messages = new ArrayList<>();
		}

		if (d.names == null) {
			d.names = new ArrayList<>();
		}

		if (d.dataVersion <= 0) {
			d.dataVersion = 1;
		}

		long maxId = 0;
		for (MsgEntry m : d.messages) {
			maxId = Math.max(maxId, m.id);
		}

		if (d.nextId <= maxId) {
			d.nextId = maxId + 1;
		}

		return d;
	}

	// ------------------------------------------------------------------ 留言

	/** 新建一条留言并落盘，返回该留言。 */
	public MsgEntry addMessage(UUID sender, String senderName, UUID recipient, String recipientName, String content) {
		synchronized (lock) {
			MsgEntry entry = new MsgEntry(data.nextId++, sender, senderName, recipient, recipientName, content);
			data.messages.add(entry);
			saveLocked();
			return entry;
		}
	}

	/** 某玩家尚未收到的全部留言（按时间先后）。 */
	public List<MsgEntry> pendingFor(UUID recipientId, String recipientName) {
		List<MsgEntry> out = new ArrayList<>();

		synchronized (lock) {
			for (MsgEntry m : data.messages) {
				if (m.delivered) {
					continue;
				}

				if (matchesRecipient(m, recipientId, recipientName)) {
					out.add(m);
				}
			}
		}

		return out;
	}

	/** 收件人匹配：先看 UUID，UUID 未知时退回名字（不区分大小写）。 */
	public static boolean matchesRecipient(MsgEntry m, UUID recipientId, String recipientName) {
		if (m.recipient != null) {
			return m.recipient.equalsIgnoreCase(String.valueOf(recipientId));
		}

		return m.recipientName != null && recipientName != null
				&& m.recipientName.equalsIgnoreCase(recipientName);
	}

	/**
	 * 标记为已投递，并把名册里缺失的收件人 UUID 回填。
	 * 只要有任何变化就写盘。
	 */
	public void markDelivered(List<MsgEntry> entries, UUID recipientId) {
		if (entries.isEmpty()) {
			return;
		}

		synchronized (lock) {
			boolean changed = false;

			for (MsgEntry m : entries) {
				if (!m.delivered) {
					m.delivered = true;
					changed = true;
				}

				if (m.recipient == null && recipientId != null) {
					m.recipient = recipientId.toString();
					changed = true;
				}
			}

			if (changed) {
				saveLocked();
			}
		}
	}

	/** 标记为已读（含已读时间）。返回是否发生了变化。 */
	public boolean markRead(UUID recipientId, String recipientName, List<Long> ids) {
		synchronized (lock) {
			boolean changed = false;
			long now = System.currentTimeMillis();

			for (MsgEntry m : data.messages) {
				if (!ids.contains(m.id) || !matchesRecipient(m, recipientId, recipientName)) {
					continue;
				}

				if (!m.read) {
					m.read = true;
					changed = true;
				}

				if (m.readAt <= 0) {
					m.readAt = now;
					changed = true;
				}
			}

			if (changed) {
				saveLocked();
			}

			return changed;
		}
	}

	/** 某发送者发出的全部留言，最新的在前。 */
	public List<MsgEntry> sentBy(UUID senderId) {
		List<MsgEntry> out = new ArrayList<>();

		synchronized (lock) {
			for (MsgEntry m : data.messages) {
				if (senderId.equals(m.senderUuid())) {
					out.add(m);
				}
			}
		}

		out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
		return out;
	}

	/** 按 ID 取留言，取不到返回 null。 */
	public MsgEntry byId(long id) {
		synchronized (lock) {
			for (MsgEntry m : data.messages) {
				if (m.id == id) {
					return m;
				}
			}
		}

		return null;
	}

	// ------------------------------------------------------------------ 名称册子

	/** 加入名册。 */
	public boolean addName(String name, UUID uuid) {
		synchronized (lock) {
			NameEntry existing = findNameLocked(name);
			boolean changed = false;

			if (existing == null) {
				NameEntry entry = new NameEntry(name);

				if (uuid != null) {
					entry.setUuid(uuid);
				}

				data.names.add(entry);
				changed = true;
			} else if (existing.uuid == null && uuid != null) {
				existing.setUuid(uuid);
				changed = true;
			}

			if (changed) {
				saveLocked();
			}

			return changed;
		}
	}

	/** 从名册移除。 */
	public boolean removeName(String name) {
		synchronized (lock) {
			boolean removed = data.names.removeIf(n -> n.name != null && n.name.equalsIgnoreCase(name));

			if (removed) {
				saveLocked();
			}

			return removed;
		}
	}

	public boolean hasName(String name) {
		synchronized (lock) {
			return findNameLocked(name) != null;
		}
	}

	/** 按名字查名册项（不区分大小写）。 */
	public NameEntry findName(String name) {
		synchronized (lock) {
			return findNameLocked(name);
		}
	}

	private NameEntry findNameLocked(String name) {
		if (name == null) {
			return null;
		}

		for (NameEntry n : data.names) {
			if (n.name != null && n.name.equalsIgnoreCase(name)) {
				return n;
			}
		}

		return null;
	}

	/** 名册中记录的 UUID，未知返回 null。 */
	public UUID uuidOf(String name) {
		NameEntry entry = findName(name);

		if (entry == null || entry.uuid == null || entry.uuid.isEmpty()) {
			return null;
		}

		try {
			return UUID.fromString(entry.uuid);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** 名册名字列表（副本）。 */
	public List<String> names() {
		List<String> out = new ArrayList<>();

		synchronized (lock) {
			for (NameEntry n : data.names) {
				if (n.name != null && !n.name.isEmpty()) {
					out.add(n.name);
				}
			}
		}

		return out;
	}

	/** 名册项数。 */
	public int nameCount() {
		synchronized (lock) {
			return data.names.size();
		}
	}

	/**
	 * 玩家名的合法性检查：仅允许 3-16 位字母、数字与下划线（与 Minecraft 账号名一致）。
	 */
	public static boolean isValidPlayerName(String name) {
		return name != null && name.matches("^[A-Za-z0-9_]{3,16}$");
	}

	/** 去掉名字里的 Minecraft 颜色符号与首尾空白。 */
	public static String sanitizePlayerName(String name) {
		if (name == null) {
			return "";
		}

		return name.replaceAll("§.", "").trim();
	}

	/** 把留言正文压成单行（该版本指令不允许直接换行）。 */
	public static String sanitizeContent(String content) {
		if (content == null) {
			return "";
		}

		return content.replaceAll("\\s*\\R\\s*", " ").replaceAll("§.", "").trim();
	}

	/** 用于排序/显示的小写键。 */
	public static String key(String name) {
		return name == null ? "" : name.toLowerCase(Locale.ROOT);
	}
}
