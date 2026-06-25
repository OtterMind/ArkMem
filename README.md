# ArkMem

ArkMem 是一个面向 AI Agent 和 LLM 应用的 Java 原生长期记忆服务。它提供持久化记忆写入、层级作用域检索、PostgreSQL/pgvector 存储、结构化变更历史，以及 OpenAI-compatible 模型 Provider 接入。

项目边界保持清晰：ArkMem 只负责稳定记忆记录、可过滤元数据、语义检索和记忆生命周期。文件解析、文档切块、ACL、文档索引、外部数据连接器等能力应放在上游服务或独立组件中。

## 核心能力

| 能力 | 说明 |
| --- | --- |
| 记忆写入 | 支持 `POST /memories`，可直接写入或通过模型推理抽取 |
| 作用域 | 一等支持 `user_id`、`agent_id`、`run_id` |
| 检索 | 支持语义、关键词、混合检索 |
| 生命周期 | 支持读取、更新、软删除、批量删除、历史记录、重置 |
| 存储 | 使用 PostgreSQL + pgvector，schema 显式初始化 |
| Provider | 支持 `local`、`openai`、`openai-compatible`、`aliyun-bailian`、`auto` |
| 可观测性 | 支持 `X-Request-Id`、结构化错误、脱敏 HTTP 日志、`/configure` |

## 架构

```text
Client
  -> MemoryController
  -> MemoryService
  -> MemoryExtractor
       -> OpenAiLlmClient | HeuristicMemoryExtractor
  -> EmbeddingClient
       -> OpenAiEmbeddingClient | LocalHashEmbeddingClient
  -> PgVectorMemoryRepository
  -> PostgreSQL + pgvector
```

## 接口

| Method | Path | 说明 |
| --- | --- | --- |
| `GET` | `/api/health` | 健康检查 |
| `GET` | `/actuator/health` | Actuator 健康检查 |
| `GET` | `/v3/api-docs` | OpenAPI JSON |
| `GET` | `/swagger-ui.html` | Swagger UI |
| `GET` | `/configure` | 脱敏后的运行时配置 |
| `GET` | `/configure/providers` | 支持的 Provider |
| `POST` | `/generate-instructions` | 生成记忆抽取指令 |
| `POST` | `/memories` | 创建记忆 |
| `GET` | `/memories` | 按作用域列出记忆 |
| `GET` / `POST` | `/memories/query` | 分页查询记忆，支持元数据过滤 |
| `GET` | `/exact/memories` | 读取精确层级作用域 |
| `GET` | `/memories/{memoryId}` | 读取单条记忆 |
| `PUT` | `/memories/{memoryId}` | 更新单条记忆 |
| `DELETE` | `/memories/{memoryId}` | 软删除单条记忆 |
| `DELETE` | `/memories` | 按作用域批量软删除 |
| `GET` | `/memories/{memoryId}/history` | 读取记忆历史 |
| `POST` | `/search` | 搜索记忆 |
| `POST` | `/reset` | 清空记忆表和历史表 |

`POST /reset` 会清空记忆表和历史表，只建议在开发环境或受控维护窗口使用。

## 快速开始

启动 PostgreSQL：

```bash
docker compose up -d postgres
```

初始化 schema：

```bash
docker exec -i arkmem-postgres psql -U postgres -d arkmem < src/main/resources/db/schema.sql
```

使用本地抽取与本地哈希向量运行服务：

```bash
mvn spring-boot:run
```

开发环境默认配置：

```text
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/arkmem
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=postgres
ARKMEM_LLM_PROVIDER=local
ARKMEM_EMBEDDING_PROVIDER=local
```

## 配置

ArkMem 不要求提交任何密钥。生产环境请通过环境变量或部署平台的 secret manager 注入凭证。

| 变量 | 说明 |
| --- | --- |
| `SPRING_DATASOURCE_URL` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | PostgreSQL 用户名 |
| `SPRING_DATASOURCE_PASSWORD` | PostgreSQL 密码 |
| `ARKMEM_API_INTERNAL_TOKEN` | 可选的服务内部访问 token |
| `DASHSCOPE_API_KEY` | DashScope / Bailian / MaaS API key；未显式配置 `arkmem.*.api-key` 时使用 |
| `ARKMEM_LLM_PROVIDER` | `local`、`auto`、`openai`、`openai-compatible`、`aliyun-bailian` |
| `ARKMEM_LLM_BASE_URL` | OpenAI-compatible chat base URL |
| `ARKMEM_LLM_MODEL` | Chat model |
| `ARKMEM_EMBEDDING_PROVIDER` | `local`、`auto`、`openai`、`openai-compatible`、`aliyun-bailian` |
| `ARKMEM_EMBEDDING_BASE_URL` | OpenAI-compatible embedding base URL |
| `ARKMEM_EMBEDDING_MODEL` | Embedding model |
| `ARKMEM_EMBEDDING_LOCAL_DIMENSIONS` | 本地哈希向量维度 |
| `ARKMEM_PROMPT_LANGUAGE` | `en` 或 `zh` |

设置 `ARKMEM_API_INTERNAL_TOKEN` 后，受保护接口接受以下任一认证方式：

```text
Authorization: Bearer <token>
X-API-Key: <token>
```

## 请求示例

创建记忆：

```bash
curl -X POST http://localhost:19028/memories \
  -H 'Content-Type: application/json' \
  -d '{
    "messages": [
      {"role": "user", "content": "I prefer concise implementation-focused answers."}
    ],
    "user_id": "user-1",
    "metadata": {"source": "manual-curl"},
    "infer": false
  }'
```

读取记忆：

```bash
curl 'http://localhost:19028/memories?user_id=user-1'
curl 'http://localhost:19028/exact/memories?user_id=user-1&agent_id=chat-agent'
curl 'http://localhost:19028/memories/query?user_id=user-1&agent_id=chat-agent&source=manual-curl&limit=10&offset=0'
```

搜索记忆：

```bash
curl -X POST http://localhost:19028/search \
  -H 'Content-Type: application/json' \
  -d '{
    "query": "runnable curl examples",
    "user_id": "user-1",
    "search_mode": "hybrid",
    "top_k": 5
  }'
```

## 元数据过滤

| 类型 | 操作符 |
| --- | --- |
| 等值 | `eq`、`ne` |
| 集合 | `in`、`nin` |
| 数值 | `gt`、`gte`、`lt`、`lte` |
| 文本 | `contains`、`icontains` |
| 存在性 | `exists`、`*` |
| 逻辑 | `AND`、`OR`、`NOT` |

## Provider 解析

| Provider | 行为 |
| --- | --- |
| `local` | 使用启发式抽取和本地哈希向量 |
| `openai` | 使用 OpenAI chat completions 和 embeddings |
| `openai-compatible` | 使用自定义 OpenAI-compatible 网关 |
| `aliyun-bailian` | 使用 DashScope compatible mode；优先使用 `arkmem.*.api-key`，否则读取 `DASHSCOPE_API_KEY` |
| `auto` | 优先使用 `DASHSCOPE_API_KEY`，再使用 `OPENAI_API_KEY`，不存在时回退到本地模式 |

DashScope / MaaS Provider 配置示例：

```bash
export DASHSCOPE_API_KEY="your-api-key"
export ARKMEM_LLM_PROVIDER="aliyun-bailian"
export ARKMEM_EMBEDDING_PROVIDER="aliyun-bailian"
export ARKMEM_LLM_BASE_URL="https://llm-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
export ARKMEM_EMBEDDING_BASE_URL="https://llm-example.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
export ARKMEM_LLM_MODEL="qwen3-max"
export ARKMEM_EMBEDDING_MODEL="text-embedding-v4"
```

## 验证

```bash
mvn test
mvn test -Darkmem.integration-tests=true -Dtest=PgVectorMemoryRepositoryIntegrationTest
bash scripts/smoke-test.sh
```

使用自定义服务地址运行 smoke test：

```bash
BASE_URL=http://localhost:8081 bash scripts/smoke-test.sh
```

## Docker

```bash
docker build -t arkmem:local .

docker run --rm --network host \
  -e SPRING_DATASOURCE_URL="jdbc:postgresql://127.0.0.1:5432/arkmem" \
  -e SPRING_DATASOURCE_USERNAME="postgres" \
  -e SPRING_DATASOURCE_PASSWORD="postgres" \
  -e ARKMEM_LLM_PROVIDER="local" \
  -e ARKMEM_EMBEDDING_PROVIDER="local" \
  arkmem:local
```

## 代码结构

| 路径 | 职责 |
| --- | --- |
| `src/main/java/io/arkmem/memory` | 记忆领域模型、服务、仓库、搜索和过滤 |
| `src/main/java/io/arkmem/memory/controller` | 记忆 HTTP API |
| `src/main/java/io/arkmem/memory/llm` | Prompt、LLM 抽取、本地降级抽取 |
| `src/main/java/io/arkmem/memory/embedding` | Embedding clients |
| `src/main/java/io/arkmem/api` | OpenAPI、错误处理、HTTP 日志 |
| `src/main/resources/db/schema.sql` | 增量 schema |
| `scripts/recreate-arkmem-schema.sql` | 破坏性 schema 重建脚本 |
| `src/main/resources/prompts` | 英文和中文 prompt 模板 |
| `scripts/smoke-test.sh` | HTTP smoke test |

## 项目来源

ArkMem 受到 [mem0](https://github.com/mem0ai/mem0) 长期记忆设计启发，并将核心思路适配为 Java 与 Spring Boot 服务。
