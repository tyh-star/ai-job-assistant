# AI Job Assistant Frontend

React + Vite + TypeScript MVP for the Spring Boot API at `http://localhost:8080/api/v1`.

独立前端项目，通过现有后端接口工作。不修改 Writer、Tailor、PDF 或数据库逻辑。

## Prerequisites / 前置条件

- Node.js 20+
- Backend running on `http://localhost:8080`

## Setup / 安装

```bash
cd frontend
npm install
```

Copy the API base URL / 复制环境变量：

```bash
cp .env.example .env
```

`.env.example` contains:

```
VITE_API_BASE_URL=http://localhost:8080/api/v1
```

## Run / 启动

```bash
npm run dev
```

Open `http://localhost:5173`.

## Build

```bash
npm run build
```

## Notes

- Log in or register, then use Dashboard, Resumes, Jobs, and Tailor.
- JWT is stored in `localStorage` and sent as `Authorization: Bearer <token>`. Tokens, API keys, and local file paths are never rendered.
- Language defaults to Chinese when the browser language is Chinese, otherwise English. Use **中文 / EN** in the top-right corner. The choice is saved in `localStorage`.
- PDF resumes can confirm structure and generate a canonical DOCX. Tailor supports CONSERVATIVE (generate now) and AGGRESSIVE (confirm selected changes).
- 登录后 JWT 保存在 `localStorage`，请求自动带上 Bearer token。页面不会显示 token、API key 或本机绝对路径。
- 浏览器语言为中文时默认中文，否则 English。右上角可切换，选择会持久化。
