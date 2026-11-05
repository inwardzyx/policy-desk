# 推上 GitHub（"可验证三件套"第 1 步）

> 为什么非做不可：简历上写"63 条测试 / 462 条测试"，
> **在没有可点仓库、没有 CI 徽章时，与编造不可区分。**
> 0 段实习的状态下，这是唯一能自己制造的信任信号。

---

## 1. 建两个仓库（网页操作，5 分钟）

```text
① github.com → New repository
   名字：policy-desk      可见性：Public（Private 的话面试官点不开 = 白做）
   不要勾 "Add a README"（本地已经有了）

② 再建一个：review-rag-cs  （旧项目的名字可以改，别用 _ 开头的原始目录名）
```

## 2. 推送（两个仓库分开推）

```powershell
# 新项目
cd D:\policy-desk
git remote add origin https://github.com/YOUR_GITHUB_NAME/policy-desk.git
git push -u origin main

# 旧项目
cd D:\_review_rag-cs
git remote add origin https://github.com/YOUR_GITHUB_NAME/review-rag-cs.git
git push -u origin main
```

★ 推送前确认两件事：

```text
☐ 没有任何 .env 被提交：  git ls-files | Select-String '\.env'
☐ 没有密钥字符串：        git grep -n "sk-" -- .        # 应为空
```

## 3. 让徽章亮起来（2 分钟）

推上去后，Actions 会自动跑一次。等它绿了，再改 README：

```text
在 D:\policy-desk\README.md 顶部找到那段被注释掉的徽章
→ 去掉 <!-- 与 --> 
→ 把 YOUR_GITHUB_NAME 换成你的用户名
→ 提交并推送
```

**顺序很重要**：先让 CI 跑绿，再贴徽章。反过来的话，前 10 分钟是一个红叉。

## 4. 在两个仓库之间互链（1 分钟）

```text
policy-desk     的 README 里写：算法服务在 https://github.com/YOU/review-rag-cs
review-rag-cs   的 README 里写：业务层与前端在 https://github.com/YOU/policy-desk
```

★ 这条不只是礼貌：面试官点进一个仓库，**看到它属于一个系统**，比看到孤零零一个 demo 强得多。

## 5. 验证（自己先当一次面试官）

```text
☐ 打开无痕窗口，点开仓库链接 —— 不登录也能看到 README
☐ README 首屏能看到：你那三个数字 + 演示视频链接
☐ Actions 标签页是绿的，点进去能看到 63 条 / 4 条的输出
☐ 「克隆 + docker compose up」那段说明，陌生人照着能做（他不一定有 Docker Desktop）
```

## 6. 简历里怎么写

```text
GitHub：github.com/YOUR_GITHUB_NAME/policy-desk   ← 可点，且 CI 绿
```

★ 不要写"项目代码见 GitHub"这种没有链接的话——筛简历的人不会去搜。

---

## 如果暂时不想公开

至少做 **Private + 给面试官开权限**，或者把 **README 截图 + 3 分钟视频**放进简历。
但坦白说：**0 实习 + Private 仓库，等于没有证据**。GitHub 的绿色徽章是你现在最便宜的可信度。
