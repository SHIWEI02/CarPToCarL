# 本机构建环境（macOS / Apple Silicon）

> 这是 **fork 者为自己的机器**建立的构建基线，不属于上游内容。
> 上游默认把 `org.gradle.java.home` 和 `distributionUrl` 指向作者的 Windows 绝对路径，
> 在本机直接 `./gradlew` 会立即失败。本文记录**改了什么、为什么这么改、换机器怎么办**。
>
> 建立日期：2026-10-28

---

## 一、实测环境

| 项 | 值 |
|---|---|
| 机器 | Mac mini（Apple Silicon / arm64） |
| macOS | 26.4.1（build 25E253） |
| JDK | Homebrew `openjdk@17` → **17.0.20.1** |
| JDK 路径 | `/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home` |
| Android SDK | `~/Library/Android/sdk`（platform-tools / platforms;android-34 / build-tools;34.0.0） |
| Gradle | wrapper **8.10.2**，已缓存于 `~/.gradle/wrapper/dists/` |
| 系统 java | `/usr/bin/java` → **1.8.0_503**（仅用于启动 wrapper，见第四节） |

---

## 二、改了哪两个文件

### 2.1 `gradle.properties`

```properties
org.gradle.java.home=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
```

- **作用**：指定编译用的 JDK。AGP 8.7.3 + Gradle 8.10.2 要求 JDK 17+，
  系统默认的 JDK 8 会让编译极慢甚至卡死。
- **原值**：`C:/Users/jjuugk/Desktop/CarPToCarL/.tools/jdk-17.0.20.1+1`（作者的 Windows 路径）

### 2.2 `gradle/wrapper/gradle-wrapper.properties`

```properties
distributionUrl=https\://services.gradle.org/distributions/gradle-8.10.2-bin.zip
```

- **原值**：`file:///C:/Users/jjuugk/Desktop/cardeck/.tools/gradle-8.10.2-bin.zip`
- **为什么保留官方源而不是指向本地**：本机只有**解压后的缓存目录**
  （`~/.gradle/wrapper/dists/gradle-8.10.2-bin/a04bxjujx95o3nb99gddekhwo/gradle-8.10.2`），
  **没有 `.zip` 文件**；而 `distributionUrl` 只接受 zip 或 URL，不接受目录。
  官方源首次下载后即缓存，之后**不再联网**。

---

## 三、为什么不能用通配符 / 环境变量

`gradle.properties` 与 `gradle-wrapper.properties` 都是**纯 key=value 文本**，
Gradle **不做任何变量展开**：

| 写法 | 结果 |
|---|---|
| `${JAVA_HOME}` | 被当作字面目录名，报路径不存在 |
| `~/...` | `~` 不展开，报路径不存在 |
| `/opt/homebrew/*/jdk` | 不支持通配符 |
| `$HOME/...` | 同上，不展开 |

可行的替代方案（按推荐度排序）：

1. **用户级 `~/.gradle/gradle.properties`** 写 `org.gradle.java.home`
   —— 项目文件保持干净、可提交，换项目也生效
2. **环境变量 `JAVA_HOME`** —— 需写进 shell profile，或每次手动 export
3. **写死绝对路径（本 fork 当前采用）** —— 自包含、克隆即用，但换机器必须改

> 本项目选择方案 3，理由是这是一个个人 fork，优先「拉下来就能编」；
> 代价是这台机器换了路径就得改这一行。

---

## 四、不需要 `export JAVA_HOME`

实测（2026-10-28）：`env -u JAVA_HOME ./gradlew :app:assembleDebug` **可以直接成功**。

原因是一个两层结构：

```
/usr/bin/java (Java 8)          → 只负责启动 gradle-wrapper.jar
        ↓
Gradle Daemon (JDK 17)          → 真正执行编译，来自 org.gradle.java.home
```

`./gradlew -version` 的输出可以验证这一点：

```
Launcher JVM:  1.8.0_503 (Oracle Corporation 25.503-b01)
Daemon JVM:    '/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home' (from org.gradle.java.home)
```

**Launcher 是 Java 8 属正常现象**，只要 `Daemon JVM` 是 17 即可。若哪天启动器报
“Gradle requires Java 17”，说明系统 `java` 被换成了别的版本，此时才需要设置 `JAVA_HOME`。

---

## 五、换机器 / 换 JDK 怎么办

按顺序做三步：

1. 确认本机 JDK 17 的真实路径：
   ```bash
   /usr/libexec/java_home -v 17
   # Homebrew 安装的：ls -d /opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
   ```
2. 改 `gradle.properties` 里 `org.gradle.java.home` 那一行
3. 改 `local.properties` 的 `sdk.dir`（该文件已 gitignore，不随仓库走）

Intel Mac 或 Linux 同理，只需换路径，其余不变。

---

## 六、验证

```bash
./gradlew -version                          # Daemon JVM 应为 17
./gradlew :app:assembleDebug                # 产物 app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest                 # 纯 JVM 单测
bash scripts/check-no-vendor-artifacts.sh   # 合规守卫，必须返回 0
```

---

## 七、本 fork 与上游的差异边界

| 范围 | 状态 |
|---|---|
| `app/` 源码 | **与上游零差异**（`git diff upstream/main..HEAD -- app/` 为空） |
| `gradle.properties` / `gradle-wrapper.properties` | 仅本机路径（本文第二节） |
| 文档 | 本文（上游没有对应文件） |

也就是说：除构建配置外，代码始终跟随上游。同步上游用：

```bash
git fetch upstream && git merge upstream/main
```

合并时注意 `gradle.properties` 与 wrapper 两文件可能被上游覆盖回 Windows 路径，
需重新确认本机路径仍正确（`./gradlew -version` 看 Daemon JVM）。
