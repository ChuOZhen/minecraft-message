# deepseek.md —— 「留言板」Fabric 服务端模组实施提示词

> 本文件既是**给 AI 的提示词**（可直接整份投喂），也是**本项目的实施规格**。
> 文末「已核实事实清单」里的版本号与 API 名均已在本机对 Minecraft **26.3** 实测通过，
> 照抄即可编译，不要凭记忆替换成 1.21.x 时代的 Yarn 名称。

---

## 0. 一句话目标

做一个 **Minecraft Java 版 26.3 + Fabric、只需要装在服务端** 的模组「留言板」：

1. 玩家 1 输入 `/msgboard send <玩家2> <内容>`，给（多半不在线的）玩家 2 留言；
2. 玩家 2 **下次进入服务器**时，聊天栏出现一行提示：
   `[留言板] [玩家1] 向你 留言。`
   其中**「留言」两个字是绿色、可点击**；鼠标悬停显示提示，**点击后立即显示留言内容**；
3. OP 用 `/messagename <玩家名>` 把玩家登记进「名称册子」；
4. 「名称册子」里的人和当前在线玩家的名字，在 `/msgboard send ` 后按 **Tab 键即可补全**；
5. 留言要**跨服务器重启保留**；
6. 发送者可以用 `/msgboard sent` 查看自己发出的留言，以及**对方是否已读（已读回执）**。

---

## 1. 需求确认表（已与需求方确认，不要再自行更改）

| 项目 | 决定 |
| --- | --- |
| 游戏版本 | Minecraft Java Edition **26.3**（官方名 Wilderness Bound） |
| 模组加载器 | **Fabric**（Loader 0.19.5 / Fabric API 0.161.0+26.3） |
| 安装位置 | **纯服务端模组**，玩家客户端**不需要**装任何东西 |
| 主指令 | **`/msgboard`**（刻意避开原版 `/message`、`/msg`、`/tell`、`/w`，不覆盖原版私聊） |
| 名册指令 | `/messagename <玩家名>`，**需要 OP（权限等级 2 = GAMEMASTERS）** |
| 名册来源 | **仅 OP 手动添加**；玩家进服不自动入册（但上线时会自动补上其 UUID） |
| 持久化 | **存档目录 JSON**：`<世界存档目录>/msgboard.json` |
| 上线提示形态 | **按发送者合并成一行**，每个发送者一个可点击的「留言」 |
| 附加功能 | **已读回执**（`/msgboard sent`） |
| 不做 | 在线即时推送以外的通知渠道、GUI 界面、跨服同步、留言附件 |

---

## 2. 已核实事实清单（**照抄，勿凭记忆改写**）

这些是在本机对 26.3 实测/反查得到的结果。**26.x 与 1.21.x 的最大差别**：
Minecraft 自 **26.1** 起源码**不再混淆**，Fabric **已弃用 Yarn 映射**
（见 [Fabric 公告](https://fabricmc.net/2025/10/31/obfuscation.html) 与
[映射迁移文档](https://docs.fabricmc.net/develop/porting/mappings/)）。
所以：

* `build.gradle` 里**没有** `mappings` 行，**不要**写 `yarn_mappings`；
* 类名是 **Mojang 官方名**：`Component`（不是 `Text`）、`ServerPlayer`（不是 `ServerPlayerEntity`）、
  `CommandSourceStack`（不是 `ServerCommandSource`）、`ClickEvent.RunCommand`（不是 `ClickEvent$RunCommandAction`）。

### 2.1 构建坐标（来自官方示例模组 26.3 分支，已验证可编译）

```properties
minecraft_version=26.3
loader_version=0.19.5
loom_version=1.18-SNAPSHOT
fabric_api_version=0.161.0+26.3
```

* Loom 插件 ID 是 **`net.fabricmc.fabric-loom`**（不是旧的 `fabric-loom`）；
* Java 版本 **25**（26.3 要求 Java SE 25，`options.release = 25`，`sourceCompatibility/targetCompatibility = VERSION_25`）；
* Gradle wrapper **9.7.1**。

### 2.2 26.3 的关键 API（易错点，已逐一用 `javap` 反查）

| 用途 | **正确写法（26.3）** | 1.21.x 的旧写法（**会编译失败**） |
| --- | --- | --- |
| 权限判断（指令树） | `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` | `source -> source.hasPermission(2)` |
| 权限判断（代码里） | `Commands.LEVEL_GAMEMASTERS.check(source.permissions())` | `source.hasPermission(2)` |
| 权限等级常量 | `Commands.LEVEL_ALL / LEVEL_MODERATORS / LEVEL_GAMEMASTERS / LEVEL_ADMINS / LEVEL_OWNERS` | 整数 0/1/2/3/4 |
| 在线玩家名列表 | `server.getPlayerList().getPlayerNamesArray()` | `getOnlinePlayerNames()`（在 `PlayerList` 上已不存在） |
| 按名查在线玩家 | `server.getPlayerList().getPlayer(String)` / `getPlayerByName(String)` | 同名 |
| 名字→UUID（本地缓存） | `server.services().nameToIdCache().get(name)` → `Optional<NameAndId>`，取 `.id()` | `server.getProfileCache().get(name)`（**26.3 已无此方法**） |
| 名字→UUID（联网查询） | `server.services().profileResolver().fetchByName(name)` → `Optional<GameProfile>` | — |
| 权限集类型 | `net.minecraft.server.permissions.PermissionSet` / `PermissionCheck` / `PermissionLevel` | 整数权限等级 |

### 2.3 仍然有效的常用 API

```java
// 注册指令
CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> { ... });

// 服务器生命周期（存/取世界目录）
ServerLifecycleEvents.SERVER_STARTING.register(server -> { ... });
ServerLifecycleEvents.SERVER_STOPPING.register(server -> { ... });
server.getWorldPath(new LevelResource("msgboard.json"))   // -> 世界存档目录下的路径

// 玩家进入服务器
ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
    ServerPlayer player = handler.getPlayer();
    UUID id = player.getUUID();
    String name = player.getGameProfile().name();
});

// 发消息（不广播）
player.sendSystemMessage(component);
source.sendSuccess(() -> component, false);
source.sendFailure(component);

// 文本与交互
Component.literal("x").withStyle(ChatFormatting.GRAY)
Component.empty().append(...)
Component.translatable("key")
Style.EMPTY.withColor(ChatFormatting.GREEN)
      .withClickEvent(new ClickEvent.RunCommand("/msgboard read 3"))
      .withClickEvent(new ClickEvent.SuggestCommand("/msgboard send "))
      .withHoverEvent(new HoverEvent.ShowText(Component.literal("提示")));

// 指令参数
StringArgumentType.word()      // 单字（玩家名）
StringArgumentType.greedyString()  // 剩余全部（留言内容，可含空格）
LongArgumentType.longArg(1L)   // ID / 页码，最小 1
.suggests(SuggestionProvider<CommandSourceStack>)  // Tab 补全
```

### 2.4 环境约束（本机实测）

* `raw.githubusercontent.com` 与 `cdn.jsdelivr.net` 在本机**被拦截**（SSL 失败）；
  需要读 GitHub 上的文件时走 **`https://api.github.com/repos/<owner>/<repo>/contents/<path>?ref=<ref>`**（base64 解码）。
  `maven.fabricmc.net`、`services.gradle.org`、`api.github.com` 正常。
* `java` 在 PATH 上，**`javap` 不在 PATH**：用 `C:\Program Files\Java\jdk-25.0.3\bin\javap.exe`。

---

## 3. 需要交付的项目

在项目根目录（`C:\Users\XSDC\Desktop\deepseek-harness\message`）生成：

```
msgboard/
├── build.gradle                     # Loom + Java 25 + 无 mappings 行
├── settings.gradle                  # rootProject.name = 'msgboard'
├── gradle.properties                # 第 2.1 节的坐标
├── gradlew / gradlew.bat
├── gradle/wrapper/gradle-wrapper.{jar,properties}   # Gradle 9.7.1
├── README.md                        # 给服主看的安装/使用说明
└── src/
    ├── main/
    │   ├── java/com/msgboard/
    │   │   ├── MessageBoardMod.java              # 入口：注册生命周期/事件/指令
    │   │   ├── command/
    │   │   │   ├── MessageCommand.java           # /msgboard send|read|sent|names|help
    │   │   │   └── NameBookCommand.java          # /messagename [name] | remove | list
    │   │   ├── data/
    │   │   │   ├── MsgData.java                  # msgboard.json 根对象
    │   │   │   ├── MsgEntry.java                 # 单条留言
    │   │   │   ├── NameEntry.java                # 名册项
    │   │   │   └── MsgStore.java                 # 载入/保存/查询 + 原子写
    │   │   ├── delivery/
    │   │   │   └── DeliveryService.java          # 上线投递
    │   │   └── util/
    │   │       ├── ChatUtil.java                 # 所有绿色可点击文本
    │   │       └── NameSuggestions.java          # Tab 补全（名册 + 在线）
    │   └── resources/
    │       ├── fabric.mod.json
    │       ├── msgboard.mixins.json              # 空 mixin 配置（占位）
    │       └── assets/msgboard/icon.png
    └── client/
        ├── java/com/msgboard/client/MessageBoardClient.java   # 客户端入口（空实现）
        └── resources/msgboard.client.mixins.json
├── src/test/java/com/msgboard/ChatUtilTest.java   # 核心验收点自动化测试（9 个用例）
└── src/gametest/                                  # 端到端客户端游戏测试（见第 9.2 节）
    ├── java/com/msgboard/gametest/
    │   ├── MessageBoardClientGameTest.java        # 真客户端 + 真服务端跑完整链路
    │   ├── CapturedChat.java                      # 客户端真实收到的组件
    │   └── mixin/ClientPacketListenerMixin.java   # 从 handleSystemChat 抄包
    └── resources/fabric.mod.json                  # fabric-client-gametest 入口点
```

> `build.gradle` 使用 `loom { splitEnvironmentSourceSets() }`，因此必须有 `src/client`，
> 否则 `fabric.mod.json` 里的 `client` 入口点会解析失败。

---

## 4. 数据结构（`msgboard.json`）

```jsonc
{
  "dataVersion": 1,
  "nextId": 4,
  "messages": [
    {
      "id": 3,
      "sender": "069a79f4-44e9-4726-a5be-fca90e38aaf5",   // 发送者 UUID
      "senderName": "Alice",
      "recipient": null,                                   // 收件人 UUID；名册未绑定时为 null
      "recipientName": "Bob",                              // 名册里的名字（权威匹配字段）
      "content": "明天八点一起打末影龙",
      "createdAt": 1791187200000,
      "delivered": false,                                  // 是否已给对方显示过提示
      "read": false,                                       // 是否已点开查看
      "readAt": 0                                          // 点开时间，未读为 0
    }
  ],
  "names": [
    { "name": "Bob", "uuid": null, "addedAt": 1791180000000 }
  ]
}
```

**收件人匹配规则**（`MsgStore.matchesRecipient`）：

1. `recipient != null` → 用 UUID 匹配（`equalsIgnoreCase`，忽略大小写）；
2. `recipient == null` → 用 `recipientName` 与当前玩家名比对（忽略大小写）。

这样 OP 只登记了名字、尚未绑定 UUID 时，留言依然能在对方上线时送出。

**原子写**：先写 `msgboard.json.tmp`，再 `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`
（原子移动不支持时降级为普通 `REPLACE_EXISTING`），避免服务器崩溃写坏存档。
读取失败时把原文件改名成 `.bak` 并退回空数据，**不允许让服务器启动失败**。

---

## 5. 指令规格

### 5.1 `/msgboard`（所有玩家可用）

| 指令 | 权限 | 行为 |
| --- | --- | --- |
| `/msgboard`、`/msgboard help` | 所有人 | 打印用法；`send`/`sent`/`names` 做成**可点击**（`SuggestCommand`/`RunCommand`） |
| `/msgboard send <目标> <内容>` | 所有人 | 留言。`<目标>` 用 `StringArgumentType.word()` + 自定义补全；`<内容>` 用 `greedyString()` |
| `/msgboard read <ID>` | 留言收件人本人 | 展示该条留言内容并标记已读。**这是聊天栏绿色「留言」的点击目标** |
| `/msgboard sent [页码]` | 所有人 | 列出自己发出的留言（最新在前，每页 8 条），带投递/已读状态 |
| `/msgboard names` | 所有人 | 只读查看名称册子 |

`send` 的校验顺序：

1. 必须是玩家执行 → `source.getPlayerOrException()`；
2. 目标名合法性 `^[A-Za-z0-9_]{3,16}$`，否则失败；
3. 内容去空白后不能为空；
4. 内容超 **200** 字截断并提示；
5. **不能给自己留言**；
6. 冷却 **3 秒/人**（内存 `Map<UUID, Long>`），过快则提示剩余秒数；
7. 目标**在线** → 立刻用同款可点击消息送达并标记 `delivered`，回执「已把留言发给在线的 X」；
8. 目标**离线** → 落盘等待，回执「已给 X 留言（当前有 N 条待接收），他上线时会看到提示」。

> 允许给**不在名册里**的玩家留言（按名字记录），对方上线时依旧能收到。
> 这样 OP 忘了登记也不会丢留言；名册的作用是 **Tab 补全 + 提前绑定 UUID**。

`read` 的校验顺序：

1. 数据已加载；2. 能按 ID 找到留言；3. 执行者是玩家；
4. **归属校验**：`matchesRecipient` 为真，否则失败「这条留言不是给你的」（防止点击别人的链接偷看）；
5. 展示内容；6. 标记 `read = true` 并写入 `readAt`；
7. 上线提示只给同组**最小 ID** 的链接，所以点开时要把「**同一发送者 + 已投递 + 尚未读 + 收件人是我**」
   的留言一并展示并一起标记已读（用 `store.sentBy(entry.senderUuid())` 显式取集合，
   **不要**用 `pendingFor` 反向推断，语义会随实现变化而失效）；
8. 控制台/命令方块调用时只打印，不做归属与已读处理。

### 5.2 `/messagename`（仅 OP）

| 指令 | 行为 |
| --- | --- |
| `/messagename <玩家名>` | 加入名册；已存在则失败并提示。成功后广播给 OP（`sendSuccess(..., true)`） |
| `/messagename remove <玩家名>` | 移出名册（**已有留言不受影响**，仍会在对方上线时送达） |
| `/messagename list` | 列出名册，每人显示 `[已绑定 UUID]` 或 `[待上线绑定]`，并带可点击 `[移除]` |

整个指令树 `.requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))`。

加名册时解析 UUID 的顺序：

1. 在线玩家 → `player.getUUID()`；
2. `server.services().nameToIdCache().get(name)`（本地缓存）；
3. `server.services().profileResolver().fetchByName(name)`（联网查询，`try/catch` 兜住所有异常）。

三步都拿不到 → `uuid = null`，回复「暂未解析到 UUID，等他上线会自动补上」。
**不要**因为联网失败就让指令报错。

---

## 6. Tab 补全

自定义 `SuggestionProvider<CommandSourceStack>`，候选集合 = **名称册子里的名字 ∪ 当前在线玩家名**：

```java
@Override
public CompletableFuture<Suggestions> getSuggestions(CommandContext<CommandSourceStack> ctx,
        SuggestionsBuilder builder) {
    for (String name : candidates(ctx.getSource().getServer())) {
        builder.suggest(name);      // Brigadier 会自己按已输入前缀过滤
    }
    return builder.buildFuture();
}
```

去重（`LinkedHashSet`，忽略大小写）、按 `String.CASE_INSENSITIVE_ORDER` 排序。
同一个 provider 同时用在 `/msgboard send <目标>`、`/messagename <名>`、`/messagename remove <名>`。

**注意**：Tab 补全要「弹出候选」，所以目标参数必须是**单个词**参数（`word()`），
不能写成 `greedyString()`，否则补全候选不会出现。

---

## 7. 上线投递与提示文案（视觉规格）

`ServerPlayConnectionEvents.JOIN` 里：

1. 若名册中有这个名字但 `uuid == null` → 用当前玩家的 UUID 回填（同时写盘）；
2. 取 `pendingFor(uuid, name)`（`delivered == false` 且收件人匹配）；
3. 为空 → 什么都不做；
4. 非空 → 发送**合并成一行**的提示，然后 `markDelivered(...)`（同时回填 UUID）。

按发送者 `senderName` 分组，同组只给**一个**绿色「留言」链接（指向组内 ID 最小的一条），
组内多于 1 条时在链接后显示灰字 `（N 条）`。

合并模板（`[留言板]` 灰、玩家名灰、**「留言」绿且可点**、其余灰）：

* 一条留言时：
  `[留言板] ` + `[玩家A] 向你 ` + **`留言`** + `。`
* 多条时：
  `[留言板] 你有 N 条新留言：` + `[玩家A] 向你 ` + **`留言`** + `（2 条）` + `、` + `[玩家B] 向你 ` + **`留言`** + `。`

「留言」这两个字的实现（**核心验收点**）：

```java
Component.literal("留言").withStyle(Style.EMPTY
        .withColor(ChatFormatting.GREEN)                                   // 绿色
        .withClickEvent(new ClickEvent.RunCommand("/msgboard read " + id)) // 点击立即展开
        .withHoverEvent(new HoverEvent.ShowText(
                Component.literal("点击查看 " + senderName + " 的留言"))));
```

用 `RunCommand` 而不是 `SuggestCommand`：点击后**直接显示内容**，不把指令填进输入框、不会误发到公屏。
（`/msgboard help` 里的用法提示才用 `SuggestCommand`，方便玩家接着改参数。）

点开后的详情样式：

```
[留言板] 来自 Alice 的留言（2026-10-05 13:40）： （已标记为已读）   ← 灰色，"Alice" 青色
明天八点一起打末影龙                                              ← 白色，另起一行
```

`/msgboard sent` 每行样式：

```
 #3 → Bob 2026-10-05 13:40 [已读 2026-10-05 14:02]   ← 已读=绿色
 明天八点一起打末影龙                                 ← 正文白色，另起一行
```

状态三态：`[已读 <时间>]`（绿）/ `[已送达未读]`（黄）/ `[未送达]`（灰）。

---

## 8. 文本与安全约束

* 用 `Component.literal(...)` 包裹所有**玩家可控内容**（玩家名、留言正文），
  不要拼接成裸字符串发给客户端，避免换行/格式符注入造成显示错乱；
* 收名字与正文时统一 `replaceAll("§.", "")` 去掉颜色符号；
* 正文压成单行：`replaceAll("\\s*\\R\\s*", " ")`（该版本指令不支持直接换行）；
* 因为「点击行为」是固定前缀的 `/msgboard read <数字>`，玩家内容永远进不了指令文本，
  不存在指令注入路径。

---

## 9. 构建与验证（必须实际执行）

```powershell
# 1. 编译 + 出包
.\gradlew.bat --no-daemon --console=plain build
# 期望：BUILD SUCCESSFUL
# 产物：build/libs/msgboard-1.0.0.jar（另有 -sources.jar）

# 2. 真机冒烟测试（Loom 的 runServer，RCON 已开）
#    run-server/eula.txt 写 eula=true，run-server/server.properties 写：
#    online-mode=false / enable-rcon=true / rcon.port=25585 / rcon.password=test
.\gradlew.bat --no-daemon --console=plain runServer
```

> `build.gradle` 里给 server / client 指定了**互相独立**的 runDir
> （`run-server` 与 `run-client`）。这不是洁癖：两者默认都用 `run/` 时，
> 先启动的那个会持有 `run/logs/latest.log`，后启动的进程会刷出
> `Unable to delete file ...latest.log` 并丢掉自己的日志，排查问题时会很痛苦。

`runServer` 起来后核对：

1. 日志出现 `[留言板] 已加载，共 0 条留言、0 个名册玩家。` → 模组加载成功、`msgboard.json` 已生成；
2. 通过 RCON 执行 `/messagename TestPlayer` → 名册新增。
   若该名字在 `usercache.json` / `nameToIdCache` 里已有记录，会直接显示「已绑定 UUID」；
   查不到时才会回复「暂未解析到 UUID，等他上线会自动补上」；
3. 执行 `/messagename list` → 显示 `TestPlayer [已绑定 UUID] [移除]`（未绑定时为 `[待上线绑定]`）；
4. 执行 `/msgboard help` → 打印用法；
5. 停机重启后 `run-server/world/msgboard.json` 内容仍在 → 持久化生效。

**验证记录**（本项目已实际执行）：

| 验证项 | 结果 |
| --- | --- |
| `gradlew build` | BUILD SUCCESSFUL，产出 `build/libs/msgboard-1.0.0.jar` |
| `gradlew test`（9 个用例） | 9/9 PASSED |
| 真实 26.3 服务端加载模组 | 日志出现 `[留言板] 已加载，共 0 条留言、0 个名册玩家。` |
| RCON 执行 `msgboard help` | 正常打印用法 |
| RCON 执行 `messagename TestPlayer` | 名册新增，并从 usercache 成功解析到 UUID |
| RCON 执行 `messagename list` | 显示 `TestPlayer [已绑定 UUID] [移除]` |
| 落盘 | `run-server/world/msgboard.json` 生成且内容正确 |
| 真实客户端端到端（`gradlew runClientGameTest`） | BUILD SUCCESSFUL：客户端真实收到的组件含 `[OfflineSender] 向你 留言`，绿色且点击指向 `/msgboard read 1` |

> RCON 验证用的临时脚本是 `rcon.ps1`（仅开发调试用；密码 `test` 只对应 `run-server/server.properties`
> 中开启的本地开发服务端）。**上线前请删掉它**，别把开发配置带到生产环境。

> **仍未覆盖**：`/msgboard read` 的「以收件人身份执行」这一步。
> 它需要第二个玩家连接才能触发，我用 RCON 试过 `execute as <玩家> run ...` 但没有成功。
> 归属校验与已读标记的逻辑本身由单元测试覆盖（`matchesRecipient`、`markRead` 相关分支），
> 但「真人点击 → 聊天栏出现留言正文 → 发送者看到已读」建议上线前手测一次。

> 提示：`gradlew runServer` 之后 `run-server/` 目录里也会有 `logs/`、`world/` 与 `msgboard.json`，
> 便于直接查看落盘结果。验证完可删掉 `run-server/` 与 `run-client/`（它们都不是交付物）。

**跨版本/跨映射的兜底做法**：若某个 API 名报「找不到符号」，
优先用 `javap` 反查而不是猜：

```powershell
& "C:\Program Files\Java\jdk-25.0.3\bin\javap.exe" -p `
  -classpath "$env:USERPROFILE\.gradle\caches\fabric-loom\26.3\minecraft-merged.jar" `
  net.minecraft.server.players.PlayerList
```

### 9.1 自动化测试（已随项目交付，`gradlew test` 9/9 通过）

`src/test/java/com/msgboard/ChatUtilTest.java` 用 **Fabric Loader JUnit**（`net.fabricmc:fabric-loader-junit`）
在真实 Minecraft 文本组件上断言核心验收点，**不需要开客户端**：

```groovy
testImplementation "net.fabricmc:fabric-loader-junit:${project.loader_version}"
testImplementation platform('org.junit:junit-bom:5.11.4')
testImplementation 'org.junit.jupiter:junit-jupiter'
testRuntimeOnly 'org.junit.platform:junit-platform-launcher'

test { useJUnitPlatform() }
```

覆盖点：

* 「留言」文字正好是两个字、颜色等于 `TextColor.GREEN`、`ClickEvent.RunCommand` 的
  `command()` 精确等于 `/msgboard read <id>`、且带 `HoverEvent.ShowText`；
* 单发送者 / 同发送者多条（只给一个入口 + 标注条数 + 指向最小 ID）/ 多发送者的合并文案；
* 收件人匹配的 UUID 优先与名字回退；
* 输入清洗与玩家名合法性；
* 已读回执的展示分支。

> **注意 26.3 的坑**：`ChatFormatting` **没有** `getColor()` 了，
> 断言颜色要用 `TextColor.fromLegacyFormat(ChatFormatting.GREEN)` 或 `TextColor.GREEN`
> 与 `style.getColor()` 比较（`style.getColor()` 返回的是 `TextColor`）。

### 9.2 端到端客户端游戏测试（`gradlew runClientGameTest`）

单元测试验证的是「组件造得对不对」，但「玩家进服后**实际收到**了什么」只能在真客户端里看。
Fabric 官方提供了客户端游戏测试框架，本项目管理方式如下：

```groovy
fabricApi {
	configureTests {
		createSourceSet = true
		modId = "msgboard-test"
		enableGameTests = false
		enableClientGameTests = true
		eula = true   // 同意 Minecraft EULA，仅用于本地自动化测试
	}
}
```

它会生成 `gametest` 源集与 `runClientGameTest` 任务，测试代码放
`src/gametest/java/**`，入口点在 `src/gametest/resources/fabric.mod.json` 的
**`fabric-client-gametest`** 里声明。

测试做的事（`MessageBoardClientGameTest`）：

1. `context.worldBuilder().createServer(props)` 起一个专用服务端
   （`online-mode=false`、超平坦、关出生点保护），`server.connect()` 让真客户端连上去；
2. 用 `computeOnServer` 直接以「从未上线的玩家 OfflineSender」身份写入一条留言，
   即走**离线留言**分支；
3. 用 `runOnServer` 在服务端取出该玩家的 `ServerPlayer`，调用与
   `DeliveryService` 的 JOIN 回调**完全相同的投递函数**；
4. 用 `context.waitFor(...)` 等这条消息真的抵达客户端；
5. 断言客户端收到的**原始组件**。

**怎么拿到「客户端真实收到的组件」**：`ClientPacketListener.handleSystemChat` 的
`@Inject(at = @At("HEAD"))` mixin（`src/gametest/java/.../mixin/ClientPacketListenerMixin.java`）
把 `ClientboundSystemChatPacket.content()` 抄一份到 `CapturedChat`。
断言的对象因此是客户端网络层收到的组件本身（含颜色与点击事件），
而不是测试自己拼出来的等价物。

> 两个容易踩的坑：
> 1. `server.connect()` 的返回类型要写成 **`TestDedicatedServerConnection`**（它是 `AutoCloseable`），
>    写成 `TestServerConnection` 会编译不过；
> 2. 收尾必须**先关服务端上下文、再断开连接**，否则框架会报
>    `Disconnected from server before closing the test server connection`。
>    把 `TestDedicatedServerContext` 放在 try-with-resources 的资源列表**前面**即可。

---

## 10. 部署与使用说明（写进 README.md）

**服务端部署**（玩家**无需**装模组）：

1. 服务端装 **Fabric Loader 0.19.5+**（Minecraft **26.3**，Java **25**）；
2. 把 `fabric-api-0.161.0+26.3.jar` 和 `msgboard-1.0.0.jar` 一起丢进 `mods/`；
3. 重启服务端即可。

**日常使用**：

```
/messagename Alice              # OP：把 Alice 登记进名称册子
/msgboard send Al<Tab> 明天八点打末影龙     # 目标名 Tab 补全
/msgboard sent                  # 看自己发出的留言与已读状态
/msgboard names                 # 看名册
```

**玩家体验**：被留言的玩家下次进入服务器时，聊天栏出现
`[留言板] [Alice] 向你 留言。` —— 点击绿色的「留言」立刻看到内容。

**数据备份**：删掉/回滚 `<世界存档>/msgboard.json` 即可清空全部留言与名册。

---

## 11. 验收清单

### 已自动验证（无需人工，共 3 条命令）

- [x] `gradlew build` 通过，产出 `build/libs/msgboard-1.0.0.jar`；
- [x] `gradlew test` 9/9 通过：绿色、可点击、指向正确 ID、合并文案、归属匹配、输入清洗、已读分支；
- [x] `gradlew runClientGameTest` 通过：真实 26.3 客户端 + 真实专用服务端，客户端**真实收到**
      `[留言板] [OfflineSender] 向你 留言。`，且经断言确认：
      该节点文字正好是「留言」、颜色为绿、点击事件为 `/msgboard read 1`、带悬停提示，
      并且客户端已同步到 `/msgboard` 指令；
- [x] 真服务端加载模组（`[留言板] 已加载，共 0 条留言、0 个名册玩家。`）并生成 `msgboard.json`；
- [x] RCON 下 `/messagename <名>` 入册并能解析 UUID、`/messagename list`、`/msgboard help` 均正常。

> 关于「上线投递」这条链路的验证边界，说清楚一点：
> E2E 测试调用的是 `DeliveryService.deliverNow(...)`，**与 JOIN 回调内部使用的是同一个函数**，
> 所以「提示造得对不对、客户端收得到收不到」是真实验证过的；
> 但**有没有真的由 `ServerPlayConnectionEvents.JOIN` 触发**没有单独断言，
> 只由「注册该回调后服务端能正常跑起来、玩家能进服」间接佐证。

### 需要人工确认（自动化覆盖不到的部分）

- [ ] **纯净客户端**（不带任何模组）进服能收到提示 —— E2E 用的是开发客户端，正式环境建议再跑一次；
- [ ] `/msgboard read` 以**收件人身份**执行：真人点击绿色「留言」→ 聊天栏出现留言正文
      → 发送者用 `/msgboard sent` 看到 `[已读 <时间>]`。
      这一步需要第二个玩家连接，我试过用 RCON 的 `execute as <玩家> run ...` 但没有成功，
      因此是本项目唯一没有机器验证的环节（归属校验与已读标记的逻辑本身由单元测试覆盖）；
- [ ] Tab 补全在真人客户端上的按键体验（候选来自名册 + 在线玩家，逻辑已实现但未做按键级测试）；
- [ ] 非 OP 执行 `/messagename` 被拒绝（指令树 `.requires(...)` 已声明，未做权限级实测）；
- [ ] 正式存档的关服重启不丢数据（已在 `run-server` 观察过落盘文件，正式环境建议再确认）。

### 设计上已保证、无需实测的项

- **点击不会误发到公屏**：用的是 `ClickEvent.RunCommand` 而不是 `SuggestCommand`，
  点击直接执行指令，不经过输入框；
- **无指令注入路径**：点击目标是固定前缀的 `/msgboard read <数字>`，
  玩家可控内容（玩家名、留言正文）永远进不了指令文本；
- **点别人的链接偷看**：`read` 里有 `matchesRecipient` 归属校验；
- **数据写坏**：原子替换写入 + 读取失败改名 `.bak` 并退回空数据，不影响服务器启动。
