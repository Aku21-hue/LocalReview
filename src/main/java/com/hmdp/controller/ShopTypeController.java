package com.hmdp.controller;


import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.service.IShopTypeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_TTL;

/**
 * <p>
 * 前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@RestController
@RequestMapping("/shop-type")
public class ShopTypeController {
    @Resource
    private IShopTypeService typeService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @GetMapping("list")
    public Result queryTypeList() {
        String key = CACHE_SHOP_TYPE_KEY + "list";
        try {
            //1.从redis查询缓存
            String shopTypeListJson = stringRedisTemplate.opsForValue().get(key);
            //2.判断是否存在
            if (StrUtil.isNotBlank(shopTypeListJson)) {
                //3.存在 直接返回
                List<ShopType> typeList = JSONUtil.toList(shopTypeListJson, ShopType.class);
                return Result.ok(typeList);
            }
        } catch (Exception e) {
            log.warn("Redis读取缓存失败，将从数据库查询: {}", e.getMessage());
        }
        //4.查询数据库
        List<ShopType> typeList = typeService
                .query().orderByAsc("sort").list();
        try {
            //5.写入redis
            stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonPrettyStr(typeList), CACHE_SHOP_TYPE_TTL, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("Redis写入缓存失败，忽略缓存: {}", e.getMessage());
        }
        //6.返回
        return Result.ok(typeList);
    }
}
