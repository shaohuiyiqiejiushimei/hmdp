package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RegexUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.cache.CacheProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import javax.servlet.http.HttpSession;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Slf4j
@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {
    @Resource
    StringRedisTemplate stringRedisTemplate;
    @Override
    //1.参数校验
    public Result sendCode(String phone) {
        log.info("我在准备发送验证码");
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确");
        }
        //生成随机验证码
        String code = RandomUtil.randomNumbers(6);
        //存进redis里面,登录的时候再取出来
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY+phone,code, RedisConstants.LOGIN_CODE_TTL, TimeUnit.MINUTES);
        log.info("发送验证码成功{}",code);
        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm) {
        String phone = loginForm.getPhone();
        String code = loginForm.getCode();
        log.info("我在获取参数");
        //1.获取参数=》参数校验
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式不正确");
        }
        //3.获取验证码
         String cacheCode= stringRedisTemplate.opsForValue().get(LOGIN_CODE_KEY+phone);
        //3.1 参数校验
        if( cacheCode == null || !cacheCode.equals(code)){
            return Result.fail("验证码错误");
        }
        //4.判断是注册还是登录 =》使用手机号查询 是否存在
        User user = query().eq("phone", phone).one();
        //4.1 用户已存在 直接返回
        //4.2 用户不存在，需要保存用户信息
        if(ObjectUtil.isEmpty(user)){
            user = createUserWithPhone(phone);
        }
        //5.生成jwt，并且返回，用户每次登录时解析jwt获取用户信息
        String token = UUID.randomUUID().toString(true);
        UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
        HashMap<String, String> userMap = new HashMap<>();
        // 使用三元运算符处理 id 字段
        userMap.put("id", userDTO.getId() != null ? userDTO.getId().toString() : null);
        // 使用三元运算符处理 nickName 字段
        userMap.put("nickName", userDTO.getNickName() != null ? userDTO.getNickName() : null);
        // 使用三元运算符处理 icon 字段
        userMap.put("icon", userDTO.getIcon() != null ? userDTO.getIcon() : null);



        //7.3 存储
        String tokenKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.opsForHash().putAll(tokenKey,userMap);
        //7.4.设置token有效期
        stringRedisTemplate.expire(tokenKey,LOGIN_USER_TTL,TimeUnit.SECONDS);

        //8.返回token
        return Result.ok(token);
    }

    private User createUserWithPhone(String phone) {
        //1.创建用户
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX + RandomUtil.randomString(10));
        // 2.保存用户 mp的语法
        save(user);
        return user;
    }
}
