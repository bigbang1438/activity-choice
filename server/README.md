# 一起定：活动选择与定案分享后端

> 公开源码范围：本文保留原开发环境的实施与验证历史。提到的日志、截图、仓库外 QA/研究原始材料不在此公开目录；当前可用测试和质量限制见 [EVIDENCE 摘要](../EVIDENCE.md)。


这是一个供本机验证的 Java 全栈个人项目。目标任务是：三位朋友共同录入周末活动候选，分别表达偏好，由组织者确定主方案与备用方案，再把不含内部讨论的定案分享给无账号收件人。当前实现了这一流程和相应权限、并发、版本规则，尚没有真实用户试用或商业收益数据。

本轮从旧画板任务失败中选出三项具体问题：第三位参与者不能正常注册加入；便签文字不能保证改票、主备定案规则；整板公开可能带出未选方案及私密讨论。新应用另建目录，保留旧实现与失败记录。核心流程不依赖画布操作。

## 本地运行

需要 Java 21、Maven；技术底座为 Spring Boot 3.2.5、Spring JDBC 与 H2 文件数据库，没有微服务或外部账号服务。当前依赖已在本机缓存，可离线构建：

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-21'
& 'C:\javaweb\apache-maven-3.8.1\bin\mvn.cmd' -o package
$env:ACTIVITY_DATA_FILE='D:/Workspace/codex/activity-choice-data/activity'
& 'C:\Program Files\Java\jdk-21\bin\java.exe' -jar target/activity-choice-1.0.0.jar
```

在此 `server` 目录运行构建命令。`ACTIVITY_DATA_FILE` 是数据库文件基名，不加 `.mv.db` 后缀；可自行指定有写权限的绝对路径，默认 `./data/activity-choice`。连接参数包括 `WRITE_DELAY=0`；不要同时启动两个进程使用同一数据文件。

应用绑定 `127.0.0.1:8098`，浏览器打开 `http://127.0.0.1:8098/`。构建包若已包含 `src/main/resources/static`，同一进程提供 Vue 页面。前端产物由项目根工作流从 `../web/dist` 放入该目录后重新打包。开发时通过 Vite 的 `/api` 代理访问，后端没有开放任意跨域 CORS。

没有固定演示账号。用三个独立浏览器上下文分别正常注册；用户名为 3—32 位字母、数字或下划线，统一转为小写，密码为 8—128 个 UTF-16 代码单元。不要为这个本地项目使用其他网站的密码。应用不发送邮件、短信或邀请消息，邀请链接由使用者自行复制。

**当前邀请与分享链接仅能在这台电脑打开。** 三会话测试是流程验证，不代表已经向异地朋友提供线上协作服务。公网服务所需的 HTTPS、域名、运行维护与进一步安全验证不在本轮交付范围。

## 业务流程与边界

1. 发起者建立活动，自动成为 `owner`。活动列表只返回本账号参与的活动。
2. 组织者创建一次性邀请，默认 60 分钟，允许 1—10080 分钟；朋友先注册或登录，再领取。过期、撤销或已领取的令牌不能再次增加成员。数据库仅保存令牌的 SHA-256 摘要。
3. 所有成员可提交候选。开放、费用、预约三项规则必须显式填写 `unknown` 或 `verified`；已核实项还必须有说明、HTTP/HTTPS 来源及有效日期。来源只是参与者录入的依据，服务器不会抓取或审核该网页，不能据此声称事实已由系统验证。
4. 每个账号可在每个候选选择 `want`、`okay` 或 `avoid`，允许改选和撤回。重复同状态请求不增加记录，也不增加活动版本号。一个账号的唯一偏好不等于实名制，也不能阻止同一自然人注册多个账号。
5. 组织者以最新活动 `version` 定案，主备必须不同且属于本活动，并填写集合时间和地点。票数是参考，不自动作决定，不代表所有参与者已经达成一致。候选修改、偏好变化和定案变更递增活动版本；领取新成员也会递增。
6. 发布时，服务器从已定主备方案建立独立 JSON 白名单副本。公开对象不含未选候选、成员、偏好、候选 ID、作者或内部讨论。修改草稿不会改变旧副本；重新发布获得新链接和发布序号。
7. 旧链接保持原内容，`isLatest=false` 标记旧版。撤销后匿名 API 返回 410 且不含正文。发布序号取该活动历次发布最大序号；即使最新版撤销，旧版也不会自动恢复为最新版。

候选编辑只允许作者或组织者，使用候选自身的 `expectedVersion` 检查旧版本。当前不提供候选删除、成员移除或退出接口，不能声称这些流程已验证。

## API 使用

所有写操作先获取会话：

```text
GET /api/session
=> {user:null|{username}, csrfToken, csrfHeader:"X-CSRF-Token"}
```

保留会话 Cookie，后续写请求带该 CSRF 头。注册、登录后会话及 CSRF 旋转，须重新调用 `GET /api/session`；退出后重新获取会话才能再次注册或登录。

| 方法与路径 | 用途与权限 |
| --- | --- |
| `POST /api/register`、`POST /api/login` | `{username,password}`；注册成功即登录 |
| `POST /api/logout` | 注销当前会话 |
| `GET /api/groups`、`POST /api/groups` | 列出已加入活动；建立 `{name,eventDate}` 活动 |
| `GET /api/groups/{id}` | 成员读取候选、成员、偏好、定案和发布状态 |
| `POST /api/groups/{id}/invitations` | 组织者创建 `{expiresInMinutes}` 邀请 |
| `DELETE /api/groups/{id}/invitations/{invitationId}` | 组织者撤销邀请 |
| `POST /api/invitations/claim` | 登录账号领取 `{token}` |
| `POST /api/groups/{id}/candidates` | 成员提交候选 |
| `PUT /api/groups/{id}/candidates/{candidateId}` | 作者或组织者提交完整字段和 `expectedVersion` |
| `PUT /api/groups/{id}/candidates/{candidateId}/preference` | 会话本人设置 `{choice}`，不得指定其他投票者 |
| `DELETE /api/groups/{id}/candidates/{candidateId}/preference` | 本人撤回偏好 |
| `PUT /api/groups/{id}/decision` | 组织者提交主备、集合信息及 `expectedVersion` |
| `POST /api/groups/{id}/publications` | 组织者发布，正文为空或 `{}`，拒绝自带公开内容 |
| `DELETE /api/groups/{id}/publications/{publicationId}` | 组织者撤销对应分享 |
| `GET /api/publications/{id}` | 匿名读取白名单副本或 410 失效状态 |

`/share/{id}`、`/join`、`/groups/{id}` 页面刷新会回到前端入口，未知 `/api/**` 返回 JSON 404。预期业务错误格式为 `{code,message}`；包括 `invalid_input`、`unauthenticated`、`forbidden`、`not_found`、`conflict`、`gone`、`csrf_invalid`、`origin_denied`、`rate_limited`。409 后应读取最新数据再由使用者确认，不自动覆盖他人修改。

## 输入限制

字符串长度按 Java `String.length()`，即 UTF-16 代码单元计算。业务描述文本去除首尾空白；必填项拒绝纯空白。描述文本允许换行、制表符，不允许 NUL 等其他低位控制字符。所有 API 请求正文最多 32 KiB，带结构化 JSON 的业务接口拒绝未声明字段；版本及邀请时长必须是 JSON 整数，拒绝小数或数字字符串。

| 字段 | 范围 / 规则 |
| --- | --- |
| `username` / `password` | 用户名 3—32 位 ASCII 字母数字下划线；密码 8—128，不含 NUL |
| 活动 `name` / `eventDate` | 名称 1—80；日期为有效 `YYYY-MM-DD` |
| 候选 `title` | 1—120 |
| `address` / `timeWindow` | 0—300 / 0—120 |
| `sourceUrl` | 0—2048；非空时仅 HTTP/HTTPS、须含主机，不含用户信息、空白或控制字符 |
| `verifiedAt` | 空或有效 `YYYY-MM-DD` |
| `openingText` / `feeText` / `reservationText` | 各 0—500；`verified` 时不能为空 |
| 三项 `*Status` | 仅 `unknown`、`verified`；未核实不补成免费、免预约或开放 |
| `internalNote` | 0—2000，仅成员视图可见 |
| `meetingTime` / `meetingPlace` / `publicNote` | 1—120 / 1—300 / 0—1000；集合时间是人工文本，不做时区或订票校验 |
| `expectedVersion` | 大于等于 1 的整数，须匹配当前版本 |
| 邀请时长 / 令牌 | 1—10080 分钟；令牌为 32 随机字节的 43 位 URL-safe Base64 表示 |
| 路径标识 | 标准小写 UUID 形态 |

## 实现说明

- 密码使用 PBKDF2-HMAC-SHA256，600000 轮，每账号 24 随机字节盐，输出 256 位，使用恒定时间摘要比较；无明文密码存储。
- Cookie 为 HttpOnly、SameSite=Strict，会话 30 分钟；本机 HTTP 配置没有 `Secure` 标志。注册、登录替换原会话，写操作检查 CSRF。
- 服务仅绑定本机，Host 只允许 loopback；Origin 仅允许本机同端口或 5173/5174/5198 开发端口，拒绝跨站请求。安全响应头包括 CSP、禁止嵌入、禁止 MIME 猜测和 `no-store`。
- SQL 参数化；活动修改事务先锁活动行，唯一主键与外键约束参与者、候选和偏好归属。相同活动内写入会串行化，避免并发重试重复计票；这是单进程小组规模取舍，不是大规模性能方案。
- 候选与活动使用不同版本号；发布序号另行递增。公开内容从字段白名单生成并独立落盘，不从实时草稿直接透传。
- 本机内存限流：每会话写请求 240 次/自然分钟，认证按来源地址和用户名合计 12 次/自然分钟。它不能代替公网反滥用或分布式限流。

## 验证与未覆盖范围

`mvn -o package` 已运行 15 项测试（14 项真实 HTTP 集成测试、1 项启动两次独立 JVM 的持久化测试），全部通过，详细证据见 [backend-evidence.md](backend-evidence.md)。根代理已用打包后服务完成 [独立 API 验收：公开摘要](../EVIDENCE.md)，以真实公开场所资料构造任务，记录六项通过结果；角色账号仍是测试创建，不算用户试用。浏览器结果以根代理另行记录为准。

当前数据库为本机 H2，账号、活动、候选、偏好、定案、邀请状态与发布副本持久化；会话和限流在内存，服务重启后需重新登录。没有账户找回、邮箱/手机验证、成员移除、分页、容量配额、自动核实、外部通知、自动订票、支付、备份恢复或多实例运行。未做生产负载、断电损坏恢复、网络恶意攻击和依赖漏洞扫描；不能把测试通过描述成生产就绪。

三账号角色扮演只验证业务规则与页面流程，不能证明真实群体愿意使用，更不能据此填写节省工时或满意率。需要真实参与者在同批资料下与共享文档/群投票进行对照后，才能评价这个产品是否值得继续做。


> 公开源码说明：本文部分链接原本指向开发工作区外的 QA/研究材料，本仓库没有附这些运行数据、截图或原始资料；已改为仓库内 EVIDENCE 摘要，不代表原始记录被一并发布。
