package mao.spring_boot_redis_hmdp.utils;

import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import mao.spring_boot_redis_hmdp.dto.RedisData;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;

import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Project name(项目名称)：spring_boot_redis_hmdp_redis_utils
 * Package(包名): mao.spring_boot_redis_hmdp.utils
 * Class(类名): RedisUtils
 * Author(作者）: mao
 * Author QQ：1296193245
 * GitHub：https://github.com/maomao124/
 * Date(创建日期)： 2022/5/14
 * Time(创建时间)： 20:48
 * Version(版本): 1.0
 * Description(描述)： 无
 */

@Slf4j
@Component
public class RedisUtils
{

    @Resource
    StringRedisTemplate stringRedisTemplate;

    //线程池
    private static final ExecutorService CACHE_REBUILD_EXECUTOR = Executors.newFixedThreadPool(10);

    /**
     * 向redis里添加数据
     *
     * @param redisKey   redis的key
     * @param value      数据
     * @param expireTime 过期时间
     * @param timeUnit   时间单位
     */
    public void set(String redisKey, Object value, Long expireTime, TimeUnit timeUnit)
    {
        stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(value), expireTime, timeUnit);
    }


    /**
     * 向redis里添加数据 设置逻辑过期
     *
     * @param redisKey   redis的key
     * @param value      数据
     * @param expireTime 过期时间
     * @param timeUnit   时间单位
     */
    public void setWithLogicalExpire(String redisKey, Object value, Long expireTime, TimeUnit timeUnit)
    {
        RedisData redisData = new RedisData();
        //添加数据
        redisData.setData(value);
        //设置过期时间
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(expireTime)));
        //放入redis
        stringRedisTemplate.opsForValue().set(redisKey, JSONUtil.toJsonStr(redisData));
    }


    /**
     * 查询数据，有缓存，解决缓存穿透问题，未解决缓存雪崩问题
     *
     * @param keyPrefix  redisKey的前缀
     * @param id         id
     * @param type       返回值的类型
     * @param dbFallback 查询数据库的函数
     * @param expireTime 过期时间
     * @param timeUnit   时间单位
     * @param <R>        返回值的类型
     * @param <ID>       id的类型
     * @return 泛型R
     */
    public <R, ID> R queryWithPassThrough(String keyPrefix, ID id, Class<R> type,
                                          Function<ID, R> dbFallback, Long expireTime, TimeUnit timeUnit)
    {
        //获得前缀
        String redisKey = keyPrefix + id;
        //查询redis
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        //判断是否为空
        if (StrUtil.isNotBlank(json))
        {
            //不为空，返回
            return JSONUtil.toBean(json, type);
        }
        //判断是否为空串
        if (json != null)
        {
            //空串
            return null;
        }
        //null
        //查数据库
        R r = dbFallback.apply(id);
        //判断
        if (r == null)
        {
            //数据库也为空，缓存空值
            this.set(redisKey, "", expireTime, timeUnit);
            return null;
        }
        //数据库存在，写入redis
        this.set(redisKey, r, expireTime, timeUnit);
        //返回
        return r;
    }

    /**
     * 查询数据，有缓存，解决缓存穿透问题，解决缓存雪崩问题
     *
     * @param keyPrefix                      redisKey的前缀
     * @param id                             id
     * @param type                           返回值的类型
     * @param dbFallback                     查询数据库的函数
     * @param expireTime                     过期时间
     * @param timeUnit                       时间单位
     * @param <R>                            返回值的类型
     * @param <ID>                           id的类型
     * @param maxTimeSecondsByCacheAvalanche this.set(redisKey, r,
     *                                       timeUnit.toSeconds(expireTime)+getIntRandom(0,maxTimeSecondsByCacheAvalanche), TimeUnit.SECONDS);
     * @return 泛型R
     */
    public <R, ID> R queryWithPassThroughAndCacheAvalanche(String keyPrefix, ID id, Class<R> type,
                                                           Function<ID, R> dbFallback, Long expireTime, TimeUnit timeUnit,
                                                           Integer maxTimeSecondsByCacheAvalanche)
    {
        //获得前缀
        String redisKey = keyPrefix + id;
        //查询redis
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        //判断是否为空
        if (StrUtil.isNotBlank(json))
        {
            //不为空，返回
            return JSONUtil.toBean(json, type);
        }
        //判断是否为空串
        if (json != null)
        {
            //空串
            return null;
        }
        //null
        //查数据库
        R r = dbFallback.apply(id);
        //判断
        if (r == null)
        {
            //数据库也为空，缓存空值
            this.set(redisKey, "",
                    timeUnit.toSeconds(expireTime) + getIntRandom(0, maxTimeSecondsByCacheAvalanche),
                    TimeUnit.SECONDS);
            return null;
        }
        //数据库存在，写入redis
        this.set(redisKey, r,
                timeUnit.toSeconds(expireTime) + getIntRandom(0, maxTimeSecondsByCacheAvalanche),
                TimeUnit.SECONDS);
        //返回
        return r;
    }

    /**
     * @param keyPrefix     redisKey的前缀
     * @param lockKeyPrefix 锁的前缀
     * @param id            id
     * @param type          返回值的类型
     * @param dbFallback    查询数据库的函数
     * @param time          过期时间
     * @param timeUnit      时间单位
     * @param <R>           返回值的类型
     * @param <ID>          id的类型
     * @return 泛型R
     */
    public <R, ID> R queryWithLogicalExpire(String keyPrefix, String lockKeyPrefix, ID id, Class<R> type,
                                            Function<ID, R> dbFallback, Long time, TimeUnit timeUnit)
    {
        //获得前缀
        String redisKey = keyPrefix + id;
        //查询redis
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        //判断是否为空
        if (StrUtil.isBlank(json))
        {
            //空，返回
            return null;
        }
        //不为空
        //json 反序列化为对象
        RedisData redisData = JSONUtil.toBean(json, RedisData.class);
        //获得过期时间
        LocalDateTime expireTime = redisData.getExpireTime();
        //获取数据
        R r = JSONUtil.toBean((JSONObject) redisData.getData(), type);
        //判断是否过期
        if (expireTime.isAfter(LocalDateTime.now()))
        {
            //未过期，返回
            return r;
        }
        //过期，缓存重建
        //获取互斥锁
        String lockKey = lockKeyPrefix + id;
        boolean isLock = tryLock(lockKey);
        if (isLock)
        {
            //获取锁成功
            // 开辟独立线程
            CACHE_REBUILD_EXECUTOR.submit(new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        R r1 = dbFallback.apply(id);
                        setWithLogicalExpire(redisKey, r1, time, timeUnit);
                    }
                    catch (Exception e)
                    {
                        throw new RuntimeException(e);
                    }
                    finally
                    {
                        //释放锁
                        unlock(lockKey);
                    }
                }
            });
        }
        //没有获取到锁，使用旧数据返回
        return r;
    }


    /**
     * 获取锁
     *
     * @param key redisKey
     * @return 获取锁成功，返回true，否则返回false
     */
    private boolean tryLock(String key)
    {
        Boolean result = stringRedisTemplate.opsForValue().setIfAbsent(key, "1",
                RedisConstants.LOCK_SHOP_TTL, TimeUnit.SECONDS);
        return BooleanUtil.isTrue(result);
    }

    /**
     * 释放锁
     *
     * @param key redisKey
     */
    private void unlock(String key)
    {
        stringRedisTemplate.delete(key);
    }


    /**
     * 获取一个随机数，区间包含min和max
     *
     * @param min 最小值
     * @param max 最大值
     * @return int 型的随机数
     */
    @SuppressWarnings("all")
    private int getIntRandom(int min, int max)
    {
        if (min > max)
        {
            min = max;
        }
        return min + (int) (Math.random() * (max - min + 1));
    }
}