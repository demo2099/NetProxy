# 签名材料（有意入库）

CI 用这里的密钥给 release 包签名，所以**不配置任何 secrets 也能出正式签名包**，
而且每次构建的签名都一样 —— 这是「覆盖安装升级」的前提。

| 项 | 值 |
|---|---|
| keystore | `keystore/interstellar.p12`（PKCS12，RSA 2048，有效期 100 年） |
| alias | `interstellar` |
| 口令 | `zlbJnVGJatjOgkddwDbtj6Ps`（storePassword 与 keyPassword 相同） |
| 证书 SHA-256 | `B1:98:82:77:3B:51:83:CC:2C:EE:E2:73:87:CD:00:78:B3:05:21:52:CA:97:70:A9:ED:D3:AC:ED:93:10:E1:A3` |
| 文件 SHA-256 | `fb6849a6299bf0cae67d3c5b8baae3a308a082e265de467200fc1a1e2c4252b2` |

口令写在 `keystore/signing.properties` 里，`app/build.gradle.kts` 会自动读取。

## ⚠️ 风险（请知悉）

本仓库是 **public** 的，所以这个私钥等于公开。任何人都能用它签一个
**能覆盖安装到你手机上**的假包 —— 而这个 App 会接管全部流量。
请只从本仓库的 Releases 页下载 APK，不要装来源不明的同名包。

想收紧的话，见下面「改用 secrets」。

## 为什么必须签正式名

Android 要求「覆盖安装」时新包的签名与已装版本**完全一致**。所以：

- **正式签名包** → 可以直接覆盖安装升级，不用卸载。
- **debug 签名包**（文件名带 `-debugsigned`）→ 签名随构建机变化，升级必须先卸载。
- **未签名包** → 直接报「安装包未包含任何证书」，根本装不上（v0.5.3 / v0.5.4 就是
  这样，因为当时没有签名材料，且 `outputFileName` 覆盖掉了 AGP 的 `-unsigned` 后缀）。

CI 里有个 `Verify APK signatures` 步骤会卡住未签名的产物，不会再漏出去。

## 换新密钥

```bash
keytool -genkeypair -keystore keystore/interstellar.p12 -storetype PKCS12 \
  -alias interstellar -keyalg RSA -keysize 2048 -validity 36525 \
  -dname "CN=Interstellar, OU=Android, O=Interstellar, L=Hong Kong, ST=Hong Kong, C=CN"
```

换完记得同步更新 `keystore/signing.properties` 和本文件。
**注意**：换密钥后所有已装用户都要**先卸载再装**一次。

## 改用 secrets（不再把私钥放进仓库）

1. 把 `keystore/interstellar.p12` 转成 base64：
   ```bash
   base64 -w0 keystore/interstellar.p12 > ks.b64
   ```
2. 到 GitHub → Settings → Secrets and variables → Actions 新建：

   | Secret | 值 |
   |---|---|
   | `INTERSTELLAR_KEYSTORE_B64` | `ks.b64` 的内容 |
   | `INTERSTELLAR_STORE_PASSWORD` | `zlbJnVGJatjOgkddwDbtj6Ps` |
   | `INTERSTELLAR_KEY_ALIAS` | `interstellar` |
   | `INTERSTELLAR_KEY_PASSWORD` | `zlbJnVGJatjOgkddwDbtj6Ps` |

3. 确认 CI 跑通后，`git rm -r keystore/` 并提交。

workflow 里 secrets 的优先级高于本目录，所以配好之后即使文件还在也会优先用 secrets。
