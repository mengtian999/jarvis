# App Store Connect listing (Bit Jarvis)

- `metadata/zh-Hans.json` / `metadata/en-US.json` — 名称、副标题、描述、关键词、隐私/支持/营销 URL
- `screenshots/iphone-6.7/` — 自动生成的宣发截图（可再替换为真机 UI 截图）
- `../docs/legal/terms-of-use.html` — 用户协议（工具类；非 AIGC 内容服务声明）
- `../scripts/asc-sync-listing.py` — 用 ASC API 写入文案（需本机 PyJWT）

定位：**本地优先的端侧 Agent 工具（BYOK）**，不在商店侧声明为生成式 AI 内容服务。
