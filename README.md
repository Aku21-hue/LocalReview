# 本地生活服务点评平台（hmdp）

基于 Spring Boot 的本地生活点评后端，覆盖登录鉴权、商户检索、优惠券秒杀、
笔记点赞关注、签到等业务，重点解决高并发下的缓存与超卖问题。

## 技术栈
- Spring Boot 3.2.5 / JDK 17
- MyBatis-Plus / MySQL 8
- Redis 7（缓存、Lua 脚本、Stream 队列）
- RabbitMQ（订单异步落库）
- Redisson（分布式锁、布隆过滤器）

## 本地运行

### 1. 启动依赖中间件
```bash
docker-compose up -d
```
首次启动会自动导入 `src/main/resources/db/hmdp.sql`。

### 2. 配置
复制 `src/main/resources/application-example.yml` 为 `application-local.yml`，
按需修改数据库/Redis 密码。

### 3. 启动
```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

### 4. 访问
http://localhost:8081

## 目录结构
- `src/main/java` — 业务代码
- `src/main/resources/db/hmdp.sql` — 建表与初始数据
