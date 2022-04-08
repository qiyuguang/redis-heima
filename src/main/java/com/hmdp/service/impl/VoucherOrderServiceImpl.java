package com.hmdp.service.impl;

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
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.hmdp.utils.RedisConstants;
/**
 * <p>
 * 服务实现类
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

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        SECKILL_SCRIPT.setResultType(long.class);
    }

    private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024*1024);

    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();
@PostConstruct
    private void init(){
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    private class VoucherOrderHandler implements Runnable{

        public void run(){
            while (true){
                try {
                    //获取订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    //创建订单
                    createVoucherOrder(voucherOrder);
                } catch (InterruptedException e) {
                    log.error("处理订单异常",e);
                }

            }
        }
    }

    private void createVoucherOrder(VoucherOrder voucherOrder) {
        Long voucherId = voucherOrder.getVoucherId();
        Long userId = voucherOrder.getId();
        //        创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
//        尝试获取锁
        boolean isLock = redisLock.tryLock();
//        判断
        if (isLock){
            //获取锁失败，直接返回失败 或者重试
            log.error("不允许重复下单");
            return;
        }
        try {
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                 log.error("不允许重复下单");
                return;
            }
            //5.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                log.error("不允许重复下单");
                return;
            }


            save(voucherOrder);

        }finally {
            //释放锁
            redisLock.unlock();
        }
    }

    @Override
    public Result seckillVoucher(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        //执行lua脚本
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString()
        );
        //判断结果是否为0
        int r = result.intValue();
        if (r != 0){
            //不为0 代表没有购买资格
            return Result.fail(r == 1 ? "库存不足":"不能重复下单");
        }
        //为0 有购买资格 把下单信息保存到阻塞队列
        long orderId = redisWorker.nextId("order");
        VoucherOrder voucherOrder = new VoucherOrder();
        //订单id
        voucherOrder.setId(orderId);
        //用户id
        voucherOrder.setUserId(userId);
        //代金券id
        voucherOrder.setVoucherId(voucherId);
        //放入阻塞队列
        orderTasks.add(voucherOrder);
        return Result.ok(orderId);
    }
    /*@Override
    public Result seckillVoucher(Long voucherId) {
        //1.查询优惠券
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        //2.判断秒杀是否开始
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        //3.判断秒杀是否结束
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }
        //4.判断库存是否充足
        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }
        return createVoucherOrder(voucherId);
    }
    @Resource
    private RedissonClient redissonClient;
    @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();
//        创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
//        尝试获取锁
        boolean isLock = redisLock.tryLock();
//        判断
        if (isLock){
            //获取锁失败，直接返回失败 或者重试
            return Result.fail("不允许重复下单");
        }
        try {
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return Result.fail("用户已经购买过");
            }
            //5.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                return Result.fail("库存不足");
            }
            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderId = redisWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //返回订单Id
            return Result.ok(orderId);
        }finally {
            //释放锁
            redisLock.unlock();
        }*/
        /*@Resource
        private StringRedisTemplate stringRedisTemplate;
        @Transactional
        public Result createVoucherOrder(Long voucherId) {
            //一人一单
            Long userId = UserHolder.getUser().getId();
//        创建锁对象
            SimpleRedisLock redisLock = new SimpleRedisLock("order:" + userId,stringRedisTemplate);
//        尝试获取锁
            boolean isLock = redisLock.tryLock(1200);
//        判断
            if (isLock){
                //获取锁失败，直接返回失败 或者重试
                return Result.fail("不允许重复下单");
            }
            try {
                int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
                if (count > 0) {
                    return Result.fail("用户已经购买过");
                }
                //5.扣减库存
                boolean success = seckillVoucherService.update()
                        .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0)
                        .update();
                if (!success) {
                    return Result.fail("库存不足");
                }
                //6.创建订单
                VoucherOrder voucherOrder = new VoucherOrder();
                //订单id
                long orderId = redisWorker.nextId("order");
                voucherOrder.setId(orderId);
                //用户id
                voucherOrder.setUserId(userId);
                //代金券id
                voucherOrder.setVoucherId(voucherId);
                save(voucherOrder);
                //返回订单Id
                return Result.ok(orderId);
            }finally {
                //释放锁
                redisLock.unlock();
            }
           }*/


//    }

   /* @Transactional
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId = UserHolder.getUser().getId();
        synchronized (userId.toString().intern()) {
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            if (count > 0) {
                return Result.fail("用户已经购买过");
            }
            //5.扣减库存
            boolean success = seckillVoucherService.update()
                    .setSql("stock=stock-1").eq("voucher_id", voucherId).gt("stock", 0)
                    .update();
            if (!success) {
                return Result.fail("库存不足");
            }
            //6.创建订单
            VoucherOrder voucherOrder = new VoucherOrder();
            //订单id
            long orderId = redisWorker.nextId("order");
            voucherOrder.setId(orderId);
            //用户id
            voucherOrder.setUserId(userId);
            //代金券id
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            //返回订单Id
            return Result.ok(orderId);
        }
    }*/
}
