package com.msgboard.data;

import java.util.UUID;

import com.google.gson.annotations.SerializedName;

/**
 * 一条留言。
 */
public final class MsgEntry {
	@SerializedName("id")
	public long id;

	/** 发送者 UUID（字符串形式）。 */
	@SerializedName("sender")
	public String sender;

	/** 发送者名称（发送时的快照）。 */
	@SerializedName("senderName")
	public String senderName;

	/** 收件人 UUID；名册里只有名字时为 null，等该玩家上线后自动回填。 */
	@SerializedName("recipient")
	public String recipient;

	/** 收件人名称（名册中的名字）。 */
	@SerializedName("recipientName")
	public String recipientName;

	/** 留言正文。 */
	@SerializedName("content")
	public String content;

	/** 发送时间（epoch millis）。 */
	@SerializedName("createdAt")
	public long createdAt;

	/** 是否已给收件人显示过提示。 */
	@SerializedName("delivered")
	public boolean delivered;

	/** 收件人是否已点开查看。 */
	@SerializedName("read")
	public boolean read;

	/** 点开查看的时间（epoch millis），未读为 0。 */
	@SerializedName("readAt")
	public long readAt;

	public MsgEntry() {
	}

	public MsgEntry(long id, UUID sender, String senderName, UUID recipient, String recipientName, String content) {
		this.id = id;
		this.sender = sender == null ? null : sender.toString();
		this.senderName = senderName;
		this.recipient = recipient == null ? null : recipient.toString();
		this.recipientName = recipientName;
		this.content = content;
		this.createdAt = System.currentTimeMillis();
	}

	public UUID senderUuid() {
		return parse(sender);
	}

	public UUID recipientUuid() {
		return parse(recipient);
	}

	private static UUID parse(String s) {
		if (s == null || s.isEmpty()) {
			return null;
		}

		try {
			return UUID.fromString(s);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	/** 时间戳格式化为 yyyy-MM-dd HH:mm。 */
	public String formattedTime() {
		return MsgStore.formatTime(createdAt);
	}

	/** 已读时间格式化为 yyyy-MM-dd HH:mm，未读返回 null。 */
	public String formattedReadTime() {
		return readAt <= 0 ? null : MsgStore.formatTime(readAt);
	}
}
