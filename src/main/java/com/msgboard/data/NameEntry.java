package com.msgboard.data;

import com.google.gson.annotations.SerializedName;

/**
 * 名称册子中的一项。
 */
public final class NameEntry {
	/** 玩家名（保留 OP 输入时的大小写）。 */
	@SerializedName("name")
	public String name;

	/** 已知 UUID 的字符串形式；未知为 null。 */
	@SerializedName("uuid")
	public String uuid;

	/** 加入册子的时间（epoch millis）。 */
	@SerializedName("addedAt")
	public long addedAt;

	/** 记录此玩家的 UUID（首次上线 / 加白名单时解析到）。 */
	public void setUuid(java.util.UUID id) {
		this.uuid = id == null ? null : id.toString();
	}

	public NameEntry() {
	}

	public NameEntry(String name) {
		this.name = name;
		this.addedAt = System.currentTimeMillis();
	}
}
