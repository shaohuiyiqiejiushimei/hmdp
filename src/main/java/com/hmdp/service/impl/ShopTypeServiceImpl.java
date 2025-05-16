package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.hmdp.dto.Result;
import com.hmdp.entity.ShopType;
import com.hmdp.mapper.ShopTypeMapper;
import com.hmdp.service.IShopTypeService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.apache.logging.log4j.message.ReusableMessage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

import java.util.ArrayList;
import java.util.List;

import static com.hmdp.utils.RedisConstants.CACHE_SHOP_TYPE_KEY;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class ShopTypeServiceImpl extends ServiceImpl<ShopTypeMapper, ShopType> implements IShopTypeService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    public Result queryTypeList() {
        //1.先查询缓存
        List<String> jsonTypes = stringRedisTemplate.opsForList().range(CACHE_SHOP_TYPE_KEY, 0, -1);
        List<ShopType> shopTypes = new ArrayList<>();
        //2.判空
        if(CollUtil.isNotEmpty(jsonTypes)){
            //2.List<String> => List<ShopType>
            for(String jsonType : jsonTypes){
                shopTypes.add(JSONUtil.toBean(jsonType,ShopType.class));
            }
            return Result.ok(shopTypes);
        }
        //3.查询不到，查数据库
        List<ShopType> shopTypes1 = query().orderByAsc("sort").list();
        List<String> jsonTypes1 = new ArrayList<>();
        if(CollUtil.isNotEmpty(shopTypes1)){
            //3.List<ShopType> => List<String>
            for(ShopType shopType : shopTypes1){
                jsonTypes1.add(JSONUtil.toJsonStr(shopType));
            }
            stringRedisTemplate.opsForList().rightPushAll(CACHE_SHOP_TYPE_KEY,jsonTypes1);

            return Result.ok(shopTypes1);
        }
        return Result.fail("数据不存在");
    }
}
