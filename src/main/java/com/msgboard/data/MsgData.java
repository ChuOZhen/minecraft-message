package com.msgboard.data;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.annotations.SerializedName;

/**
 * 留言板持久化数据结构（对应 msgboard.json）。
 */
public final class MsgData {
	/** 数据格式版本，便于以后升级迁移。 */
	@SerializedName("dataVersion")
	public int dataVersion = 1;

	/** 自增留言 ID。 */
	@SerializedName("nextId")
	public long nextId = 1;

	/** 全部留言。 */
	@SerializedName("messages")
	public List<MsgEntry> messages = new ArrayList<>();

	/** 名称册子（OP 用 /messagename 维护）。 */
	@SerializedName("names")
	public List<NameEntry> names = new ArrayList<>();
}
