# ms-localdebug 使用手册

通用微服务本地调试 IDEA 插件的详细使用说明。配合 kt-connect 在本地开发/调试 Kubernetes 集群中的微服务。

## 前置条件

- JDK 17+，IDEA 2023.1+（插件 `sinceBuild=231`）
- 有权限的 Kubernetes 集群（kubeconfig）
- 集群中已部署目标微服务

## 安装插件

```bash
# 打包
./gradlew buildPlugin
```

产物在 `build/distributions/ms-localdebug-<version>.zip`。IDEA 中：`Settings → Plugins → ⚙ → Install Plugin from Disk...` 选择 zip，重启。

## 初始设置

1. **工具安装**：打开右侧边栏 `LocalDebug` 工具窗口 → `工具安装` 页签 → 点击「安装 kubectl」「安装 ktctl (ktconnect)」。二进制下载到 `~/.localdebug/bin/` 并自动加入 PATH。下载地址在全局配置 `~/.localdebug/config/config.json` 中设置（`debug.kubectl.downloadUrl` / `debug.ktconnect.downloadUrl`）。
2. **K8s 配置**：`Settings → LocalDebug 微服务调试`，粘贴 kubeconfig 内容 → 「保存 kubeconfig」（写入 `~/.kube/config`）→ 「测试连接」。
3. **阿里云配置**：同一设置页填写 Nacos 地址、AccessKey / SecretKey 等。**AK/SK 保存在 IntelliJ PasswordSafe 中，不落明文文件**；RocketMQ 消费者/生产者等列表配置 UI 暂未提供，可编辑 `~/.localdebug/config/aliyun_config.json`。

## 项目接入流程

1. `LocalDebug` → `项目配置` 页签 → 添加 debug 项目
2. 选中项目 → 「初始化配置」：
   - 备份 `application.yaml` / `bootstrap.yaml` / `pom.xml`（备份在项目内 `project_config_back/`）
   - 复制 `.localdebug.json` 配置模板到项目根目录
   - 生成 `.localdebug.ktctl.cmds`、`.localdebug.k8sServices.json`、`ports.json`
3. 按项目实际情况填写 `.localdebug.json`（Nacos dataId/group、K8s 服务名与命名空间、serviceType、tag；`debugTag` 非空时优先于 OS 用户名作为链路 tag）
4. 「替换配置」：改写本地配置文件（Nacos 地址、Dubbo tag、中间件 patch），生成 `.localdebug.jvmrc.json`
5. 后续再次调试无需重复 init，直接「替换配置」即可

## 启动调试

1. `调试命令` 页签 → 选择项目 → 启动 `ktctl connect`（本地接入集群网络）
2. 正常 Run/Debug 启动本地应用——插件自动按主类注入 `.localdebug.jvmrc.json` 中的 JVM 参数与环境变量
3. 启动流量拦截命令（见下）
4. 调试完成 → 终止各代理命令 → 「回滚配置」恢复项目文件

## 典型调试场景

### 场景一：BFF 接口流（serviceType = bff）

BFF 接收 HTTP 请求，流量拦截用 `ktctl mesh`：

```bash
# 调试命令页签依次启动：
ktctl -d -n <namespace> connect
ktctl -d -n <namespace> mesh <bff-service> --expose <本地端口>:8080 --versionMark tag:<tag>
```

调用时请求头带上你的 tag（默认为 OS 用户名，可用 `.localdebug.json` 的 `debugTag` 覆盖）：

```bash
curl -H "tag: <tag>" http://<集群入口>/<bff接口路径>
```

带 tag 的请求经 mesh 代理路由到本地 BFF 实例；不带 tag 的流量走原集群服务，不影响他人。

### 场景二：RPC 流（serviceType = rpc）

RPC 服务通过 Dubbo 调用，流量拦截用 `ktctl preview`（在集群中创建别名服务 `<service>-<tag>`）：

```bash
ktctl -d -n <namespace> connect
ktctl -d -n <namespace> preview <rpc-service>-<tag> --expose 20880:20880
```

上游调用方在 Dubbo 请求头带 `dubbo-tag: <tag>`，流量即路由到本地 RPC 实例（本地应用已由替换配置写入 `dubbo.provider.tag`）。

> BFF 与 RPC 可在同一项目混布：`.localdebug.json` 的 `replaceConfigs` 各配一条，ktctl 命令会同时生成 mesh 与 preview。

## 常见问题

### 代理未正常终止，集群中残留失联 pod

ktctl 异常退出（IDE 崩溃、强杀进程）时，集群里可能残留已失联的 KT 代理容器。清理：

```bash
# 清理指定命名空间下超期的代理容器
ktctl -d -n <namespace> clean

# 恢复被 mesh/preview 改动的服务
ktctl -d -n <namespace> recover <service-name>
```

这两条命令已预置在 `.localdebug.ktctl.cmds` 中（「清理流量拦截」「清理集群里已超期KT代理容器」），可直接在调试命令页签启动。

### AK/SK 存在哪里？

存 IntelliJ PasswordSafe（操作系统级加密存储），不写入明文文件。更换凭据：`Settings → LocalDebug 微服务调试` 重新填写保存。

### kubeconfig 保存在哪里？

设置页「保存 kubeconfig」写入 `~/.kube/config`（标准位置），kubectl / ktctl 均直接读取，无需额外配置。

### 配置文件改坏了怎么恢复？

「回滚配置」从 `project_config_back/` 恢复 `application.yaml` / `bootstrap.yaml` / `pom.xml` 到初始化前状态，然后重新填写 `.localdebug.json` → 「替换配置」。

### 启动应用没有注入参数？

确认 `.localdebug.jvmrc.json` 存在于项目根目录且 `mainClass` 与运行配置的主类一致（该文件由「替换配置」生成，手工改动会被下次替换覆盖，如需补充参数请改 `.localdebug.json` 的 `jvmArgs`）。
