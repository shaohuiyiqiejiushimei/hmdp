package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.ObjectUtil;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{
    private StringRedisTemplate stringRedisTemplate;
    private String name;
    private static final String KEY_PREFIX = "lock:";
    private static final String ID_PREFIX = UUID.randomUUID().toString(true);
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }
    public SimpleRedisLock(StringRedisTemplate stringRedisTemplate, String name) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //获取线程表示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        //获取锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(  KEY_PREFIX+name, threadId , timeoutSec, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(success);
    }

//    @Override
//    public void unlock() {
//        //获取线程表示
//        String threadId = ID_PREFIX + Thread.currentThread().getId();
//        //根据锁表示获取值
//        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX+name);
//        if(threadId.equals(id)) {
//            stringRedisTemplate.delete(name);
//        }
//    }


    @Override
    public void unlock() {
        //调用lua脚本
        stringRedisTemplate
                .execute(UNLOCK_SCRIPT,
                        Collections.singletonList(KEY_PREFIX+name),
                        ID_PREFIX + Thread.currentThread().getId());
    }
}
