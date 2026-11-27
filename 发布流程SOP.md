# OKK 双仓库发布流程 SOP

> 适用：OKK 微信全能体验增强模块（开源二改版，包名 `io.github.lovkem1.okk`）
> 整理时间：2026-11-27
> 本文件不含明文凭据，可随源码提交。明文密码见未入库的 `密钥管理文档.md`。

---

## 0. 两个仓库的分工

| 仓库 | 角色 | 存什么 | 谁维护 |
|---|---|---|---|
| `lovkem1/OKK` | **源码开发仓**（从 vip772/OKK fork） | 全部 Kotlin 源码、README（二改声明）、构建脚本、文档 | 你自己 push |
| `Xposed-Modules-Repo/io.github.lovkem1.okk` | **LSP 官方收录仓**（bot 建仓） | 只放 `README.md` + Release + APK，**不放源码** | bot 创建，你是维护者 |

**数据流**：源码仓改代码 → 本地编译正式签名 → 收录仓建 Release 挂 APK → 官网抓取展示。

官网模块页：`https://modules.lsposed.org/module/io.github.lovkem1.okk`

---

## 1. 版本号与 tag 规则（官方硬规范）

| 项 | 位置 | 当前值 |
|---|---|---|
| versionCode | `app/build.gradle.kts` → `defaultConfig.versionCode` | `18` |
| versionName | 同上 → `versionName` | `1.2.6` |
| Release tag | 收录仓 | `18-1.2.6`（= VersionCode-VersionName） |

**每次发版必须同时递增 versionCode 和 versionName，tag 用新的组合。** 旧 tag 一律不要复用。

---

## 2. 发布前检查

- [ ] 源码已 commit + push 到 `lovkem1/OKK`（当前最新 commit 记入日志）
- [ ] 包名/入口未被误改：`namespace`/`applicationId` = `io.github.lovkem1.okk`
- [ ] `xposed_init`、`java_init.list` 入口类名同步为新包名
- [ ] README 的“开源二改版”声明仍在
- [ ] GitHub secret scanning 无未解决告警（不得出现 `wx` 开头的硬编码 AppID）

---

## 3. 本地编译正式签名 APK

编译环境为绿色便携版，位于 `E:\GJ\WORKBUDDY\tools\`（JDK 17 + Android SDK API 37 + Gradle 9.6.1），**不进系统 PATH**。GitHub Actions 只能出 debug 签名，**正式版必须本地编译**。

PowerShell：

```powershell
$env:JAVA_HOME = "E:\GJ\WORKBUDDY\tools\jdk-17"
$env:ANDROID_HOME = "E:\GJ\WORKBUDDY\tools\android-sdk"
$env:ANDROID_SDK_ROOT = $env:ANDROID_HOME
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

# 正式签名：密码从密钥管理文档取，切勿写进脚本或提交
$env:OKK_STORE_FILE = "E:\GJ\WORKBUDDY\OKK\release-okk.keystore"
$env:OKK_STORE_PASSWORD = "<见密钥管理文档>"
$env:OKK_KEY_ALIAS = "okk"
$env:OKK_KEY_PASSWORD = "<见密钥管理文档>"

Set-Location "E:\GJ\WORKBUDDY\OKK"
& "E:\GJ\WORKBUDDY\tools\gradle-9.6.1\bin\gradle.bat" :app:assembleRelease
```

产物：`app\build\outputs\apk\release\app-release.apk`（R8 混淆 + 资源裁剪，约 5.5 MB）。
复制到 `output\OKK-<versionName>-release.apk` 备用。

> 环境坑：`compileSdk=37` 需 `platforms/android-37` 目录，而官方包名是 `android-37.2`，需把 `android-37.2` 复制为 `android-37` 供 AGP 识别。

---

## 4. 校验签名

```powershell
& "E:\GJ\WORKBUDDY\tools\android-sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs "output\OKK-<versionName>-release.apk"
```

核对 SHA-256 是否为 `8EE9D51459EAB449C459A82FCCE44D9390C8E4B2FE9DCB4B2610C33556E2DD08`。不一致说明用错 keystore，停止发布。

---

## 5. 收录仓建 Release（全新 tag）

**红线：GitHub Release 一旦 published 即 immutable**，不能改 tag、不能增删资产、不能改正文。发版只能建**全新 tag**。git 443 不稳定时统一走 API。

```bash
export GH_TOKEN=<classic token，仅本地使用，用完即吊销>

# 5.1 建 draft Release（tag = versionCode-versionName）
curl -s -X POST \
  https://api.github.com/repos/Xposed-Modules-Repo/io.github.lovkem1.okk/releases \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -d '{"tag_name":"<新tag>","name":"<Release标题>","body":"<正文>","draft":true,"prerelease":false}'
# 从响应里取 id → $RELEASE_ID

# 5.2 上传 APK（注意是 uploads.github.com，不是 api.github.com）
curl -s -X POST \
  "https://uploads.github.com/repos/Xposed-Modules-Repo/io.github.lovkem1.okk/releases/$RELEASE_ID/assets?name=OKK-<versionName>-release.apk" \
  -H "Authorization: Bearer $GH_TOKEN" \
  -H "Content-Type: application/octet-stream" \
  --data-binary @"output/OKK-<versionName>-release.apk"

# 5.3 确认无误后发布（draft → published，此后不可改）
curl -s -X PATCH \
  https://api.github.com/repos/Xposed-Modules-Repo/io.github.lovkem1.okk/releases/$RELEASE_ID \
  -H "Authorization: Bearer $GH_TOKEN" \
  -d '{"draft":false}'
```

仓库描述填模块显示名 `LOKK 微信全能体验增强模块`（用户指定 LOKK，非笔误）。

### 5.4 收录仓元数据文件（决定 App 里的「主页/源码/协作者」显示）

LSPosed 模块仓库 App 的「主页」「源码」「协作者」**不读 GitHub 仓库设置**（homepage 字段需 admin 权限，maintain 角色改不了），而是读收录仓根目录的约定文件：

| 文件 | 作用 | 当前内容 |
|---|---|---|
| `SOURCE_URL` | 主页 + 源码链接 | `https://github.com/lovkem1/OKK` |
| `SUMMARY` | 模块简介 | 微信全能体验增强模块… |
| `SCOPE` | 作用域包名 JSON 数组 | `["com.tencent.mm"]` |
| `ADDITIONAL_AUTHORS` | 协作者 JSON 数组 | `[{"type":"add","name":"lovkem1","link":"https://github.com/lovkem1"}]` |

用 Contents API 创建/更新这些文件即可（push 权限足够，不需要 admin）。缺失时 App 里主页/源码/协作者栏目不显示。

---

## 6. 官网收录确认

- bot 收录：`Xposed-Modules-Repo/submission` 提 issue `[submission] io.github.lovkem1.okk`，bot 自动建仓并邀请维护者。
- 已有 issue #2791，仓库已建成。
- 描述填好后，官网收录通常 **最多 3 个工作日**；期间 `modules.lsposed.org/module/io.github.lovkem1.okk` 返回 404 属正常。
- 收录后检查：模块页显示名、版本、APK 下载链接是否指向最新 Release。

---

## 7. 红线与历史踩坑

| 项 | 说明 |
|---|---|
| Release immutable | 发布后 tag / 资产 / 正文均不可改；只能删旧 Release+tag 后建全新 tag（删需 `delete_repo` 外的权限，classic token 注意） |
| 包名不兼容 | `io.github.lovkem1.okk` 与旧 `com.OKK.yes` 不兼容，用户需卸载旧模块重装 |
| Kotlin 命名空间冲突 | 方法体内写 `io.github.lovkem1.okk.xxx` 全限定名且文件有 `import java.io.*` 会报 `Unresolved reference 'github'`；改为 `import` + 短名 |
| 编译环境 | Android SDK 需 `android-37` 目录（由 `android-37.2` 复制）；compileSdk 37 |
| 签名 | 只用本地 `release-okk.keystore`；丢失无法对同包名覆盖升级 |
| Token | classic token 用完立即吊销，不写入任何仓库文件 |
| 敏感信息 | keystore、两份含密码文档、token 均不进公开仓库（已在 `.gitignore`） |
| 推送方式二选一 | 源码仓推送统一走 API **或** git，二者混用会让本地/远端 commit 分叉、`git push` 被拒；分叉时用 API 上传并 `git reset --hard origin/main` 对齐 |

---

## 8. 版本记录

| 版本 | 源码仓 tag | 收录仓 tag | 包名 | 签名 |
|---|---|---|---|---|
| 1.2.6 | `v1.2.6` | `18-1.2.6` | io.github.lovkem1.okk | 正式（V2，SHA256 8ee9d514…） |

> 源码仓用 `v` 前缀（历史惯例），收录仓用官方 `VersionCode-VersionName` 格式，两仓 tag 刻意不同。
> 源码仓无发行版时不会发生"重复推送"——两仓各自独立建 Release，源码仓是源码+文档+源码版 APK，收录仓只放 README+同一份 APK。
