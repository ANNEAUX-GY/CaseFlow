# 调试踩坑备查（CaseFlow）

> 从 MEMORY.md 迁出的「坑位清单」。这些是**排查具体问题时的备查**，
> 不是每次开工都要读的内容。日常约定见 `MEMORY.md`。

## 启动 / 环境

### 后端在本机活不过一次工具调用
`nohup` / `Start-Process` 起的后端会被 SIGTERM 收走（表现为端口 8080 探测 000，
但日志里 Tomcat 已 started）。**用 Bash 工具的 `run_in_background=true` 直接跑
`dev.py --no-frontend` 才稳**。另外 dev 用 H2 文件库，**同时只能一个后端进程**。

### 编译后端必须用 dev.py 同款命令
中文路径下 `mvn -f` 会编码损坏。在 `app/backend` 下执行：
```
JAVA_HOME=jdk8 tools/maven/bin/mvn.cmd -B -o -Dmaven.repo.local=D:\案件指派demo\tools\m2repo compile
```

## 前端运行时坑

### Vue 模板 `:class` 数组里不能写两个对象字面量
`:class="[{ 'is-zero': x }, { 'is-' + t }]"` → `Error parsing JavaScript expression`，
**整页 500，连 Layout 都加载不出来**，浏览器控制台只说
「Failed to fetch dynamically imported module」，看不出是哪个表达式。
正确：`:class="['is-' + t, { 'is-zero': x }]"`（字符串 + 单个对象）。
排查：直接 `curl http://127.0.0.1:5173/src/xxx.vue | grep -i error`，
错误详情在 vite 错误浮层的 HTML 里。

### 漏导入 Vue 组合式 API —— 编译期不报错，运行时才炸
`AssignDialog.vue` 缺 `computed`、`Register.vue` 缺 `computed/watch`。
症状极迷惑：**编译全绿**，运行时 `ReferenceError: computed is not defined`，
**整个抽屉打不开**。排查靠 `pageerror` 事件的 `e.stack`
（能看到 `at setup (xxx.vue:34:23)` 定位行号）。
**已加 `scripts/check_vue_imports.py`，发版前跑一次。**

### 改完必须 grep 确认锚点真的存在
用 node 脚本批量插入代码时，锚点字符串不完全匹配会**静默失败、零报错**。
前科：给 `load()` 插 `await loadQuestions()` 没插进去 → 用户看到「打开永远 0 条，
发一条才刷出历史」；`<FeedbackDialog>` 只 import 没挂载 → **点击按钮完全无反应但无报错**。
脚本中途抛异常也会留下「import 了但模板没挂载」的半成品。
**改完 grep，改完 grep，改完 grep。**

### 按字符串位置删 HTML 片段会破坏标签配对
删流程图时多删了一个 `</div>` → Vue 报 `Invalid end tag`，**编译期不报错、只在浏览器炸**。
删完立刻 `curl localhost:5173/src/xxx.vue | grep -i error` 校验。

### Element Plus 坑
1. **el-radio-group 受控绑定导致「点几次就改不动」**（三重叠加）：
   给 `:model-value`（受控）而非 v-model → 内部选中态变了但父级没变，Vue 重渲染**弹回旧值**；
   **不能用 `reload()` 整体替换数组**（模板绑的 row 引用失效，乐观改的值改在旧对象上），
   要按 id 就地同步 + `serial()` 串行链；点击落在 `.el-radio-button__inner` 上，
   **用 `@click` 读 `event.target.value` 拿到 undefined** → 必须用 `@change`。
   通用教训：受控组件 + 乐观更新时，不要用 reload() 整体替换数组。
2. **el-radio-button 悬停会把选中态文字染成主题色** → 「红底红字」看不清。
   必须写 `:hover:not(.is-active)`，并显式声明 `.is-active .el-radio-button__inner { color:#fff }`。
3. **remote el-select 内部输入框是 `.el-select__input`**（无 placeholder）：
   点 wrapper 后直接 `keyboard.type`，别 `evaluate focus`（会把焦点搞丢）。
4. **el-cascader 隐藏 radio 上 dispatchEvent 无效**：用 `page.mouse` 按节点
   bounding box 真实点击；`checkStrictly` 点节点文字即选中。
5. **el-tree 行内长标签在手机端会被截掉半个字**。给容器加 `min-width:0`、
   子项 `text-overflow:ellipsis`，长文案在手机端换短版本。

## 后端 / 数据坑

### MyBatis-Plus `apply()` 不能拼排序
`.apply("... ASC, id ASC")` 拼进的是 **WHERE 条件**，生成
`WHERE (case_id=? AND (CASE..END) ASC, id ASC)` → H2 直接 `JdbcSQLSyntaxErrorException`。
排序必须用 `.last("ORDER BY ...")`。**这个坑在 `OpinionService.sortOrder` 上犯过一次。**

### `Map<String,Object>` 强转必须与 put 时类型一致
负荷详情排序里 `(Long) a.get("daysLeft")` → 运行期
`Integer cannot be cast to Long`。put 进去是 Integer 就必须按 Integer 取。
**这类错编译期发现不了，只在有数据时炸。**

### 前后端规则判定口径必须一致
`feedbackCount` 与完成规则原先只算**本任务自己**的反馈，而前端 `canDone` 用的是
合并后的条数 → **子任务都提交过反馈了，前端按钮可点、后端却拒绝**。
现统一为 `countFeedbacksWithSub()`（含子任务）。
**凡「前端置灰/可点」与「后端放行/拒绝」共用同一规则，必须抽成同一个口径函数。**

### `caseMeasure` 返回 JSON null 而非字符串 "NONE"
`CaseInfo.caseMeasure` 无值时返回 **None（JSON null）**。脚本里
`if measure == "NONE"` 会漏判，要用 `not measure`。
`CaseService.moduleOf` 里是 `!hasText(m) || "NONE".equals(m)`，两种都覆盖。

### 前端传参字段名与后端 DTO 对不上（静默失败）
`TodoSaveRequest` 字段是 `content`，我传了 `{ note }` → 后端收 null → 提交必然失败。
**教训：加提交类功能必须端到端验一次成功路径（填内容→提交→查库），
不能只验「打开弹窗不自动提交」。**

### `@FullAccessOnly` 会把子任务操作挡在门外
`PermissionInterceptor` 在**方法执行前**拦截，所以「主任务仅管理层、子任务承办人即可」
这种分层**做在 Service 里没用**——注解必须一起去掉，权限判断下沉到 Service。

### CREATE INDEX IF NOT EXISTS 是 H2 专属
MySQL 8 会让建表段失败（`init_db` 直接崩）。**二级索引必须放 `sql/index-mysql.sql`**。

### 规则变更后自检脚本要跟上
- `verify_scope` / `verify_assign_status` 原先取任意员工 + 任意案件，
  组别校验上线后会被正确拒收 → **自检失败是约束在正常工作，不是被测逻辑坏了**。
  修法：固定挑「无强制措施」的案件 + 员工组别设 INITIAL，finally 恢复不留痕迹。
- `audit_consistency.py` 的「已完成待办必须有佐证」在规则废止后必然报 10 处不一致。
- **凡是「会往库里写数据」的自检都必须自建专用数据 + 跑完自动清理**
  （时间戳 RUN_ID 标记）。第一版 `verify_todo_subtask.py` 复用存量主任务，
  上一轮留下的子任务/反馈让断言基数全错（实际 6 个、断言 2 个，27 项里错 8 项），
  **误以为代码坏了**。基线从 0 开始才恒定可重复。

## 截图核对 / puppeteer

- **ESM 脚本不认 `NODE_PATH`**：`import 'puppeteer-core'` 报 `ERR_MODULE_NOT_FOUND`，
  必须用绝对路径
  `file:///C:/Users/.../node_modules/puppeteer-core/lib/puppeteer/puppeteer-core.js`。
- 登录注入 localStorage 必须**同时写 `cf_token` 和 `cf_user`**；
  `login` 返回的 `data` 是**扁平**的（token/userId/role…），**没有 `user` 子对象**，
  要 `const { token, ...userInfo } = j.data`。
- `querySelector('.el-drawer'/'.el-dialog')` 会选到隐藏的第一个，
  必须 `.find` 按内容/可见+标题定位。
- 点 el-radio-button：点 `.el-radio-button__inner`；**抽屉很长时第一条的坐标可能在视口外**，
  必须先 `scrollIntoView({block:'center'})`；`scrollIntoView` 后立刻
  `getBoundingClientRect()` 拿到的是**滚动前的旧坐标**，要么等 500ms 重取，
  要么直接 `element.click()` 派发（最稳）。**因此误判过一次产品有 bug。**
- 颜色判定用 Pillow `Counter` 取目标区域色，别只看截图。
- 各页面路由：`/#/org`（员工图谱，**不是 /employees**）、`/#/cases`、`/#/watch`、
  `/#/todos`、`/#/users`、`/#/categories`、`/#/dashboard`。写错会截到空白页。
- 产物目录 `shots/` 已加进 `.git/info/exclude`。

## git push

- **卡住无输出 4 分钟**：`PortableGit/versions/1.2.0/etc/gitconfig` 里
  `[credential] helper = helper-selector` 会**弹 GUI 凭据框**，无人值守永远等不到。
  已仓库级修好：`git config --local credential.helper ""` 然后 `= manager`。
  **不要去改便携 Git 的 etc/gitconfig**（影响整个 WorkBuddy 环境）。
- **直连报 `Connection was reset`**（限流，不是配置问题）→ **去掉** `env -u http_proxy`，
  走代理 5 秒推完。**直连卡住 → 加上 `env -u http_proxy -u https_proxy -u HTTP_PROXY -u HTTPS_PROXY`**。
  两种都踩过，判断：报 reset 就去掉，卡住就加上，**别固定绕过**。
- 仓库级传输参数：`http.version=HTTP/1.1`、`http.postBuffer=524288000`、
  `http.lowSpeedLimit=0`、`http.lowSpeedTime=999999`。
- 已跟踪的 `.gitignore` 不要动，本机专属写 `.git/info/exclude`。
