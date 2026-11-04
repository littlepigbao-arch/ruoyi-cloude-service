package com.ruoyi.ai.service.impl;

import com.ruoyi.ai.domain.AiWorkbook;
import com.ruoyi.ai.mapper.AiWorkbookMapper;
import com.ruoyi.ai.service.AiWorkbookService;
import com.ruoyi.common.core.domain.R;
import com.ruoyi.common.security.utils.SecurityUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.ai.service.WorkbookVersionService;

import java.util.Date;
import java.util.List;
import com.ruoyi.ai.util.ContentHashUtils;

/**
 * AI 报表文档服务实现
 *
 * @author ruoyi
 */
@Service
public class AiWorkbookServiceImpl implements AiWorkbookService
{
    private static final Logger log = LoggerFactory.getLogger(AiWorkbookServiceImpl.class);

    @Autowired
    private AiWorkbookMapper workbookMapper;

    @Autowired
    private WorkbookVersionService versionService;

    @Autowired
    private com.ruoyi.ai.agent.AgentWriteGuard agentWriteGuard;

    @Override
    public R<List<AiWorkbook>> list()
    {
        Long userId = getUserId();
        if (userId == null)
        {
            return R.fail(503, "未登录或登录已过期");
        }
        List<AiWorkbook> list = workbookMapper.selectListByUserId(userId);
        return R.ok(list);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<AiWorkbook> save(AiWorkbook workbook)
    {
        Long userId = getUserId();
        if (userId == null)
        {
            return R.fail(503, "未登录或登录已过期");
        }

        if (workbook == null || workbook.getName() == null || workbook.getName().trim().isEmpty())
        {
            return R.fail("文档名称不能为空");
        }

        String username = SecurityUtils.getUsername();
        workbook.setUserId(userId);
        workbook.setName(workbook.getName().trim());
        workbook.setContentHash(ContentHashUtils.sha256(workbook.getContent()));

        if (workbook.getWorkbookId() == null)
        {
            // 新增：校验同名文档
            if (workbookMapper.countByNameAndUserId(workbook.getName(), userId, null) > 0)
            {
                return R.fail("文件名已存在");
            }
            if (workbook.getType() == null || workbook.getType().trim().isEmpty())
            {
                workbook.setType("created");
            }
            workbook.setCreateBy(username);
            workbook.setCreateTime(new Date());
            workbookMapper.insertWorkbook(workbook);
            versionService.initializeWorkbook(workbook, userId, username);
        }
        else
        {
            agentWriteGuard.manual(workbook.getWorkbookId(), userId);
            // 更新：需校验归属，防止越权更新他人文档
            AiWorkbook exist = workbookMapper.selectById(workbook.getWorkbookId(), userId);
            if (exist == null)
            {
                return R.fail("文档不存在或无权访问");
            }
            return R.fail(409, "已有文档内容请通过 working-state 保存分支草稿；旧整本更新入口已停止写入，请升级客户端。名称修改请使用 rename");
        }

        // 回读一次，确保返回完整信息
        AiWorkbook saved = workbookMapper.selectById(workbook.getWorkbookId(), userId);
        if (saved != null)
        {
            saved.setContent(null); // 保存响应不返回大段内容
        }
        return R.ok(saved);
    }

    @Override
    public R<AiWorkbook> get(Long workbookId)
    {
        Long userId = getUserId();
        if (userId == null)
        {
            return R.fail(503, "未登录或登录已过期");
        }
        if (workbookId == null)
        {
            return R.fail("文档 ID 不能为空");
        }
        AiWorkbook workbook = workbookMapper.selectById(workbookId, userId);
        if (workbook == null)
        {
            return R.fail("文档不存在或无权访问");
        }
        return R.ok(workbook);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<Void> remove(Long workbookId)
    {
        Long userId = getUserId();
        if (userId == null)
        {
            return R.fail(503, "未登录或登录已过期");
        }
        if (workbookId == null)
        {
            return R.fail("文档 ID 不能为空");
        }
        agentWriteGuard.manual(workbookId, userId);
        int rows = workbookMapper.deleteById(workbookId, userId);
        if (rows == 0)
        {
            return R.fail("文档不存在或无权访问");
        }
        return R.ok();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public R<AiWorkbook> rename(AiWorkbook workbook)
    {
        Long userId = getUserId();
        if (userId == null)
        {
            return R.fail(503, "未登录或登录已过期");
        }
        if (workbook == null || workbook.getWorkbookId() == null)
        {
            return R.fail("文档 ID 不能为空");
        }
        if (workbook.getName() == null || workbook.getName().trim().isEmpty())
        {
            return R.fail("文档名称不能为空");
        }

        String name = workbook.getName().trim();
        agentWriteGuard.manual(workbook.getWorkbookId(), userId);

        AiWorkbook exist = workbookMapper.selectById(workbook.getWorkbookId(), userId);
        if (exist == null)
        {
            return R.fail("文档不存在或无权访问");
        }
        if (name.equals(exist.getName()))
        {
            // 名称未变，直接返回
            exist.setContent(null);
            return R.ok(exist);
        }
        if (workbookMapper.countByNameAndUserId(name, userId, workbook.getWorkbookId()) > 0)
        {
            return R.fail("文件名已存在");
        }

        workbookMapper.updateName(workbook.getWorkbookId(), userId, name, SecurityUtils.getUsername());

        AiWorkbook saved = workbookMapper.selectById(workbook.getWorkbookId(), userId);
        if (saved != null)
        {
            saved.setContent(null);
        }
        return R.ok(saved);
    }

    /**
     * 获取当前登录用户 ID，未登录返回 null
     */
    private Long getUserId()
    {
        try
        {
            return SecurityUtils.getUserId();
        }
        catch (Throwable e)
        {
            log.warn("[AiWorkbook] 未获取到登录用户: {}", e.getMessage());
            return null;
        }
    }
}
