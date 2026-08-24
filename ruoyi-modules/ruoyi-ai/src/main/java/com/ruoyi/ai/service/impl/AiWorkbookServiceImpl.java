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

import java.util.Date;
import java.util.List;

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

        if (workbook.getWorkbookId() == null)
        {
            // 新增
            if (workbook.getType() == null || workbook.getType().trim().isEmpty())
            {
                workbook.setType("created");
            }
            workbook.setCreateBy(username);
            workbook.setCreateTime(new Date());
            workbookMapper.insertWorkbook(workbook);
        }
        else
        {
            // 更新：需校验归属，防止越权更新他人文档
            AiWorkbook exist = workbookMapper.selectById(workbook.getWorkbookId(), userId);
            if (exist == null)
            {
                return R.fail("文档不存在或无权访问");
            }
            workbook.setType(workbook.getType() == null || workbook.getType().trim().isEmpty()
                    ? exist.getType() : workbook.getType());
            workbook.setUpdateBy(username);
            workbookMapper.updateWorkbook(workbook);
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
        int rows = workbookMapper.deleteById(workbookId, userId);
        if (rows == 0)
        {
            return R.fail("文档不存在或无权访问");
        }
        return R.ok();
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
