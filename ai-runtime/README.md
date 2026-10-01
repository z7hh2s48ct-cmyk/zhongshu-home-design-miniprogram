# 众墅 AI Runtime 1.0.0

本目录是独立 Node 24 服务，不依赖后端 JVM，也没有 npm 运行时依赖。镜像与部署文件随源码交付。真实调用尚未验收，不得把离线样本回写当作真实生成成功。

## 处理合同

1. 使用 HMAC-SHA256 内部签名领取一个任务；jobId 以字符串传递，attemptNo + fencingToken 绑定本次租约。
2. 读取创建任务同一事务冻结的需求、阶段、资产编号及可选的顶层 `imageOptions`。新任务的 `imageOptions` 必须完整匹配 `{ resolution: '2K'|'4K', orientation: 'LANDSCAPE'|'PORTRAIT' }`，非法枚举在供应商调用前拒绝；无该字段的历史任务继续使用原配置尺寸。后端重新校验资产状态与使用权限后签发 120 秒读 URL；不向 Runtime 交付数据库或 COS 长期密钥。
3. 校验输入大小及 SHA-256。仅允许显式配置的存储 origin；拒绝重定向，不对模型返回的任意 URL 发起请求。
4. 先调用 apilio 的 chat/completions 生成提示词，再使用独立配置的图片模型调用 images/generations 或 multipart images/edits。立面必须有已选平面输入。每个槽位单独生成，实际图片通过原格式和摘要验证后上传。
5. 每 15 秒续租，丢失租约时中断在途请求。每个结果的源事件 ID 稳定，网络响应丢失最多重试三次同一结果事件。
6. 全部结果回写后发送完成屏障；后端继续做格式扫描、图片重编码、腾讯 IMS 内容审核及独立对象固化，再按有效槽位结算。
7. 供应商失败或付费结果未知时，结束当前尝试，由后端按已收到且审核通过的结果结算；不盲目重试付费请求。SIGTERM 或租约丢失保留日志，后续领取可复用已完成的提示词/图片，未知付费调用需要人工核对。

后端最多领取三次、任务创建 30 分钟后不再领取；调度器处理排队超时、取消及完成后的结算。服务健康仅代表轮询存活，不代表供应商生图质量合格。

## 配置（全部值通过未跟踪的 `.env.runtime` 或秘密托管注入）

|变量|用途|
|---|---|
|ZS_AI_CORE_URL|后端内部 origin；网络必须限制 Runtime 身份访问 internal-api，跨主机使用 HTTPS/mTLS。|
|ZS_INTERNAL_SECRET|与后端相同的内部签名密钥；不发送给供应商。|
|ZS_AI_API_KEY|apilio Bearer 密钥；不发送给后端或存储。|
|ZS_AI_BASE_URL|默认 https://api.apilio.ai/v1，需 HTTPS，不含凭据、查询串。|
|ZS_AI_MODEL|提示词模型，沿用既定 gpt-5.6-sol。|
|ZS_AI_TEMPERATURE|默认 0.7，范围 0–2。|
|ZS_AI_IMAGE_MODEL|必填，无默认值；必须先确认购买渠道实际支持的图片模型与同步返回协议。|
|ZS_AI_IMAGE_SIZE_FLAT|无 `imageOptions` 的历史平面任务尺寸，默认 `1024x1536`，保持原有 `宽x高` 自定义格式兼容。|
|ZS_AI_IMAGE_SIZE_ELEVATION|无 `imageOptions` 的历史立面任务尺寸，默认 `1536x1024`，保持原有 `宽x高` 自定义格式兼容。|
|ZS_AI_IMAGE_QUALITY|可选；`low/medium/high/auto`，留空不发送该参数。`high` 提升清晰度但单张费用更高。|
|ZS_AI_REQUEST_TIMEOUT_MS|单次供应商请求（chat/images/图片下载）超时，默认 180000；4K/高质量档建议 300000。范围 30000–600000。|
|ZS_AI_STORAGE_ORIGINS|逗号分隔的 HTTPS 私有 COS/CDN origin 白名单；必须包含签名读写 URL 的实际 origin。|
|ZS_AI_IMAGE_URL_ORIGINS|可选；图片回包为 URL 模式（如 gpt-image-2 经 apilio 代理返回 `webstatic.aiproxy.vip`）时允许下载的 HTTPS origin 白名单，逗号分隔。默认仅允许与 AI base 同源；渠道 CDN 不同源时必须显式配置。|
|ZS_AI_STORAGE_MODE|可选，默认 `https`。`local-fs` 为开发模式：后端 LocalObjectStorageAdapter 签发 `local://` 内网地址，引擎直接读写与后端共享的资产根目录（见下节）；绝不用于生产。|
|ZS_AI_STORAGE_ROOT|`ZS_AI_STORAGE_MODE=local-fs` 时必填；必须与后端 `zhongshu.design.asset.storage-root` 指向同一目录。|
|ZS_AI_DAILY_CALL_LIMIT|单个持久日志目录每日最多发起的供应商请求数（含提示词、失败及未知结果），1–10000；不是人民币费用上限。多目录部署需分别分配预算并在供应商账户再设总额度。|
|ZS_AI_JOURNAL_DIR|直接 Node 启动时必填；Compose 固定为 /data。|

`node src/main.mjs --check` 仅校验配置结构，不发起真实请求。`node --test test/*.test.mjs` 使用本地替身，无真实消费。`docker compose build` 构建镜像，`docker compose up -d` 会启动真实轮询，必须完成下方人工验收前置条件后再执行；本轮未执行此部署命令。

冻结选项对应的供应商请求尺寸如下：

|清晰度|方向|请求 `size`|
|---|---|---|
|2K|横向 `LANDSCAPE`|`2048x1152`|
|2K|竖向 `PORTRAIT`|`1152x2048`|
|4K|横向 `LANDSCAPE`|`3840x2160`|
|4K|竖向 `PORTRAIT`|`2160x3840`|

4K 使用 GPT Image 2 的 3840 最大边规格，总像素约 829 万，不请求 4096。`imageOptions` 同时作用于无参考图的 `images/generations` 和有参考图的 `images/edits`。Runtime 在上传和成功回写前读取 PNG IHDR 或 JPEG SOF，要求原生响应宽高与冻结档位精确一致；completed journal 的复用结果也执行相同的无付费复核。不匹配时以 `PROVIDER_IMAGE_SIZE_MISMATCH` 结束本次尝试，不重试付费调用，也不通过缩放伪装目标档位。单张输入和输出仍限制为 20MB；URL 或 `b64_json` 图片超限以 `PROVIDER_IMAGE_TOO_LARGE` 失败，图片 JSON 信封超过 20MB 图片的 base64 容量时以 `PROVIDER_IMAGE_RESPONSE_TOO_LARGE` 失败。代码已覆盖请求和响应像素合同，但供应商真实调用、画质及费用仍需账户验收。

## 本地真跑（local-fs 开发模式，T15 补）

前提：本地后端已按 `local,pg,zsdev` 启动（内部签名密钥用 zsdev 默认值即可），`后端程序/.env` 已注入 `ZS_AI_API_KEY`，并确认供应商支持的图像模型名。在 `ai-runtime/` 下执行：

```bash
ZS_AI_CORE_URL=http://127.0.0.1:48080 \
ZS_INTERNAL_SECRET=zsdev-internal-secret-0123456789abcdef \
ZS_AI_API_KEY=<真实 key> \
ZS_AI_IMAGE_MODEL=<渠道实际图像模型> \
ZS_AI_DAILY_CALL_LIMIT=50 \
ZS_AI_STORAGE_MODE=local-fs \
ZS_AI_STORAGE_ROOT="$TEMP/zhongshu-assets" \
ZS_AI_JOURNAL_DIR="$TEMP/zs-ai-journal" \
node src/main.mjs
```

引擎每 3 秒领取一次任务；小程序点「生成平面方案」后任务会在数秒内被领取并真实出图。`local-fs` 模式直读/直写与后端共享的资产根目录（含路径越界防护），绕开 `local://` 内网地址无法走 HTTPS 白名单的限制；生产切 COS 后仍走 `https` 白名单模式。

容器以非 root 身份运行，根目录只读，移除 capabilities，并限制内存、进程、CPU。日志卷包含提示词与图片，是业务数据，需要主机磁盘保护、限制访问和留存清理；不能作为公共静态目录。重启时保留日志卷。额度保留记录使用独占创建，多个进程共享同一目录时不会超额预留同一额度。不要通过删除日志或换目录绕过调用限制。

## 供应商验收前必须核对

### 与后端同步切换 COS

后端切换 COS 前先迁移本地历史资产。Runtime 同时设置 `ZS_AI_STORAGE_MODE=https` 和 `ZS_AI_STORAGE_ORIGINS=https://zhongshu-design-assets-1397776231.cos.ap-shanghai.myqcloud.com`，保留原日志目录以继续处理既有任务。Runtime 不需要 COS 永久密钥，只使用后端签发的输入 GET 和输出 PUT URL。平面和立面共用此链路，生成后的输出先进入 `ai-quarantine/`，校验后由后端固化到 `accepted/ai/`。

`node src/main.mjs --check` 只验证配置；真实 COS 权限、图片上传和任务结果仍需实际验收。

- 项目选择的是 apilio。图片模型的参数、响应形态、额度和计费需要供应商当前官方合同及实际账户验收。图片回包支持同步 `data[0].b64_json` 与 `data[0].url` 两种形态：URL 模式仅下载 `ZS_AI_IMAGE_URL_ORIGINS` 白名单内的 HTTPS origin、拒绝重定向、下载后重算魔数与摘要（已实测 apilio `gpt-image-2` 返回 `webstatic.aiproxy.vip` CDN 链接）。不支持任务 ID 异步轮询协议；若实际套餐采用该协议，必须补供应商适配器后才能启用，不得仅修改模型名后上线。
- 确认 chat 模型支持输入图片、temperature 和 max_tokens；确认图生图参数 `image[]`、单张 `n=1` 及图片模型输出格式。需求传给模型不保证建筑结构正确，结果仍需设计人员验收。
- 验收平面→选择→立面→选择→冻结版本，以及部分失败、超时、取消、进程重启、迟到回写；逐笔核对供应商实际费用与设计点结算。
- 为账户设置实际金额上限、只读用量核对和报警；对外出站仅开放所选供应商与存储域名。入口健康 /health（8081）不要当作可公开管理接口。

## 回退

先停止领取新任务，保留日志卷和所有账务事实；已有任务交给仍在运行的后端调度器结算。不得回退到会提前按首张图片结算的旧调度器，也不得恢复隔离区对象直接作为已审核资产的路径。供应商返回未知时按任务 ID 核对，不手工重放扣点流水。
