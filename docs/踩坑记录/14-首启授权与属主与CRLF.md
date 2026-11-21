# 14 - 首次安装的授权、属主与脚本换行（v42 集中踩坑）

> 这一篇记录 v42 收尾阶段连续踩的四个坑。共同特征：**代码逻辑都对，但用户看到的是一句无关的英文报错或直接闪退**。
> 排查顺序建议：先看 `linux-launcher-status.txt`，再看 `log/desktop-start.log`，最后才怀疑业务逻辑。

---

## 坑 1：全新安装后「rootfs 解压失败 … permission denied」

### 现象

全新安装（卸载重装 / 新设备）后打开 App，提示解压失败，错误里带 `Permission denied`，进不去 Linux。
`linux-launcher-status.txt` 只有一行：

```
tar(app) exit=1 tar: /sdcard/UbuntuXFCE/rootfs.tar.gz: Permission denied
```

### 真因

全新安装的 App **一个权限都没有**。Android 11+ 上访问 `/sdcard` 需要「所有文件访问」（`MANAGE_EXTERNAL_STORAGE`），
否则 FUSE 直接拒绝，`tar` 连文件头都读不到。

旧代码只有「挂载 SD」菜单里检查了 `hasStorageAccess()`，**导入路径完全没检查**，
所以直接把 toybox tar 的英文原文甩给了用户。

### 解法

1. `LinuxLauncher.preImportBlocker(ctx)`：导入前自检，缺权限就返回中文说明；
   `TermuxActivity.launchLinuxIfNeeded()` 在起会话前拦下来，弹「需要先授权」+「去授权」按钮
   （`openStorageSettings()` 跳到系统的「所有文件访问」页）。返回后 `onResume` 会自动重试。
2. `PadMenu.importRootfs()` 同样加一道检查（重新导入时 rootfs 已就绪，不能用 `preImportBlocker`，
   那里会因为「系统已就绪」直接放行，必须直接查 `hasStorageAccess`）。
3. `explainExtractError()` 把 tar 的英文报错翻译成可执行动作（权限 / 空间不足 / 找不到包）。

### 注意

**不能把「没有 root」当成阻断条件**。设备可能压根没 root，这时本来就走 proot；
proot 是在用户态伪装 uid 的，以 App 身份解压完全可行（只是文件属主都是 App uid，容器内看到的是 root）。

---

## 坑 2：`su` 不可用时整个导入直接崩，连兜底都走不到

### 现象

`linux-launcher-status.txt`：

```
reimport failed: Cannot run program "su": error=13, Permission denied
```

### 真因

KernelSU 没给这个 UID 授权时，`Runtime.exec("su")` 直接抛 `EACCES`。
旧代码让它冒泡出去，**`if (code != 0)` 的「以 App 身份解压」兜底分支永远执行不到**。

### 解法

`extractRootfs()` 里把 root 解压整段包进 try/catch，失败就 `code = -1` 继续走 App 身份兜底。

---

## 坑 3：在 KernelSU 里授权了，App 还是说「没有 root」

### 现象

用户卸载重装后去 KernelSU 手动授权，回到 App 点「切到 root 模式」，仍然提示没有可用 root，只能杀 App 才生效。

### 真因（两个叠加）

1. **App 侧缓存**：`hasRoot()` 把「su 不可用」这个**否定结果永久缓存**在静态字段 `sSuManagerVerified` 里
   （注释写的是「进程生命周期内缓存」）。授权前实测到 false，之后一直吃这个缓存。
2. **KernelSU 侧 uid 陈旧**：重装后 Android 分配了**新 uid**（本机实测 10167 → 10183），
   而 `/data/adb/ksu/.allowlist` 里那条 `com.termux` 还指着旧 uid。
   在管理器里点授权，写回的仍是旧值 —— 所以「我明明授权了」其实授给了一个不存在的安装。

### 解法

1. `hasRoot()` 的缓存改成带 TTL：**肯定结果长期有效，否定结果只缓存 15 秒**；
   并新增 `invalidateRootCache()`，每次点「切换模式」先作废缓存再实测。
2. `tools/fix_ksu_allowlist.py`：直接修 KernelSU 落盘的白名单。
   - uid **动态取**：优先读 App 自己写的 `$FILES/.app-uid`，拿不到才 `stat` / `pm list packages -U`
   - 条目按**包名**定位，不认任何 uid 常量
   - 幂等（一致就不改），改前自动备份 `.allowlist.bak`

### KernelSU 白名单文件格式（实测）

```
offset 0   : b"USK\x7f"     魔数
offset 4   : u32            版本
offset 8   : u32            条目数（不可靠，别用来切分）
offset 12  : 条目区
条目内     : name[256] + uid(u32) + flags(u32) + ... + domain[256]
```

即：找到包名的 256 字节名字段，**其后 4 字节就是该包的 uid**。

---

## 坑 4：脚本被写成 CRLF → 闪退 + 变量里带 `\r`

### 现象

root 模式一点就闪退，终端横幅里 `App UID` 是空的，桌面起不来。`log/desktop-start.log`：

```
start-xfce.sh[19]: can't open /data/data/com.termux//.app-uid: No such file or directory
start-xfce.sh[262]: syntax error: unmatched 'if'
```

### 真因

在 Windows 上用 Python 文本模式改写脚本，把整份文件的换行从 LF 变成了 **CRLF**。
toybox sh 把 `\r` 当成命令的一部分：

- 解析阶段直接 `syntax error: unmatched 'if'` → 脚本退出 → 用户看到「闪退」
- 赋值 `FD=/data/data/com.termux/files\r` → 拼路径变成 `.../files\r/.app-uid`，
  显示出来像 `//`，报 `No such file or directory` —— **看起来像「uid 没传过去」，其实文件好好的**

### 解法

1. 6 个脚本全部转回 LF，`sh -n` 语法检查全过，再从 APK 里 `unzip -p` 出来复查 `0d` 字节数 = 0。
2. 加防线：
   - `.gitattributes`：`*.sh text eol=lf`
   - `tools/check_assets_lf.sh`：打包前跑一次，按**字节**判 `0d`
     （第一版用 `tr -dc '\r'` 是错的：不同实现的转义解释不一致，会把普通字母 `r` 也数进去，误报 18 个文件）

### 教训

在 Windows 上改 shell 脚本，**必须用二进制模式写文件**（`open(f,'wb')` / `newline='\n'`），
改完立刻跑一次 `check_assets_lf.sh`。这个坑的症状全都指向别处，非常费时间。

---

## 坑 5：`fix_perms` 失败却写了标记 → sudo 永久坏掉

### 现象

以 App 身份（proot 阶段）导入 rootfs，后来配好 root 切 chroot，桌面能起但 `sudo` 报：

```
sudo: /etc/sudo.conf is owned by uid 10183, should be 0
sudo: /usr/bin/sudo 必须属于用户 ID 0(的用户)并且设置 setuid 位
```

### 真因

1. **以非 root 解压会丢 setuid**：内核不允许普通用户给文件设 setuid root，tar 静默丢弃这些位；
   同时 umask 077 会把 755 压成 700（所以看到 `-rwx------`）。
2. `ensureRootOwnership()` 第一版**无条件写标记**，而当时 `fix_perms.sh` 正被写成 CRLF 跑不起来
   → 标记写了、权限没修 → 之后每次启动都跳过校正 → sudo 永久坏掉。

### 解法

1. `runFixPerms()` 改为返回 `boolean`：要求**退出码 0 且输出含 `fix_perms done`** 才算成功。
2. 标记只在成功后才写，且**内容带版本号**（`PERMS_MARKER_VERSION`）——
   以后 `fix_perms.sh` 逻辑有改动，改版本号即可让老系统自动重跑一次。
3. 手动补救（本次就是这么修的）：删掉 `.rootfs-root-owned`，以 root 跑 `sh $FILES/fix_perms.sh`，
   校验 `sudo` 是 `-rwsr-xr-x root root`，chroot 里 `sudo -n id -u` 返回 `0`，再写回标记 `2`。

### 顺带确认

发布包 `Ubuntu-XFCE-rootfs-v42.tar.gz` 里的 setuid 是完整的（`tar tzvf` 可见 `-rwsr-xr-x`），
问题只出在「以 App 身份解压」这条路径上。
