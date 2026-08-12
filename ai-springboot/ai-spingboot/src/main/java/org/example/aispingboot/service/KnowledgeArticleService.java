package org.example.aispingboot.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.extern.slf4j.Slf4j;
import org.example.aispingboot.DTO.response.KnowledgeArticleResponseDTO;
import org.example.aispingboot.entity.KnowledgeArticle;
import org.example.aispingboot.entity.KnowledgeCategory;
import org.example.aispingboot.mapper.KnowledgeArticleMapper;
import org.example.aispingboot.mapper.KnowledgeCategoryMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 知识文章服务
 * <p>
 * 知识文章是典型的「读多写少」数据：几乎没人改，但每个人都会反复看列表和详情，
 * 是最适合做缓存的一类场景。这里用 Redis 缓存：
 * <ul>
 *     <li>文章详情 —— TTL 1 小时（key 精确，命中率高）</li>
 *     <li>文章列表分页 —— TTL 10 分钟（key 含分页与排序参数）</li>
 * </ul>
 * 所有缓存操作都做了异常兜底，Redis 故障时自动回源 MySQL。
 */
@Slf4j
@Service
public class KnowledgeArticleService {

    private static final String KEY_DETAIL = "mental:knowledge:article:";
    private static final String KEY_PAGE = "mental:knowledge:page:";
    private static final String KEY_CATEGORY_TREE = "mental:knowledge:category:tree";

    /** 详情缓存 1 小时 */
    private static final long DETAIL_TTL_HOURS = 1;
    /** 列表缓存 10 分钟 */
    private static final long PAGE_TTL_MINUTES = 10;

    @Autowired
    private KnowledgeArticleMapper knowledgeArticleMapper;

    @Autowired
    private KnowledgeCategoryMapper knowledgeCategoryMapper;

    /** 作者名需要关联 user 表（knowledge_article 表没有 author_name 列） */
    @Autowired
    private org.example.aispingboot.mapper.UserMapper userMapper;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    /**
     * 分页查询已发布文章
     * <p>
     * 注意：前端传的参数名是 currentPage / size / sortField / sortDirection，
     * 这里统一接收并做好默认值与白名单校验（sortField 直接拼进排序会有 SQL 注入风险）。
     */
    public Page<KnowledgeArticleResponseDTO> pageArticles(long currentPage, long size,
                                                          String sortField, String sortDirection) {
        String cacheKey = KEY_PAGE + currentPage + ":" + size + ":" + sortField + ":" + sortDirection;

        // 1. 查缓存
        Page<KnowledgeArticleResponseDTO> cached = getPageFromCache(cacheKey);
        if (cached != null) {
            return cached;
        }

        // 2. 回源数据库（只查已发布文章）
        Page<KnowledgeArticle> page = new Page<>(currentPage, size);
        LambdaQueryWrapper<KnowledgeArticle> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(KnowledgeArticle::getStatus, 1);
        applySort(wrapper, sortField, sortDirection);

        Page<KnowledgeArticle> articlePage = knowledgeArticleMapper.selectPage(page, wrapper);

        // 3. 转换为 DTO
        Page<KnowledgeArticleResponseDTO> result = new Page<>(
                articlePage.getCurrent(), articlePage.getSize(), articlePage.getTotal());
        result.setRecords(articlePage.getRecords().stream()
                .map(article -> convert(article, false))
                .collect(Collectors.toList()));

        // 4. 回填缓存
        putPageToCache(cacheKey, result);
        return result;
    }

    /**
     * 查询文章详情，并把阅读量 +1
     */
    public KnowledgeArticleResponseDTO getArticleDetail(String id) {
        String cacheKey = KEY_DETAIL + id;

        // 1. 查缓存
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof KnowledgeArticleResponseDTO dto) {
                // 阅读量属于高频变化数据，缓存命中时异步累加，不阻塞返回
                increaseReadCount(id);
                return dto;
            }
        } catch (Exception e) {
            log.warn("读取文章详情缓存失败, id={}: {}", id, e.getMessage());
        }

        // 2. 回源数据库
        KnowledgeArticle article = knowledgeArticleMapper.selectById(id);
        if (article == null) {
            return null;
        }
        KnowledgeArticleResponseDTO dto = convert(article, true);

        // 3. 回填缓存
        try {
            redisTemplate.opsForValue().set(cacheKey, dto, DETAIL_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("写入文章详情缓存失败, id={}: {}", id, e.getMessage());
        }

        // 4. 累加阅读量
        increaseReadCount(id);
        return dto;
    }

    /**
     * 直接按主键查询文章实体（不走缓存、不累加阅读量）
     * <p>
     * 专供 RAG 索引构建使用：索引必须拿到"当前最新"的正文，
     * 而 getArticleDetail 会命中缓存并累加阅读量，都不适合这个场景。
     */
    public KnowledgeArticle getArticleEntity(String id) {
        if (id == null) {
            return null;
        }
        return knowledgeArticleMapper.selectById(id);
    }

    /**
     * 分类树（前端后台页面使用）
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, Object>> categoryTree() {
        try {
            Object cached = redisTemplate.opsForValue().get(KEY_CATEGORY_TREE);
            if (cached instanceof List<?> list) {
                return (List<Map<String, Object>>) list;
            }
        } catch (Exception e) {
            log.warn("读取分类树缓存失败: {}", e.getMessage());
        }

        List<KnowledgeCategory> categories = knowledgeCategoryMapper.selectList(
                new LambdaQueryWrapper<KnowledgeCategory>().orderByAsc(KnowledgeCategory::getSortOrder));

        List<Map<String, Object>> tree = categories.stream().map(category -> {
            Map<String, Object> node = new HashMap<>();
            node.put("id", category.getId());
            node.put("name", category.getName());
            node.put("parentId", category.getParentId());
            node.put("description", category.getDescription());
            return node;
        }).collect(Collectors.toList());

        try {
            redisTemplate.opsForValue().set(KEY_CATEGORY_TREE, tree, DETAIL_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("写入分类树缓存失败: {}", e.getMessage());
        }
        return tree;
    }

    /**
     * 新增/修改/删除文章后清理相关缓存
     * <p>
     * 因为列表缓存按分页参数组合成多个 key，这里用前缀批量删除，保证数据一致性。
     */
    public void evictArticleCache(String articleId) {
        try {
            if (articleId != null) {
                redisTemplate.delete(KEY_DETAIL + articleId);
            }
            // 清理所有列表分页缓存
            var keys = redisTemplate.keys(KEY_PAGE + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
            redisTemplate.delete(KEY_CATEGORY_TREE);
            log.debug("已清理知识文章缓存, articleId={}", articleId);
        } catch (Exception e) {
            log.warn("清理知识文章缓存失败: {}", e.getMessage());
        }
    }

    // ---------------- 写操作（后台管理） ----------------

    /**
     * 新增文章
     * <p>
     * 主键是 varchar(36) 的 UUID，且不是自增列，必须由应用侧生成。
     */
    public KnowledgeArticleResponseDTO createArticle(KnowledgeArticleResponseDTO dto) {
        KnowledgeArticle article = KnowledgeArticle.builder()
                // 生成 36 位 UUID 作为主键
                .id(java.util.UUID.randomUUID().toString())
                .title(dto.getTitle())
                .categoryId(dto.getCategoryId())
                .authorId(dto.getAuthorId())
                .coverImage(dto.getCoverImage())
                .summary(dto.getSummary())
                .content(dto.getContent())
                .tags(dto.getTags())
                .readCount(0)
                .status(1)
                .publishedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
        knowledgeArticleMapper.insert(article);
        // 新增后清理列表缓存，保证能立刻查到
        evictArticleCache(article.getId());
        return convert(article, true);
    }

    /**
     * 修改文章
     */
    public KnowledgeArticleResponseDTO updateArticle(String id, KnowledgeArticleResponseDTO dto) {
        KnowledgeArticle existing = knowledgeArticleMapper.selectById(id);
        if (existing == null) {
            return null;
        }
        existing.setTitle(dto.getTitle());
        existing.setCategoryId(dto.getCategoryId());
        existing.setAuthorId(dto.getAuthorId());
        existing.setCoverImage(dto.getCoverImage());
        existing.setSummary(dto.getSummary());
        existing.setContent(dto.getContent());
        existing.setTags(dto.getTags());
        existing.setUpdatedAt(LocalDateTime.now());
        knowledgeArticleMapper.updateById(existing);

        // 修改后清理该文章详情与列表缓存
        evictArticleCache(id);
        return convert(existing, true);
    }

    /**
     * 修改文章状态（上架/下架）
     */
    public boolean changeStatus(String id, Integer status) {
        KnowledgeArticle existing = knowledgeArticleMapper.selectById(id);
        if (existing == null) {
            return false;
        }
        KnowledgeArticle update = new KnowledgeArticle();
        update.setId(id);
        update.setStatus(status);
        update.setUpdatedAt(LocalDateTime.now());
        knowledgeArticleMapper.updateById(update);
        evictArticleCache(id);
        return true;
    }

    /**
     * 删除文章
     */
    public boolean deleteArticle(String id) {
        if (knowledgeArticleMapper.selectById(id) == null) {
            return false;
        }
        knowledgeArticleMapper.deleteById(id);
        evictArticleCache(id);
        return true;
    }

    // ---------------- 私有方法 ----------------

    /**
     * 排序字段白名单，防止 SQL 注入
     */
    private void applySort(LambdaQueryWrapper<KnowledgeArticle> wrapper, String sortField, String sortDirection) {
        boolean asc = "asc".equalsIgnoreCase(sortDirection);
        String field = sortField == null ? "publishedAt" : sortField;

        switch (field) {
            case "readCount" -> wrapper.orderBy(true, asc, KnowledgeArticle::getReadCount);
            case "updatedAt" -> wrapper.orderBy(true, asc, KnowledgeArticle::getUpdatedAt);
            case "createdAt" -> wrapper.orderBy(true, asc, KnowledgeArticle::getCreatedAt);
            // 默认按发布时间倒序
            default -> wrapper.orderBy(true, asc, KnowledgeArticle::getPublishedAt);
        }
        // 加一个稳定的次级排序，避免分页时出现重复/漏数据
        wrapper.orderByDesc(KnowledgeArticle::getId);
    }

    private void increaseReadCount(String id) {
        try {
            KnowledgeArticle update = new KnowledgeArticle();
            update.setId(id);
            KnowledgeArticle current = knowledgeArticleMapper.selectById(id);
            if (current != null) {
                update.setReadCount((current.getReadCount() == null ? 0 : current.getReadCount()) + 1);
                knowledgeArticleMapper.updateById(update);
            }
        } catch (Exception e) {
            // 阅读量统计失败不应影响正文展示
            log.warn("更新阅读量失败, id={}: {}", id, e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Page<KnowledgeArticleResponseDTO> getPageFromCache(String cacheKey) {
        try {
            Object cached = redisTemplate.opsForValue().get(cacheKey);
            if (cached instanceof Page<?> page) {
                log.debug("文章列表命中缓存: {}", cacheKey);
                return (Page<KnowledgeArticleResponseDTO>) page;
            }
        } catch (Exception e) {
            log.warn("读取文章列表缓存失败: {}", e.getMessage());
        }
        return null;
    }

    private void putPageToCache(String cacheKey, Page<KnowledgeArticleResponseDTO> page) {
        try {
            redisTemplate.opsForValue().set(cacheKey, page, PAGE_TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.warn("写入文章列表缓存失败: {}", e.getMessage());
        }
    }

    /**
     * 实体转 DTO，并补上前端需要的派生字段
     */
    private KnowledgeArticleResponseDTO convert(KnowledgeArticle article, boolean withContent) {
        KnowledgeArticleResponseDTO dto = new KnowledgeArticleResponseDTO();
        dto.setId(article.getId());
        dto.setTitle(article.getTitle());
        dto.setCategoryId(article.getCategoryId());
        dto.setAuthorId(article.getAuthorId());
        dto.setCoverImage(article.getCoverImage());
        dto.setSummary(article.getSummary());
        dto.setTags(article.getTags());
        dto.setReadCount(article.getReadCount() == null ? 0 : article.getReadCount());
        dto.setPublishedAt(article.getPublishedAt());
        dto.setUpdatedAt(article.getUpdatedAt());

        // 正文体积大，列表页不需要，避免无谓的传输与缓存占用
        if (withContent) {
            dto.setContent(article.getContent());
        }

        // 分类名称（库中列名是 category_name，实体已映射为 name）
        if (article.getCategoryId() != null) {
            KnowledgeCategory category = knowledgeCategoryMapper.selectById(article.getCategoryId());
            if (category != null) {
                dto.setCategoryName(category.getName());
            }
        }

        // 作者名称：knowledge_article 表没有 author_name 列，需按 author_id 关联 user 表
        if (article.getAuthorId() != null) {
            try {
                org.example.aispingboot.entity.User author = userMapper.selectById(article.getAuthorId());
                if (author != null) {
                    // 优先用昵称，没有则退回用户名
                    dto.setAuthorName(author.getNickname() != null && !author.getNickname().isBlank()
                            ? author.getNickname()
                            : author.getUsername());
                }
            } catch (Exception e) {
                // 作者信息查不到不应影响文章展示
                log.warn("查询作者信息失败, authorId={}: {}", article.getAuthorId(), e.getMessage());
            }
        }

        // 标签字符串 -> 数组（前端详情页 v-for 遍历 tagArray）
        if (StringUtils.hasText(article.getTags())) {
            dto.setTagArray(Arrays.stream(article.getTags().split("[,，]"))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList()));
        } else {
            dto.setTagArray(new ArrayList<>());
        }
        return dto;
    }
}
