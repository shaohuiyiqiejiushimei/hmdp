--判断是否有下单的资格 通过库存和一人一单
-- 有哪些是要传进来的参数 --voucherId userId
--1.1 优惠卷id
local voucherId = ARGV[1]
--1.2 用户id
local userId = ARGV[2]

--2. 数据key
--2.1。库存key
local stockKey = "seckill:stock:" .. voucherId
--2.2 订单key
local orderKey = "seckill:order:" .. voucherId

--3. 判断资格
--3.1 获取库存
if(tonumber(redis.call("get",stockKey)) <=0 ) then
    --3.2. 库存不足，返回1
    return 1
end
--3.2 判断用户是否下过单
if(redis.call("sismember",orderKey,userId) == 1) then
    -- 3.3. 存在 说明是重复下单 返回2
    return 2
end

--4.1 给redis中的库存减一 在set集合中的userid加上
redis.call("incrby",stockKey,-1)
--4.2 下单(保存用户)
redis.call("sadd",orderKey,userId)
return 0