package com.hmdp.service.impl;

import cn.hutool.core.collection.CollUtil;
import com.hmdp.config.RedissonConfig;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static{
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    //基于阻塞队列实现异步下单秒杀
    private BlockingQueue<VoucherOrder>  orderTasks = new ArrayBlockingQueue<>(1024*1024);
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();



    /**
     * 基于lua脚本判断用户是否有资格下单
     * @param voucherId
     * @return
     */
    @Override
    public Result seckillVoucher(Long voucherId) {
        //获取用户
        String id = UserHolder.getUser().getId().toString();
//        ArrayList<String> keys = new ArrayList<>();
        List<String> keys = Arrays.asList(id, voucherId.toString());
        //1.执行Lua脚本 根据返回的结果
        Long result = stringRedisTemplate.execute(
                UNLOCK_SCRIPT, Collections.emptyList(),
                voucherId.toString(),id);
        //2.判断结果是为0
        int r = result.intValue();
        //2.1 不为零，代表没有购买资格
        if(r!=0){
            return Result.fail(r==1?"库存不足":"不能重复下单");
        }
        //2.2 为零 有购买资格，把下单信息保存到阻塞队列
        long orderId = redisWorker.nextId("order");
        // TODO 保存阻塞队列
        //3.返回订单id
        return Result.ok(orderId);

    }


    /**
     * 1.基于分布式锁进行下单
     * 2.步骤：
     *  做了一个双重检查
     * 然后再使用
     * @param voucherId
     * @return
     */
//    @Override
//    public Result seckillVoucher(Long voucherId) {
//        //1.根据id查询优惠卷
//        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
//        //2.查询是否已经在时间内
//        LocalDateTime beginTime = seckillVoucher.getBeginTime();
//        LocalDateTime endTime = seckillVoucher.getEndTime();
//        LocalDateTime now = LocalDateTime.now();
//        if(now.isBefore(beginTime)||now.isAfter(endTime)){
//            return Result.fail("还没开始呢");
//        }
//        log.info("开始查询库存是否充足");
//        //3.查询库存是否充足
//        Integer stock = seckillVoucher.getStock();
//        if(stock<1){
//            return Result.fail("库存不足啦");
//        }
//        //4.单体架构下，使用synchronized没问题
//          Long userId = UserHolder.getUser().getId();
////        synchronized (userId.toString().intern()) {
////            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
////            return proxy.createVoucherOrder(voucherId);
////        }
//        //4.创建锁对象
//       // SimpleRedisLock lock = new SimpleRedisLock(stringRedisTemplate, "order:" + userId);
//        RLock lock = redissonClient.getLock("lock:order" + userId);
//        //获取锁
//        boolean isLock = lock.tryLock();
//        //判断是否获取锁成功
//        if(!isLock){
//            return Result.fail("不允许重复下单");
//        }
//        try {
//            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
//            return proxy.createVoucherOrder(voucherId);
//        } catch (IllegalStateException e) {
//            throw new RuntimeException(e);
//        } finally {
//            lock.unlock();
//        }
//    }
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //5. 一人一单
        //5.1 查询订单,根据用户id和voucherId判断
        Long userId = UserHolder.getUser().getId();


        int count = query().eq("user_id", userId)
                .eq("voucher_id", voucherId).count();

        if(count>0){
            return Result.fail("别他妈的重复下单啊");
        }

        //4.扣减库存
        boolean result = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId).gt("stock",0)
                .update();
        if(!result){
            return Result.fail("库存不足");
        }


        //6.生成优惠卷订单
        VoucherOrder voucherOrder = new VoucherOrder();
        //6.1 生成订单，订单id
        long orderId = redisWorker.nextId("order");
        voucherOrder.setId(orderId);
        //6.2 用户id
        voucherOrder.setUserId(userId);
        //6.3 代金券id
        voucherOrder.setVoucherId(voucherId);

        boolean success = save(voucherOrder);
        if(!success){
            return Result.fail("系统错误");
        }

        //返回订单id
        return Result.ok(orderId);
    }
}
