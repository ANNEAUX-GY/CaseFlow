============================================================
 CaseFlow 案件指派系统 · 开发环境包（换电脑继续开发用）
============================================================

一、这是什么
------------------------------------------------------------
这是「整套可继续开发」的环境，不是只能看不能改的成品：
源码 + JDK 8 + Maven + Maven 离线仓库 + Node + 前端依赖
+ 演示数据库，全部自带。解压到任何一台 Windows 电脑，
用 PyCharm 打开即可把前后端跑起来，改代码即时生效。

需要联网的场景只有两种：新增第三方依赖、重建 Python 虚拟环境。
日常改代码、编译、运行完全离线可用。

二、怎么启动（重要：本项目没有 .bat）
------------------------------------------------------------
项目刻意**不提供任何 .bat 启动脚本**，全部改为 app\scripts
下的 Python 脚本 + PyCharm 运行配置。原因有两个：
  1. .bat 依赖 cmd 的编码与 findstr 语法，中文极易乱码；
  2. 双击黑窗口的方式 IDE 管不到进程树，停止时常留下
     Java / Node 孤儿进程占住 8080 / 5173 端口。

启动步骤：
1. 解压到任意位置（建议路径不要有中文和空格）
2. 用 PyCharm 打开 **app 目录**（不是它的上一级 CaseFlow开发环境包）
3. 右上角运行下拉框里选「① 一键启动（后端+前端）」，点运行
4. 首次会编译后端，等 30~90 秒
5. 浏览器访问 http://127.0.0.1:5173 （开发页，改代码自动刷新）
   登录：boss / admin123（系统管理员，全部权限）

如果下拉框里没有这些配置：Run -> Edit Configurations -> 左侧 +
-> Python，按下表填一份即可（其余配置同理）：

  名称            Script path               Parameters
  ---------------------------------------------------------
  ① 一键启动      app\scripts\dev.py        --seed
  ② 只启动后端    app\scripts\dev.py        --no-frontend
  ③ 只启动前端    app\scripts\dev.py        --no-backend
  ④ 环境自检      app\scripts\dev.py        --check
  ⑤ 修复依赖      app\scripts\dev.py        --fix-deps
  ⑥ 灌演示数据    app\scripts\seed_demo.py  （留空）
  ⑦ 一致性体检    app\scripts\audit_consistency.py  （留空）
  ⑧ 打包成品      app\scripts\package_release.py   （留空）
  ⑨ 局域网部署    app\scripts\start_lan.py         （留空）
  ⑩ 开放局域网    app\scripts\open_lan_firewall.py （留空）

公共字段（以上全部一致）：
  Working directory           <项目>\app
  Python interpreter          app\.venv\Scripts\python.exe
  ? Emulate terminal in output console   （必须勾，否则子进程输出被缓冲，看着像卡死）

三、目录结构
------------------------------------------------------------
README-开发包.txt  本说明

app\               项目源码（就是原来那份工程）
  backend\         Spring Boot 后端（JDK8 + MyBatis-Plus）
  frontend\        Vue3 + Vite 前端（node_modules 已装好）
  scripts\         启动器 / 演示数据 / 自检 / 打包（Python）
  sql\             建表脚本
  docs\            设计方案等文档
  .idea\           PyCharm 工程配置（含上面 10 个运行配置）
  backend\data\    内置 H2 数据库 + 上传附件目录
tools\             自带工具链（不要改目录名）
  jdk8\            JDK 1.8（后端必须用它，高版本不兼容）
  maven\           Maven 3.9.9
  m2repo\          Maven 离线依赖仓库（离线编译靠它）
  node\            Node 22 运行时（npm 已内置）

四、日常开发怎么改
------------------------------------------------------------
- 改前端：编辑 app\frontend\src 下的文件，保存即热更新，不用重启
- 改后端：编辑 app\backend 下的 Java，然后重启「② 只启动后端」这个
  运行配置（PyCharm 里点红色停止再点绿色运行即可）
- 换端口：运行配置的 Parameters 里加 --backend-port 8090
- 数据库：内置 H2 文件库 app\backend\data\h2\caseflow.mv.db
  注意同一时刻只能有一个后端进程打开它
- 想彻底清空数据：停掉后端，删除 app\backend\data\h2\caseflow.mv.db，
  重新启动会自动建表并初始化账号（boss / admin123）
- 当前库里已有演示数据：12 起案件、8 人组织、4 起带侦查进度

五、脚本说明（都在 app\scripts\ 下，也可用对应运行配置）
------------------------------------------------------------
dev.py                 启动器。--check 自检 / --fix-deps 修依赖 /
                       --no-frontend 只后端 / --no-backend 只前端 /
                       --seed 灌数据 / --profile mysql 用 MySQL
seed_demo.py           灌演示案件（需后端已在跑）
seed_watch.py          灌盯办进度（需后端已在跑）
audit_consistency.py   全库一致性体检（只读，退出码非 0 即有不一致）
verify_assign_status.py 状态推进与写入约束的行为自检
verify_scope.py        角色数据范围（管理员 vs 普通民警）
package_release.py     打包成品 -> app\backend\target\case-flow-backend.jar
start_lan.py           局域网部署（跑 jar，同事/手机可访问）
open_lan_firewall.py   放行 TCP 8080 入站（需管理员，会自动弹 UAC）
mysql_local.py         本地免安装 MySQL 的 init/start/stop/status/setup

注：dev.py 刻意只依赖 Python 标准库。就算 .venv 没建、依赖没装，
用系统 Python 也能跑起来（它会自动定位 tools\ 下的 JDK/Maven/Node）。

六、打成品给别人演示
------------------------------------------------------------
跑运行配置「⑧ 打包成品（jar）」，产出
app\backend\target\case-flow-backend.jar（前端页面已内嵌）。
对方电脑只要有 JDK8：
    java -jar case-flow-backend.jar --server.port=8080
页面地址 http://<目标机IP>:8080/api/（结尾的 /api/ 不能少）

全单位共用一套时，改用运行配置「⑨ 局域网部署启动」，
它会自动拉起数据库、打印可访问地址和二维码；
手机打不开就跑一次「⑩ 开放局域网访问」。

七、常见问题
------------------------------------------------------------
Q: 运行配置下拉框是空的 / 找不到项目？
A: 要用 PyCharm 打开 app 目录，不是上一级 CaseFlow开发环境包。

Q: 解释器显示红色 / 提示 Cannot find Python interpreter？
A: .venv 不存在（它在 .gitignore 里，换电脑不会带过来）。
   Settings -> Project: app -> Python Interpreter -> Add Local
   Interpreter -> Existing，指到 app\.venv\Scripts\python.exe。
   若 .venv 目录本身没有，先跑「⑤ 修复依赖」运行配置重建。
   也可以在 Settings 里把解释器指到系统 Python —— 脚本照样能跑。

Q: 运行报「无法加载模块 .venv」或 ObjectNotFound？
A: 这类问题都来自在终端手敲命令时路径/目录不对。
   不要开 Terminal，直接用上面的运行配置。

Q: 后端报 Could not open ... caseflow.mv.db？
A: 已经有一个后端实例在跑，先停掉再启动。

Q: 提示端口 8080 被占用？
A: dev 模式用 H2 文件库，同一时刻只允许一个进程打开，
   所以不能并行起两个后端。停掉旧的即可；
   或在 Parameters 里加 --kill-existing 让脚本自动结束旧进程。

Q: 前端依赖报 spawnSync node.exe EBUSY？
A: 有进程占着 tools\node\node.exe（前端还开着、或终端占着）。
   先关掉正在跑的前端，再跑「⑤ 修复依赖」。
   注意：修复用的是 npm install（增量），不是 npm ci ——
   npm ci 会先清空 node_modules，中断后会留下半残依赖。

Q: 编译报 Unsupported class file major version？
A: JDK 版本不对，本项目必须用 JDK 8（tools\jdk8 已经是）。

Q: 前端页面空白 / 接口 404？
A: 确认后端已打印 Started CaseFlowApplication；
   前端 5173 已把 /api 代理到 8080，两个都要在跑。

============================================================
