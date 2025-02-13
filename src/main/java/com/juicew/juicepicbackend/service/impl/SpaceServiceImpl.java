package com.juicew.juicepicbackend.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.juicew.juicepicbackend.exception.BusinessException;
import com.juicew.juicepicbackend.exception.ErrorCode;
import com.juicew.juicepicbackend.exception.ThrowUtils;
import com.juicew.juicepicbackend.manager.sharding.DynamicShardingManager;
import com.juicew.juicepicbackend.model.VO.SpaceVO;
import com.juicew.juicepicbackend.model.VO.UserVO;
import com.juicew.juicepicbackend.model.dto.space.SpaceAddRequest;
import com.juicew.juicepicbackend.model.dto.space.SpaceQueryRequest;
import com.juicew.juicepicbackend.model.entity.Space;
import com.juicew.juicepicbackend.model.entity.SpaceUser;
import com.juicew.juicepicbackend.model.entity.User;
import com.juicew.juicepicbackend.model.enums.SpaceLevelEnum;
import com.juicew.juicepicbackend.model.enums.SpaceRoleEnum;
import com.juicew.juicepicbackend.model.enums.SpaceTypeEnum;
import com.juicew.juicepicbackend.service.SpaceService;
import com.juicew.juicepicbackend.mapper.SpaceMapper;
import com.juicew.juicepicbackend.service.SpaceUserService;
import com.juicew.juicepicbackend.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
* @author nxbhx
* @description 针对表【space(空间)】的数据库操作Service实现
* @createDate 2024-12-31 00:36:41
*/
@Service
public class SpaceServiceImpl extends ServiceImpl<SpaceMapper, Space>
    implements SpaceService{

    @Resource
    private UserService userService;

    @Resource
    private TransactionTemplate transactionTemplate;

    @Resource
    private SpaceUserService spaceUserService;

//    @Resource
//    @Lazy
//    private DynamicShardingManager dynamicShardingManager;


    @Override
    public long addSpace(SpaceAddRequest spaceAddRequest, User loginUser) {
        //填充参数默认值
        Space space = new Space();
        BeanUtil.copyProperties(spaceAddRequest, space);
        if (StrUtil.isBlank(space.getSpaceName())){
            space.setSpaceName("默认空间");
        }
        if (space.getSpaceLevel() == null){
            space.setSpaceLevel(SpaceLevelEnum.COMMON.getValue());
        }
        if (space.getSpaceType() == null){
            space.setSpaceType(SpaceTypeEnum.PRIVATE.getValue());
        }
        //填充容量
        this.fillSpaceBySpaceLevel(space);
        //校验参数
        this.validSpace(space,true);

        //校验权限，非管理员只能创建普通级别空间
        Long userId = loginUser.getId();
        space.setUserId(userId);
        if(space.getSpaceLevel() != SpaceLevelEnum.COMMON.getValue() && !userService.isAdmin(loginUser)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR,"无权限创建指定级别的空间");
        }

        //每个用户只能创建一个私有空间,以及一个团队空间
        //todo 这里可以使用ConcurrentHashMap对锁优化
        //Map<Long, Object> lockMap = new ConcurrentHashMap<>();
        //public long addSpace(SpaceAddRequest spaceAddRequest, User user) {
        //    Long userId = user.getId();
        //    Object lock = lockMap.computeIfAbsent(userId, key -> new Object());
        //    synchronized (lock) {
        //        try {
        //            // 数据库操作
        //        } finally {
        //            // 防止内存泄漏
        //            lockMap.remove(userId);
        //        }
        //    }
        //}
        String lock = String.valueOf(userId).intern();//确保从字符串常量池中取到相同的对象
        synchronized (lock){
            //使用编程式事务
            Long newSpaceId = transactionTemplate.execute(status -> {
                //判断是否已有空间
                boolean exists = this.lambdaQuery()
                        .eq(Space::getUserId, userId)
                        //如果创建的是私有空间，则判断是否创建过私有的，创建团队空间，就判断是否创建过团队的
                        .eq(Space::getSpaceType, space.getSpaceType())
                        .exists();
                ThrowUtils.throwIf(exists && !userService.isAdmin(loginUser), ErrorCode.OPERATION_ERROR, "每类空间只能创建一个");
                //创建
                // 写入数据库
                boolean result = this.save(space);
                ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
                // 如果是团队空间，关联新增团队成员记录
                if (SpaceTypeEnum.TEAM.getValue() == spaceAddRequest.getSpaceType()) {
                    SpaceUser spaceUser = new SpaceUser();
                    spaceUser.setSpaceId(space.getId());
                    spaceUser.setUserId(userId);
                    spaceUser.setSpaceRole(SpaceRoleEnum.ADMIN.getValue());
                    result = spaceUserService.save(spaceUser);
                    ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "创建团队成员记录失败");
                }
                // 创建分表
                //当前分库分表关闭
//                dynamicShardingManager.createSpacePictureTable(space);
                // 返回新写入的数据 id
                return space.getId();


            });
            return newSpaceId;
        }
    }

    @Override
    public void validSpace(Space space, boolean add) {
        ThrowUtils.throwIf(space == null, ErrorCode.PARAMS_ERROR);
        String spaceName = space.getSpaceName();
        Integer spaceLevel = space.getSpaceLevel();
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(spaceLevel);
        Integer spaceType = space.getSpaceType();
        SpaceTypeEnum spaceTypeEnum = SpaceTypeEnum.getEnumByValue(spaceType);


        //添加数据时
        if(add){
            if(StrUtil.isBlank(spaceName)){
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间名不能为空");
            }
            if(spaceLevel == null){
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间级别不能为空");
            }
            if(spaceType == null){
                throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间类别不能为空");
            }
        }
        //修改数据时
        if(StrUtil.isNotBlank(spaceName) && spaceName.length() > 30){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间名不能为空或空间名过长");
        }
        if(spaceLevel != null && spaceLevelEnum == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间级别不存在");
        }
        if(spaceType != null && spaceTypeEnum == null){
            throw new BusinessException(ErrorCode.PARAMS_ERROR,"空间类别不存在");
        }

    }

    @Override
    public SpaceVO getSpaceVO(Space space, HttpServletRequest request) {
        // 对象转封装类
        SpaceVO spaceVO = SpaceVO.objToVo(space);
        // 关联查询用户信息
        Long userId = space.getUserId();
        if(userId != null && userId > 0){
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            spaceVO.setUser(userVO);
        }
        return spaceVO;
    }

    @Override
    public Page<SpaceVO> getSpaceVOPage(Page<Space> spacePage, HttpServletRequest request) {

        List<Space> spaceList = spacePage.getRecords();
        Page<SpaceVO> spaceVOPage = new Page<>(spacePage.getCurrent(), spacePage.getSize(), spacePage.getTotal());
        if (CollUtil.isEmpty(spaceList)) {
            return spaceVOPage;
        }
        // 对象列表 => 封装对象列表
        List<SpaceVO> spaceVOList = spaceList.stream()
                .map(SpaceVO::objToVo)
                .collect(Collectors.toList());

        // 1. 关联查询用户信息
        Set<Long> userIdSet = spaceList.stream()
                .map(Space::getUserId)
                .collect(Collectors.toSet());

        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));

        // 2. 填充信息
        spaceVOList.forEach(spaceVO -> {
            Long userId = spaceVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            spaceVO.setUser(userService.getUserVO(user));
        });
        spaceVOPage.setRecords(spaceVOList);
        return spaceVOPage;
    }

    @Override
    public QueryWrapper<Space> getQueryWrapper(SpaceQueryRequest spaceQueryRequest) {
        QueryWrapper<Space> queryWrapper = new QueryWrapper<>();
        if(spaceQueryRequest == null){
            return queryWrapper;
        }
        // 从对象中取值
        Long id = spaceQueryRequest.getId();
        Long userId = spaceQueryRequest.getUserId();
        String spaceName = spaceQueryRequest.getSpaceName();
        Integer spaceLevel = spaceQueryRequest.getSpaceLevel();
        String sortField = spaceQueryRequest.getSortField();
        String sortOrder = spaceQueryRequest.getSortOrder();
        Integer spaceType = spaceQueryRequest.getSpaceType();



        //拼接查询条件
        queryWrapper.eq(ObjUtil.isNotEmpty(id),"id",id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId),"userId",userId);
        queryWrapper.like(StrUtil.isNotBlank(spaceName), "spaceName", spaceName);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceLevel),"spaceLevel",spaceLevel);
        queryWrapper.eq(ObjUtil.isNotEmpty(spaceType), "spaceType", spaceType);
        // 排序
        queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), sortOrder.equals("ascend"), sortField);
        return queryWrapper;
    }

    @Override
    public void fillSpaceBySpaceLevel(Space space) {
        SpaceLevelEnum spaceLevelEnum = SpaceLevelEnum.getEnumByValue(space.getSpaceLevel());
        if(spaceLevelEnum != null){
            long maxSize = spaceLevelEnum.getMaxSize();
            if(space.getMaxSize() == null){
                space.setMaxSize(maxSize);
            }
            long maxCount = spaceLevelEnum.getMaxCount();
            if (space.getMaxCount() == null){
                space.setMaxCount(maxCount);
            }
        }
    }

    @Override
    public void checkSpaceAuth(User loginUser, Space space) {
        if(!space.getUserId().equals(loginUser.getId()) && !userService.isAdmin(loginUser)){
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }
}




