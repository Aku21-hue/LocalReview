package com.hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.hmdp.utils.RedisConstants.*;

@Component
@Slf4j
public class CacheClient {

    private StringRedisTemplate stringRedisTemplate;

    private RedissonClient redissonClient;

    //商铺布隆过滤器
    private RBloomFilter<Long> shopBloomFilter;

    public CacheClient (StringRedisTemplate stringRedisTemplate, RedissonClient redissonClient) {
        this .stringRedisTemplate = stringRedisTemplate;
        this .redissonClient = redissonClient;
    }

    //初始化布隆过滤器
    public void initBloomFilter() {
        shopBloomFilter = redissonClient.getBloomFilter("shop:bloom");
        //预计元素 10000 个，误判率 1%
        shopBloomFilter.tryInit(10000L, 0.01);
        log.info("布隆过滤器初始化完成");
    }

    //对外暴露预热方法（供 ShopServiceImpl 启动时调用）
    public void loadShopIdsToBloom(List<Long> shopIds) {
        shopIds.forEach(id -> shopBloomFilter.add(id));
        log.info("预热 {} 个商铺 ID 到布隆过滤器", shopIds.size());
    }

    //判断 ID 是否可能存在
    public boolean mightContain(Long id) {
        return shopBloomFilter.contains(id);
    }

    //动态加入新商铺 ID
    public void addToBloom(Long id) {
        shopBloomFilter.add(id);
    }

    public void set(String key, Object value, Long time, TimeUnit unit) {
        try {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, unit);
        } catch (Exception e) {
            log.warn("Redis写入失败: {}", e.getMessage());
        }
    }

    public void setWithLogicalExpire(String key, Object value, Long time, TimeUnit unit) {
        //设置逻辑过期
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(unit.toSeconds(time)));

        //写入redis
        try {
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
        } catch (Exception e) {
            log.warn("Redis写入失败: {}", e.getMessage());
        }
    }

    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String json = null;
        try {
            json = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis读取失败，将查询数据库: {}", e.getMessage());
        }
        //2.判断存在
        if (StrUtil.isNotBlank(json)) {
            //3.存在 — 先判断是否是 RedisData 逻辑过期格式（兼容旧缓存）
            if (json.contains("\"data\"") && json.contains("\"expireTime\"")) {
                RedisData redisData = JSONUtil.toBean(json, RedisData.class);
                if (redisData.getData() != null) {
                    json = JSONUtil.toJsonStr(redisData.getData());
                } else {
                    // RedisData 里的 data 为 null，删除缓存重建
                    try {
                        stringRedisTemplate.delete(key);
                    } catch (Exception e) {
                        log.warn("Redis删除旧格式缓存失败: {}", e.getMessage());
                    }
                    json = null; // 让它走后面的 DB 查询逻辑
                }
            }
            if (json != null) {
                return JSONUtil.toBean(json, type);
            }
        }
        //判断命中的是否是空值
        if (json != null) {
            return null;
        }
        //4.不存在 根据id查询数据库
        R r = dbFallback.apply(id);
        //5.不存在 返回错误
        if (r == null) {
            //将空值写入redis
            try {
                stringRedisTemplate.opsForValue().set(key, "", CACHE_SHOP_TTL, TimeUnit.MINUTES);
            } catch (Exception e) {
                log.warn("Redis写入空值失败: {}", e.getMessage());
            }
            return null;
        }
        //6.存在 写入redis
        this.set(key, r, time, unit);
        //7.返回
        return r;
    }

    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    public <R, ID> R queryWithLogicalExpire(String keyPrefix, ID id, Class<R> type, Function<ID, R> dbFallback, Long time, TimeUnit unit) {
        String key = keyPrefix + id;
        //1.从redis查询商铺缓存
        String json = null;
        try {
            json = stringRedisTemplate.opsForValue().get(key);
        } catch (Exception e) {
            log.warn("Redis读取失败，将查询数据库: {}", e.getMessage());
        }
        //2.判断存在
        if (StrUtil.isBlank(json)) {
            //3.不存在 直接查数据库
            R r = dbFallback.apply(id);
            if (r != null) {
                this.setWithLogicalExpire(key, r, time, unit);
            }
            return r;
        }
        //4.命中 需要先把json反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        //兼容旧缓存格式：如果没有expireTime，说明是旧格式，删除缓存并从数据库重建
        if (expireTime == null || redisData.getData() == null) {
            try {
                stringRedisTemplate.delete(key);
            } catch (Exception e) {
                log.warn("Redis删除缓存失败: {}", e.getMessage());
            }
            R r = dbFallback.apply(id);
            if (r != null) {
                this.setWithLogicalExpire(key, r, time, unit);
            }
            return r;
        }
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //5.判断是否过期
        if (expireTime.isAfter(LocalDateTime.now())) {
            //5.1.未过期 直接返回店铺信息
            return r;
        }
        //5.2.已过期 需要缓存重建

        //6.缓存重建
        //6.1.获取互斥锁
        String lockKey = LOCK_SHOP_KEY + id;
        boolean isLock = tryLock(lockKey);
        //6.2.判断是否获取锁成功
        if (isLock) {
            //6.3.成功 开启独立线程 实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() -> {
                try {
                    //查询数据库
                    R r1 = dbFallback.apply(id);
                    //写入redis
                    this.setWithLogicalExpire(key, r1, time, unit);
                } catch (Exception e) {
                    log.error("缓存重建失败", e);
                } finally {
                    //释放锁
                    unLock(lockKey);
                }
            });
        }
        //6.4.返回过期的商铺信息
        return r;
    }

    /**
     * 布隆过滤器 + 缓存穿透双层防护
     */
    public <R> R queryWithBloom(String keyPrefix, Long id, Class<R> type,
                                Function<Long, R> dbFallback, Long time, TimeUnit unit) {
        // 第一道：布隆过滤器前置拦截
        if (!mightContain(id)) {
            // 布隆说不存在 → 一定不存在 → 直接返回，不查 Redis 不查 DB
            log.debug("布隆过滤器拦截非法ID: {}", id);
            return null;
        }
        // 第二道：走原有缓存穿透防护流程
        return queryWithPassThrough(keyPrefix, id, type, dbFallback, time, unit);
    }

    //尝试锁
    private boolean tryLock(String key) {
        try {
            Boolean flag = stringRedisTemplate.opsForValue().setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
            return BooleanUtil.isTrue(flag);
        } catch (Exception e) {
            log.warn("Redis获取锁失败: {}", e.getMessage());
            return false;
        }
    }

    //释放锁
    private void unLock(String key) {
        try {
            stringRedisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("Redis释放锁失败: {}", e.getMessage());
        }
    }
}
