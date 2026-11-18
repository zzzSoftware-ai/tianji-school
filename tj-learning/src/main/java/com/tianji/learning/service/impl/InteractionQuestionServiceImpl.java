package com.tianji.learning.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tianji.api.cache.CategoryCache;
import com.tianji.api.client.course.CatalogueClient;
import com.tianji.api.client.course.CategoryClient;
import com.tianji.api.client.course.CourseClient;
import com.tianji.api.client.search.SearchClient;
import com.tianji.api.client.user.UserClient;
import com.tianji.api.dto.course.CataSimpleInfoDTO;
import com.tianji.api.dto.course.CourseSimpleInfoDTO;
import com.tianji.api.dto.user.UserDTO;
import com.tianji.common.domain.dto.PageDTO;
import com.tianji.common.exceptions.BadRequestException;
import com.tianji.common.utils.BeanUtils;
import com.tianji.common.utils.CollUtils;
import com.tianji.common.utils.StringUtils;
import com.tianji.common.utils.UserContext;
import com.tianji.learning.domain.dto.QuestionFormDTO;
import com.tianji.learning.domain.po.InteractionQuestion;
import com.tianji.learning.domain.po.InteractionReply;
import com.tianji.learning.domain.query.QuestionAdminPageQuery;
import com.tianji.learning.domain.query.QuestionPageQuery;
import com.tianji.learning.domain.vo.QuestionAdminVO;
import com.tianji.learning.domain.vo.QuestionVO;
import com.tianji.learning.mapper.InteractionQuestionMapper;
import com.tianji.learning.service.IInteractionQuestionService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tianji.learning.service.IInteractionReplyService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * <p>
 * 互动提问的问题表 服务实现类
 * </p>
 *
 * @author author
 * @since 2026-11-10
 */
@Service
@RequiredArgsConstructor
public class InteractionQuestionServiceImpl extends ServiceImpl<InteractionQuestionMapper, InteractionQuestion> implements IInteractionQuestionService {

    private final IInteractionReplyService replyService;
    private final UserClient userClient;
    private final CourseClient courseClient;
    private final SearchClient searchClient;
    private final CatalogueClient catalogueClient;
    private final CategoryClient categoryClient;
    private final CategoryCache categoryCache;

    @Override
    public void saveQuestion(QuestionFormDTO questionDTO) {
        //1.获取当前登录用户的id
        Long userId = UserContext.getUser();
        //2.数据封装
        InteractionQuestion question = BeanUtils.copyBean(questionDTO, InteractionQuestion.class);
        question.setUserId(userId);
        //3.写入数据库
        save(question);
    }

    @Override
    public PageDTO<QuestionVO> queryQuestionPage(QuestionPageQuery query) {
        //1.校验参数,课程id不能为空
        Long courseId = query.getCourseId();
        Long sectionId = query.getSectionId();
        if(sectionId == null && courseId == null){
            throw new BadRequestException("课程id和小节id不能同时为空");
        }
        //2.分页查询
        Page<InteractionQuestion> page = lambdaQuery()
                .select(InteractionQuestion.class,info->!info.getProperty().equals("description"))
                .eq(query.getOnlyMine(), InteractionQuestion::getUserId, UserContext.getUser())
                .eq(courseId != null, InteractionQuestion::getCourseId, courseId)
                .eq(sectionId != null, InteractionQuestion::getSectionId, sectionId)
                .eq(InteractionQuestion::getHidden, false)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if(CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }
        //3.根据id查询提问者和最近一次回答的信息
        Set<Long> userIds = new HashSet<>();
        Set<Long> answerIds = new HashSet<>();
        //3.1得到问题当中的提问者id和最近一次回答的id
        for (InteractionQuestion q : records) {
            //只查询非匿名的问题
            if (!q.getAnonymity()) {
                userIds.add(q.getUserId());
            }
            answerIds.add(q.getLatestAnswerId());
        }
        //3.2根据id查询最近一次回答
        answerIds.remove(null);
        Map<Long,InteractionReply> replyMap = new HashMap<>(answerIds.size());
        if(CollUtils.isNotEmpty(answerIds)){
            List<InteractionReply> replies = replyService.listByIds(answerIds);
            for (InteractionReply reply : replies) {
                replyMap.put(reply.getId(),reply);
                if(!reply.getAnonymity()){
                    userIds.add(reply.getUserId());
                }
            }
        }

        //3.3根据id查询用户信息(提问者)
        userIds.remove(null);
        Map<Long, UserDTO> userMap = new HashMap<>(userIds.size());
        if(CollUtils.isNotEmpty(userIds)){
            List<UserDTO> users = userClient.queryUserByIds(userIds);
            userMap = users.stream()
                    .collect(Collectors.toMap(UserDTO::getId, u -> u));
        }

        //4.封装VO
        List<QuestionVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion r : records) {
            //4.1将PO转为VO
            QuestionVO vo = BeanUtils.copyBean(r, QuestionVO.class);
            voList.add(vo);
            //4.2封装提问者信息
            if(!r.getAnonymity()){
                UserDTO userDTO = userMap.get(r.getUserId());
                if(userDTO !=null ){
                    vo.setUserName(userDTO.getName());
                    vo.setUserIcon(userDTO.getIcon());
                }
            }
            //4.3封装最新一次回答的信息
            InteractionReply reply = replyMap.get(r.getLatestAnswerId());
            if( reply!= null ){
                vo.setLatestReplyContent(reply.getContent());
                if(!reply.getAnonymity()){
                    vo.setLatestReplyUser(userMap.get(reply.getUserId()).getName());
                }
            }
        }

        return PageDTO.of(page,voList);
    }

    @Override
    public QuestionVO queryQuestionById(Long id) {
        //1.根据id查询数据
        InteractionQuestion question = getById(id);
        //2.数据校验
        if(question==null || question.getHidden()){
            //没有数据或者被隐藏了
            return null;
        }
        //3.查询提问者信息
        UserDTO user = new UserDTO();
        if(!question.getAnonymity()){
            user = userClient.queryUserById(id);
        }
        //4.封装VO
        QuestionVO vo = BeanUtils.copyBean(question, QuestionVO.class);
        if(user !=null){
            vo.setUserName(user.getName());
            vo.setUserIcon(user.getIcon());
        }
        return vo;
    }

    @Override
    public PageDTO<QuestionAdminVO> queryQuestionPageAdmin(QuestionAdminPageQuery query) {
        List<Long> courseIds = null;
        //1.处理课程名称得到课程id
        if(StringUtils.isNotBlank(query.getCourseName())){
            courseIds = searchClient.queryCoursesIdByName(query.getCourseName());
            if(CollUtils.isEmpty(courseIds)){
                return PageDTO.empty(0L,0L);
            }
        }
        //2.分页查询
        Integer status = query.getStatus();
        LocalDateTime begin = query.getBeginTime();
        LocalDateTime end = query.getEndTime();
        Page<InteractionQuestion> page = lambdaQuery()
                .in(courseIds != null, InteractionQuestion::getCourseId, courseIds)
                .eq(status != null, InteractionQuestion::getStatus, status)
                .gt(begin != null, InteractionQuestion::getCreateTime, begin)
                .lt(end != null, InteractionQuestion::getCreateTime, end)
                .page(query.toMpPageDefaultSortByCreateTimeDesc());
        List<InteractionQuestion> records = page.getRecords();
        if(CollUtils.isEmpty(records)){
            return PageDTO.empty(page);
        }
        //3.准备VO需要的数据,用户数据,课程数据,章节数据,分类数据
        Set<Long> userIds = new HashSet<>();
        Set<Long> cIds = new HashSet<>();
        Set<Long> cataIds = new HashSet<>();
        //3.1获取各种数据的id集合
        for (InteractionQuestion q : records) {
            userIds.add(q.getUserId());
            cIds.add(q.getCourseId());
            cataIds.add(q.getChapterId());
            cataIds.add(q.getSectionId());
        }
        //3.2根据id查询用户
        List<UserDTO> users = userClient.queryUserByIds(userIds);
        Map<Long,UserDTO> userMap = new HashMap<>(users.size());
        if(CollUtils.isNotEmpty(userMap)){
            userMap = users.stream()
                    .collect(Collectors.toMap(UserDTO::getId,u->u));
        }
        //3.3根据id查询课程
        List<CourseSimpleInfoDTO> cInfos = courseClient.getSimpleInfoList(cIds);
        Map<Long,CourseSimpleInfoDTO> cInfoMap = new HashMap<>(cInfos.size());
        if(CollUtils.isNotEmpty(cInfos)){
            cInfoMap = cInfos.stream()
                    .collect(Collectors.toMap(CourseSimpleInfoDTO::getId,u->u));
        }
        //3.4根据id查询章节
        List<CataSimpleInfoDTO> catas = catalogueClient.batchQueryCatalogue(cataIds);
        Map<Long,String> cataMap = new HashMap<>(catas.size());
        if(CollUtils.isNotEmpty(catas)){
            cataMap = catas.stream()
                    .collect(Collectors.toMap(CataSimpleInfoDTO::getId,CataSimpleInfoDTO::getName));
        }
        //4.封装VO
        List<QuestionAdminVO> voList = new ArrayList<>(records.size());
        for (InteractionQuestion q : records) {
            //4.1将PO转VO
            QuestionAdminVO vo = BeanUtils.copyBean(q, QuestionAdminVO.class);
            voList.add(vo);
            //4.2用户信息
            UserDTO user = userMap.get(q.getUserId());
            if(user!=null){
                vo.setUserName(user.getName());
            }
            //4.3课程信息以及分类信息
            CourseSimpleInfoDTO cInfo = cInfoMap.get(q.getCourseId());
            if(cInfo!=null){
                vo.setCourseName(cInfo.getName());
                vo.setCategoryName(categoryCache.getCategoryNames(cInfo.getCategoryIds()));
            }
            //4.4章节信息
            vo.setChapterName(cataMap.getOrDefault(q.getChapterId(),""));
            vo.setSectionName(cataMap.getOrDefault(q.getSectionId(),""));
        }
        return PageDTO.of(page,voList);
    }
}
