package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisData;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


import javax.annotation.Resource;

import java.time.LocalDateTime;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {
    @Resource
    StringRedisTemplate stringRedisTemplate;

    private static final  ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);
    /**
     * 手动预热缓存 解决缓存击穿问题
     * 设计逻辑过期时间
     * @param id
     * @param expireSeconds
     */
    public void saveShop2Redis(Long id,Long expireSeconds) throws InterruptedException {
        //1.查询店铺数据
        Shop shop = query().eq("id", id).one();
        Thread.sleep(200);

        //2.设置逻辑过期时间
        RedisData redisData = new RedisData();
        redisData.setData(shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(expireSeconds));
        //3.写入redis
        stringRedisTemplate.opsForValue().
                set(CACHE_SHOP_KEY+id,JSONUtil.toJsonStr(redisData));
    }
    /**
     * 1.解决缓存穿透：
     *      *  当所要查询的数据，在数据库和缓存中都不存在，可以缓存一个null对象给
     * @param id
     * @return
     */
    @Override
    public Result queryById(Long id) {
        //缓存穿透
        //Shop shop = queryWithPassThrough(id);

        //互斥锁解决缓存击穿
        Shop shop = queryWithMutex(id);

        //逻辑过期解决缓存击穿
        //Shop shop = queryWithLogicalExpire(id);
        if(shop == null){
            return Result.fail("店铺不存在");
        }
        return Result.ok(shop);
    }


    public Shop queryWithLogicalExpire(Long id){
        String key = CACHE_SHOP_KEY + id ;
        //1.从redis中查询缓存 使用字符串格式生成
        String shopJson = stringRedisTemplate.opsForValue().get(key);

        //2.判断是否存在
        if(StrUtil.isBlank(shopJson)){
            //返回给前端，字符串格式需要转换为对象
            return null;
        }

        //3.命中，需要判断缓存是否过期
        //3.1 json字符串 =>  对象
        RedisData redisData = JSONUtil.toBean(shopJson, RedisData.class);
        LocalDateTime expireTime = redisData.getExpireTime();
        JSONObject data = (JSONObject)redisData.getData();
        Shop shop = JSONUtil.toBean(data, Shop.class);
        LocalDateTime now = LocalDateTime.now();
        //3.2判断是否过期
        //4.1 未过期，直接返回店铺信息
        if(expireTime.isAfter(now)){
            return shop;
        }
        //4.2 已过期，需要缓存重建
        //5.尝试获取互斥锁

        boolean isLock = tryLock(LOCK_SHOP_KEY+id);
        //5.1 获取锁
        //5.2 获取锁失败，返回过期的商铺信息
        if(isLock){
            //5.3 获取锁成功，开启独立线程，实现缓存重建
            CACHE_REBUILD_EXECUTOR.submit(() ->{
                //重建缓存
                try {
                    log.info("现在开始重建缓存");
                    this.saveShop2Redis(id,20L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    //释放锁
                    unLock(LOCK_SHOP_KEY);
                }
            });
        }



        return shop;
    }
    /**
     * 使用互斥锁解决缓存击穿问题
     * @param id
     * @return
     */
    public Shop queryWithMutex(Long id) {
        /*
        直接使用 StrUtil.isEmpty(shopJson) 会将 null 和 "" 都视为异常，导致无法查询数据库。
         */

        String key = CACHE_SHOP_KEY + id ;
        //1.从redis中查询缓存 使用字符串格式生成
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否为空
        if(StrUtil.isNotBlank(shopJson)){
            //返回给前端，字符串格式需要转换为对象
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //不可以直接
        //考虑了几种写法，都没有黑马老师给的这种好
//        if(StrUtil.isNotEmpty(shopJson)){
//            return Result.fail("别乱搞了");
//        }
        if(shopJson!=null){
            return null;
        }
        Shop shop = null;
        try {
            //3.尝试获取锁，从数据库获取值，写入数据库
            boolean isLock = tryLock(LOCK_SHOP_KEY + id);
            if(!isLock){
                Thread.sleep(10);
                return  queryWithMutex(id);
            }

            //双重检查 有可能在获取锁成功之后，其他线程已经完成重建
            String key1 = CACHE_SHOP_KEY + id ;
            //1.从redis中查询缓存 使用字符串格式生成
            String shopJson1 = stringRedisTemplate.opsForValue().get(key1);
            //2.判断是否为空
            if(StrUtil.isNotBlank(shopJson1)){
                //返回给前端，字符串格式需要转换为对象
                Shop shop1 = JSONUtil.toBean(shopJson1, Shop.class);
                return shop1;
            }


            //4.获取锁成功，查数据库，然后写入数据库
            shop = query().eq("id", id).one();
            // 模拟重建的延时
            Thread.sleep(200);
            if(ObjectUtil.isEmpty(shop)){
                //1.为了解决缓存穿透问题，
                //2.选择缓存一个空对象"",
                //3.下次再查询这个值，直接返回错误
                stringRedisTemplate.opsForValue()
                        .set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
                return null;
            }
            //写入缓存，需要现将对象转换为json字符串
            String cacheShop = JSONUtil.toJsonStr(shop);
            stringRedisTemplate.opsForValue()
                    .set(CACHE_SHOP_KEY+id,cacheShop,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            //释放锁
            unLock(LOCK_SHOP_KEY+id);
        }

        return shop;
    }

    /**
     * 尝试获取锁
     * @param
     * @return
     */
    public boolean tryLock(String key){
        Boolean aBoolean = stringRedisTemplate.opsForValue().
                setIfAbsent(key, "1", 10, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(aBoolean);
    }

    public boolean unLock(String key){
        Boolean reuslt = stringRedisTemplate.delete(key);
        return BooleanUtil.isTrue(reuslt);
    }

    /**
     * 解决缓存穿透
     * @param id
     * @return
     */
    public Shop queryWithPassThrough(Long id){
        /*
        直接使用 StrUtil.isEmpty(shopJson) 会将 null 和 "" 都视为异常，导致无法查询数据库。
         */

        String key = CACHE_SHOP_KEY + id ;
        //1.从redis中查询缓存 使用字符串格式生成
        String shopJson = stringRedisTemplate.opsForValue().get(key);
        //2.判断是否为空
        if(StrUtil.isNotBlank(shopJson)){
            //返回给前端，字符串格式需要转换为对象
            Shop shop = JSONUtil.toBean(shopJson, Shop.class);
            return shop;
        }
        //不可以直接
        //考虑了几种写法，都没有黑马老师给的这种好
//        if(StrUtil.isNotEmpty(shopJson)){
//            return Result.fail("别乱搞了");
//        }
        if(shopJson!=null){
            return null;
        }
        //3.如果不存在，查数据库，然后写入数据库
        Shop shop = query().eq("id", id).one();
        if(ObjectUtil.isEmpty(shop)){
            //1.为了解决缓存穿透问题，
            //2.选择缓存一个空对象"",
            //3.下次再查询这个值，直接返回错误
            stringRedisTemplate.opsForValue()
                    .set(CACHE_SHOP_KEY+id,"",CACHE_NULL_TTL,TimeUnit.MINUTES);
            return null;
        }
        //写入缓存，需要现将对象转换为json字符串
        String cacheShop = JSONUtil.toJsonStr(shop);
        stringRedisTemplate.opsForValue()
                .set(CACHE_SHOP_KEY+id,cacheShop,CACHE_SHOP_TTL, TimeUnit.MINUTES);
        return shop;
    }
    /**
     * 1.先更数据库，再删除缓存。
     *
     * @param shop
     */
    @Override
    @Transactional
    public Result update(Shop shop) {
        if(BeanUtil.isEmpty(shop)){
            return Result.fail("参数为空");
        }
        //1.先修数据库
        boolean result = updateById(shop);
        if(!result){
            return Result.fail("系统错误");
        }
        //2.再删除缓存
        result = stringRedisTemplate.delete(CACHE_SHOP_KEY);
        if(!result){
            return Result.fail("缓存删除失败");
        }
        return Result.ok();
    }
}
