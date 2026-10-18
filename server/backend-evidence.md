# 后端实施与验证证据

> 公开源码范围：本文保留原开发环境的实施与验证历史。提到的日志、截图、仓库外 QA/研究原始材料不在此公开目录；当前可用测试和质量限制见 [EVIDENCE 摘要](../EVIDENCE.md)。


日期：2026-10-17。范围：`activity-choice/server` 的本地 Java 业务闭环，不修改旧 `canvas-share`。源码与测试依据根 [实施契约：公开摘要](../EVIDENCE.md) 和 [重构规格：公开摘要](../EVIDENCE.md)。此文件记录开发及测试事实，不宣称真实用户收益。

## 为什么增加这些规则

旧任务失败记录已经指出：固定演示账号阻止第三人正常加入；便签没有结构化偏好和定案规则；整板分享可能泄露未选方案及内部讨论。本轮使用普通本机注册、一次性邀请、候选规则状态、账号唯一偏好、组织者主备定案以及白名单发布副本，将一次活动从加入到撤销分享串成可验收流程。

## RED → GREEN

| 阶段 | 实际命令与记录 | 观察结果 |
| --- | --- | --- |
| 初始 HTTP RED | `mvn -o test`；[red-http.log：公开摘要](../EVIDENCE.md) | 10 项测试，10 次断言失败、0 错误；真实 `/api/session` 缺失返回 404，预期 200。不是用编译错误代替 RED。 |
| 第一轮 GREEN | `mvn -o test`；[green-http-1.log：公开摘要](../EVIDENCE.md) | 10 项 HTTP 测试全部通过，涵盖三账号、邀请失效、权限、候选版本、12 请求并发偏好、主备及匿名冻结发布。 |
| 严格边界 RED | `mvn -o -Dtest=BusinessHttpTest test`；[red-boundaries.log：公开摘要](../EVIDENCE.md) | 14 项测试中 2 项失败、0 错误：邀请时长 `1.5` 被截断接受，发布接口忽略了调用方自带的非空正文，均返回 201 而非预期 400。 |
| 完整 GREEN 与打包 | `mvn -o package`；[根打包日志：公开摘要](../EVIDENCE.md) | 增加整数严格反序列化与空发布正文校验后，14 项 HTTP + 1 项独立 JVM 测试全部通过，0 失败、0 错误、0 跳过；可执行 jar 打包成功。 |

最终打包日志完成时间为 2026-10-17 17:38:56（本机时间），总时长 75 秒左右，不作为性能指标。当前测试报告位于 `target/surefire-reports`。此文核对了测试报告与最终日志；不另行重复运行同一套测试，以免干扰根代理独立验收。

根代理随后在打包后独立启动的服务上运行 [黑盒验收：公开摘要](../EVIDENCE.md)，其中六项检查通过：新账号加入与隔离、真实来源候选保留未知状态、六并发偏好及改选撤回、组织者主备与旧版本拒绝、匿名白名单、冻结新旧版与撤销。该任务使用四个构造账号、其中三个成员，以及真实公开场所资料，仍属于流程测试。API 检查耗时不能作为用户节省时间或生产吞吐量数据。浏览器可读性与手机布局由根任务另行验收，不在这六项记录中冒充通过。

## HTTP 测试具体证明了什么

测试文件：[BusinessHttpTest.java](src/test/java/local/activity/BusinessHttpTest.java)。使用真实 Spring Boot 嵌入式 HTTP 服务、独立 Cookie 容器、真实 JDBC/H2；未使用控制器或数据库 mock。

| 测试名 | 可观察行为 |
| --- | --- |
| `normalRegistrationHashesPasswordsAndRotatesSessionCsrf` | 普通注册后登录；数据库不存明文；退出、错误密码、重复注册及无 CSRF 写入被处理；认证后令牌变化。 |
| `threeNewAccountsJoinOnceAndOutsideAccountCannotReadOrWrite` | 三个新账号通过一次性邀请加入同一活动；第四账号不能读取或提交；数据库令牌与原文不同；已领取邀请不能再次使用。 |
| `expiredRevokedAndUsedInvitationsDoNotCreateMembership` | 使用测试夹具把邀请设为过去时间后，通过真实 HTTP 领取返回 410；撤销同样返回 410，成员数不增加；拒绝过长时长与短令牌。 |
| `unknownRulesStayUnknownAndVerifiedNeedsSourceDateAndText` | 未核实费用空值保持未知；已核实规则缺说明、来源或日期时拒绝；危险协议、带账号密码及控制字符的 URL 被拒绝。 |
| `candidateWritesAreScopedAuthorRestrictedAndOptimistic` | 其他普通成员不能改作者候选；作者能修改；旧候选版本返回 409；跨活动候选 ID 不可借用。 |
| `concurrentPreferenceRetriesRemainOneAndCanChangeOrWithdraw` | 同一账号对同一候选的 12 个并发相同请求均成功，API 与数据库仅一条偏好；改选覆盖、撤回及重复撤回后汇总正确；不得指定他人账号。 |
| `onlyOwnerCanDecideDistinctLocalCandidatesAtLatestGroupVersion` | 成员直接定案被拒；主备同一个、跨活动候选和过时活动版本被拒；组织者基于刷新状态正常定案。 |
| `anonymousPublicationIsWhitelistFrozenVersionedAndReallyRevoked` | 发布前需有定案；成员不能发布；匿名正文不含私密标记、未选候选、成员/偏好/作者/候选 ID；草稿修改不影响 v1；v2 发布后旧版标识变化；撤销 v1 返回 410 且无正文，v2 不受影响。 |
| `malformedInputsCannotBecomeTruncatedOrCoercedBusinessData` | 拒绝短用户名/密码、过长密码/名称/内部讨论、无效日期、无效枚举、额外作者字段和嵌套错误类型。 |
| `untrustedOriginAndMissingAuthenticationCannotReadOrMutatePrivateData` | 未登录无法列出私有活动；外站与非允许开发端口不能访问或写入。 |
| `numericFieldsRejectFractionsAndTextInsteadOfSilentlyChangingVersions` | 邀请时长与候选版本拒绝浮点及数字字符串，错误请求不能推进候选版本。 |
| `emptyPublishContractCannotIgnoreAttackerSuppliedIdentityOrContent` | 发布拒绝任意自带内容，不会悄悄成功生成记录。 |
| `allCandidateLengthsAndDecisionLengthsHaveAcceptedBoundariesAndRejectedOverflow` | 候选各文本、来源 URL、集合信息和公开备注的上限值被接受，上限加一被拒；活动 80 字符边界被接受。 |
| `oldPreLoginSessionCannotUseItsCsrfAfterAuthentication` | 认证前令牌不能在新会话继续写入；未知 API 仍返回 JSON 404。 |

这些测试使用人为构造的字段、账号和活动，主要证明服务端不变量。它们没有证明参与者理解界面、来源事实正确、完成任务更快或愿意持续使用。

## 独立 JVM 持久化验证

测试文件：[JvmPersistenceTest.java](src/test/java/local/activity/JvmPersistenceTest.java)。它顺序启动并终止两个独立 Java 进程，使用同一个专属临时文件数据库，不是只重建内存对象或同 JVM 的服务实例。

第一进程正常注册两个账号、建立并加入活动、提交两个候选、表达偏好、确认主备、发布 v1；修改草稿再发布 v2 并撤销 v2，另建并撤销邀请。进程终止后，第二进程重新启动同一文件数据库，以既有密码重新登录，验证成员、候选新标题、偏好、主备定案均存在；匿名 v1 内容逐字保持原 JSON 且为旧版，已撤销 v2 仍为 410，新账号也无法领取已撤销邀请。

数据库使用 `WRITE_DELAY=0`。该测试验证这些正常提交后的业务状态在进程重开后可读，没有覆盖断电、磁盘损坏、所有提交瞬间的强制崩溃、备份恢复或双进程共享数据库。内存会话不持久化，重开后重新登录是预期行为。

## 安全与一致性实现位置

- [Passwords.java](src/main/java/local/activity/Passwords.java)：PBKDF2-HMAC-SHA256，600000 轮、24 字节随机盐、256 位输出；摘要使用 `MessageDigest.isEqual` 比较。用户名小写归一，唯一主键兜底并发重复注册。
- [LocalSecurity.java](src/main/java/local/activity/LocalSecurity.java)：本机 Host/Origin 限制、HttpOnly/SameSite 会话配合 CSRF、认证轮换会话、请求正文 32 KiB 上限、基础安全头与内存限流。认证限流按来源地址和用户名，写入限流按会话；未实现多实例反滥用。
- [ActivityService.java](src/main/java/local/activity/ActivityService.java)：参数化 SQL；写事务先锁活动行；邀请领取获得锁后重新检查已领、过期与撤销状态；偏好重复同状态不再插入或提高版本；候选作者/组织者权限与候选版本、定案组织者权限与活动版本分开校验。
- [schema.sql](src/main/resources/schema.sql)：活动成员、偏好唯一键与跨表外键；候选属于活动；主备不同且引用本活动候选的数据库约束。
- [JsonConfiguration.java](src/main/java/local/activity/JsonConfiguration.java)、[ActivityController.java](src/main/java/local/activity/ActivityController.java)：JSON 数值类型不隐式截断或转型，发布空正文契约拒绝自带内容。
- `ActivityService.publicCandidate/publish/publication`：只允许公开字段进入独立 JSON；返回 `content` 与动态 `isLatest`；撤销在读取正文之前返回 410。来源仅记录，不进行服务器抓取。

字段上限及运行命令见 [README.md](README.md)。集成测试未覆盖所有可能的类型、协议、并发交错或第三方漏洞；“测试中未泄漏标记”是针对该发布对象和样本的证据，不是无边界的安全保证。

## 交付物与局限

可执行包：`target/activity-choice-1.0.0.jar`。核对时大小 23371192 字节，SHA-256：`bb17d01681f756dbb61a23bb451b2fda0bbc4feb5235543de5d933bbda931eed`。根代理如重新包含前端产物打包，该摘要会相应变化，以最终打包记录为准。

当前仍是本机单进程、H2、小组任务范围，没有真实群体试用。匿名分享链接不是公网地址，不能让异地收件人直接访问。没有成员移除/退出、候选删除、账号找回、实名验证、内容自动核实、外部通知、分页、容量配额、备份、多节点或生产压测。内存限流、自然分钟窗口及未加密本地数据库不能作为生产级安全声明。成员暂时不能退出，因而没有以“移除成员后不计票”冒充已实现测试。

后续是否继续开发应由根任务中的真实任务对照和试用决定；目前不能写“节省多少工时”“已服务多少人”“提升出行效率”或“能够保证找到工作”。


> 公开源码说明：本文部分链接原本指向开发工作区外的 QA/研究材料，本仓库没有附这些运行数据、截图或原始资料；已改为仓库内 EVIDENCE 摘要，不代表原始记录被一并发布。
