package com.mola.cmd.proxy.app.acp.memory;

import com.google.gson.*;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryAction;
import com.mola.cmd.proxy.app.acp.memory.model.DreamState;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryEntry;
import com.mola.cmd.proxy.app.acp.memory.model.MemoryIndex;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import com.mola.cmd.proxy.app.acp.common.PathUtils;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 记忆文件存储层，负责索引和明细文件的 CRUD。
 * <p>
 * 存储结构：
 * <pre>
 * {baseDir}/
 * ├── {workspacePath_hash}/
 * │   ├── MEMORY_INDEX.json
 * │   ├── memories/
 * │   │   ├── user_profile.md
 * │   │   └── ...
 * │   └── archive/
 * └── global/
 * </pre>
 */
public class MemoryFileStore {

    private static final Logger logger = LoggerFactory.getLogger(MemoryFileStore.class);
    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 索引专用 Gson：排除 detail 和 sourceSession，这些只属于明细文件 */
    private static final Gson INDEX_GSON = new GsonBuilder()
            .setPrettyPrinting()
            .setExclusionStrategies(new ExclusionStrategy() {
                private final Set<String> EXCLUDED = new HashSet<>(Arrays.asList("detail", "sourceSession"));
                @Override
                public boolean shouldSkipField(FieldAttributes f) {
                    return f.getDeclaringClass() == MemoryEntry.class && EXCLUDED.contains(f.getName());
                }
                @Override
                public boolean shouldSkipClass(Class<?> clazz) { return false; }
            })
            .create();
    private static final String INDEX_FILE = "MEMORY_INDEX.json";
    private static final String DREAM_STATE_FILE = "DREAM_STATE.json";
    private static final String MEMORIES_DIR = "memories";
    private static final String ARCHIVE_DIR = "archive";
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final String baseDir;

    public MemoryFileStore(String baseDir) {
        this.baseDir = baseDir;
    }

    // ==================== 索引操作 ====================

    /**
     * 加载项目的记忆索引，不存在时返回空索引。
     */
    public MemoryIndex loadIndex(String workspacePath) {
        Path indexPath = getIndexFilePath(workspacePath);
        if (!Files.exists(indexPath)) {
            return new MemoryIndex();
        }
        try {
            String content = new String(Files.readAllBytes(indexPath), StandardCharsets.UTF_8);
            return PRETTY_GSON.fromJson(content, MemoryIndex.class);
        } catch (Exception e) {
            logger.error("加载记忆索引失败: {}", indexPath, e);
            return new MemoryIndex();
        }
    }

    /**
     * 保存索引文件。
     */
    public void saveIndex(String workspacePath, MemoryIndex index) {
        Path indexPath = getIndexFilePath(workspacePath);
        try {
            Files.createDirectories(indexPath.getParent());
            index.setLastUpdated(ZonedDateTime.now().format(ISO_FORMATTER));
            Files.write(indexPath, INDEX_GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
            logger.info("记忆索引已保存: {}, 记忆数={}", indexPath, index.getMemories().size());
        } catch (IOException e) {
            logger.error("保存记忆索引失败: {}", indexPath, e);
        }
    }

    // ==================== 明细文件操作 ====================

    /**
     * 写入/更新明细文件（Markdown + Frontmatter 格式）。
     */
    public void writeDetail(String workspacePath, MemoryEntry entry) {
        Path memoriesDir = getProjectDir(workspacePath).resolve(MEMORIES_DIR);
        try {
            Files.createDirectories(memoriesDir);
            String fileName = buildFileName(entry.getType(), entry.getTitle());
            Path filePath = memoriesDir.resolve(fileName);

            StringBuilder sb = new StringBuilder();
            sb.append("---\n");
            sb.append("id: ").append(entry.getId()).append("\n");
            sb.append("type: ").append(entry.getType()).append("\n");
            sb.append("title: ").append(entry.getTitle()).append("\n");
            sb.append("tags: [").append(String.join(", ", entry.getTags())).append("]\n");
            sb.append("createdAt: ").append(entry.getCreatedAt()).append("\n");
            sb.append("updatedAt: ").append(entry.getUpdatedAt()).append("\n");
            if (entry.getSourceSession() != null) {
                sb.append("sourceSession: ").append(entry.getSourceSession()).append("\n");
            }
            sb.append("---\n\n");
            sb.append(entry.getDetail() != null ? entry.getDetail() : entry.getSummary());
            sb.append("\n");

            Files.write(filePath, sb.toString().getBytes(StandardCharsets.UTF_8));
            entry.setFile(filePath.toAbsolutePath().toString());
            logger.info("记忆明细已写入: {}", filePath);
        } catch (IOException e) {
            logger.error("写入记忆明细失败: {}", entry.getId(), e);
        }
    }

    // ==================== 删除与归档 ====================

    /**
     * 删除记忆：从索引中移除，明细文件移到 archive 目录。
     *
     * @return true 如果成功删除
     */
    /**
     * 删除记忆：从索引中移除，明细文件移到 archive 目录，保存索引。
     * 这是对外的完整删除操作（load → remove → archive → save）。
     *
     * @return true 如果成功删除
     */
    public boolean deleteMemory(String workspacePath, String memoryId) {
        MemoryIndex index = loadIndex(workspacePath);
        boolean removed = removeAndArchive(workspacePath, index, memoryId);
        if (removed) {
            saveIndex(workspacePath, index);
        }
        return removed;
    }

    // ==================== 批量操作 ====================

    /**
     * 执行子 Client 返回的批量操作（ADD/UPDATE/DELETE）。
     */
    public void applyActions(String workspacePath, List<MemoryAction> actions,
                             MemoryIndex existingIndex, int maxEntries) {
        String now = ZonedDateTime.now().format(ISO_FORMATTER);
        boolean changed = false;

        for (MemoryAction action : actions) {
            if (action.getAction() == null || action.getAction() == MemoryAction.ActionType.NOOP) {
                continue;
            }
            switch (action.getAction()) {
                case ADD:
                    if (existingIndex.getMemories().size() >= maxEntries) {
                        archiveOldest(workspacePath, existingIndex);
                    }
                    MemoryEntry newEntry = new MemoryEntry();
                    newEntry.setId("memory_" + UUID.randomUUID().toString().substring(0, 8));
                    newEntry.setType(action.getType());
                    newEntry.setTitle(action.getTitle());
                    newEntry.setSummary(action.getSummary());
                    newEntry.setDetail(action.getDetail());
                    newEntry.setTags(action.getTags());
                    newEntry.setCreatedAt(now);
                    newEntry.setUpdatedAt(now);
                    writeDetail(workspacePath, newEntry);
                    existingIndex.getMemories().add(newEntry);
                    changed = true;
                    logger.info("[记忆 ADD] id={}, type={}, title=\"{}\", summary=\"{}\"",
                            newEntry.getId(), newEntry.getType(), newEntry.getTitle(), newEntry.getSummary());
                    break;

                case UPDATE:
                    for (MemoryEntry entry : existingIndex.getMemories()) {
                        if (entry.getId().equals(action.getId())) {
                            String oldTitle = entry.getTitle();
                            String oldSummary = entry.getSummary();
                            if (action.getTitle() != null) entry.setTitle(action.getTitle());
                            if (action.getSummary() != null) entry.setSummary(action.getSummary());
                            if (action.getDetail() != null) entry.setDetail(action.getDetail());
                            if (action.getTags() != null && !action.getTags().isEmpty()) {
                                entry.setTags(action.getTags());
                            }
                            entry.setUpdatedAt(now);
                            writeDetail(workspacePath, entry);
                            changed = true;
                            logger.info("[记忆 UPDATE] id={}, type={}, title=\"{}\" -> \"{}\", summary=\"{}\" -> \"{}\"",
                                    entry.getId(), entry.getType(),
                                    oldTitle, entry.getTitle(),
                                    oldSummary, entry.getSummary());
                            break;
                        }
                    }
                    break;

                case DELETE:
                    // 先取出待删记忆信息用于日志
                    MemoryEntry toDelete = existingIndex.getMemories().stream()
                            .filter(e -> e.getId().equals(action.getId()))
                            .findFirst().orElse(null);
                    if (toDelete != null) {
                        logger.info("[记忆 DELETE] id={}, type={}, title=\"{}\", summary=\"{}\"",
                                toDelete.getId(), toDelete.getType(), toDelete.getTitle(), toDelete.getSummary());
                    }
                    changed |= removeAndArchive(workspacePath, existingIndex, action.getId());
                    break;
            }
        }

        if (changed) {
            saveIndex(workspacePath, existingIndex);
        }
    }

    /**
     * 归档最不活跃的记忆（按 updatedAt 升序，归档最早的一条）。
     */
    private void archiveOldest(String workspacePath, MemoryIndex index) {
        if (index.getMemories().isEmpty()) return;
        MemoryEntry oldest = index.getMemories().stream()
                .min(Comparator.comparing(e -> e.getUpdatedAt() != null ? e.getUpdatedAt() : ""))
                .orElse(null);
        if (oldest != null) {
            logger.info("容量超限，归档最不活跃记忆: {} - {}", oldest.getId(), oldest.getTitle());
            removeAndArchive(workspacePath, index, oldest.getId());
        }
    }

    /**
     * 从指定的 index 对象中移除记忆条目，并将明细文件归档。
     * 不会触发 saveIndex——由调用方统一保存。
     *
     * @return true 如果找到并移除了该条目
     */
    public boolean removeAndArchive(String workspacePath, MemoryIndex index, String memoryId) {
        MemoryEntry target = null;
        Iterator<MemoryEntry> it = index.getMemories().iterator();
        while (it.hasNext()) {
            MemoryEntry entry = it.next();
            if (memoryId.equals(entry.getId())) {
                target = entry;
                it.remove();
                break;
            }
        }
        if (target == null) {
            logger.warn("记忆不存在: {}", memoryId);
            return false;
        }
        archiveDetailFile(workspacePath, target);
        logger.info("记忆已从索引移除并归档: {} - {}", target.getId(), target.getTitle());
        return true;
    }

    /**
     * 将明细文件移到 archive 目录。
     */
    private void archiveDetailFile(String workspacePath, MemoryEntry entry) {
        if (entry.getFile() == null) return;
        try {
            Path source = Paths.get(entry.getFile());
            if (Files.exists(source)) {
                Path archiveDir = getProjectDir(workspacePath).resolve(ARCHIVE_DIR);
                Files.createDirectories(archiveDir);
                Files.move(source, archiveDir.resolve(source.getFileName()));
            }
        } catch (IOException e) {
            logger.error("归档记忆文件失败: {}", entry.getFile(), e);
        }
    }

    /**
     * 清理过期的 project 类型记忆。
     *
     * @param expireDays 过期天数
     * @return 清理的记忆数量
     */
    public int cleanExpiredMemories(String workspacePath, int expireDays) {
        MemoryIndex index = loadIndex(workspacePath);
        String cutoff = ZonedDateTime.now().minusDays(expireDays).format(ISO_FORMATTER);
        List<String> toDelete = index.getMemories().stream()
                .filter(e -> "project".equals(e.getType()))
                .filter(e -> e.getUpdatedAt() != null && e.getUpdatedAt().compareTo(cutoff) < 0)
                .map(MemoryEntry::getId)
                .collect(Collectors.toList());

        for (String id : toDelete) {
            deleteMemory(workspacePath, id);
        }
        if (!toDelete.isEmpty()) {
            logger.info("清理过期记忆 {} 条, workspacePath={}", toDelete.size(), workspacePath);
        }
        return toDelete.size();
    }

    // ==================== Dream 状态操作 ====================

    /**
     * 加载整理状态，不存在时返回默认状态。
     */
    public DreamState loadDreamState(String workspacePath) {
        Path path = getProjectDir(workspacePath).resolve(DREAM_STATE_FILE);
        if (!Files.exists(path)) {
            return new DreamState();
        }
        try {
            String content = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
            return PRETTY_GSON.fromJson(content, DreamState.class);
        } catch (Exception e) {
            logger.error("加载整理状态失败: {}", path, e);
            return new DreamState();
        }
    }

    /**
     * 保存整理状态。
     */
    public void saveDreamState(String workspacePath, DreamState state) {
        Path path = getProjectDir(workspacePath).resolve(DREAM_STATE_FILE);
        try {
            Files.createDirectories(path.getParent());
            Files.write(path, PRETTY_GSON.toJson(state).getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            logger.error("保存整理状态失败: {}", path, e);
        }
    }

    /**
     * 递增 session 计数（用于 Dream 触发条件判断）。
     */
    public void incrementDreamSessionCount(String workspacePath) {
        DreamState state = loadDreamState(workspacePath);
        state.setSessionsSinceLastDream(state.getSessionsSinceLastDream() + 1);
        saveDreamState(workspacePath, state);
    }

    /**
     * 读取所有明细文件内容。
     *
     * @return key=memoryId, value=明细文件的原始文本内容
     */
    public Map<String, String> loadAllDetails(String workspacePath, MemoryIndex index) {
        Map<String, String> details = new HashMap<>();
        for (MemoryEntry entry : index.getMemories()) {
            if (entry.getFile() != null) {
                try {
                    Path filePath = Paths.get(entry.getFile());
                    if (Files.exists(filePath)) {
                        String content = new String(Files.readAllBytes(filePath), StandardCharsets.UTF_8);
                        details.put(entry.getId(), content);
                    }
                } catch (IOException e) {
                    logger.warn("读取明细文件失败: {}", entry.getFile());
                }
            }
        }
        return details;
    }

    // ==================== 孤立文件清理 ====================

    /**
     * 将 memories/ 目录下未被索引引用的明细文件移到 archive/ 目录。
     *
     * @return 归档的孤立文件数量
     */
    public int archiveOrphanedDetails(String workspacePath, MemoryIndex index) {
        Path memoriesDir = getProjectDir(workspacePath).resolve(MEMORIES_DIR);
        if (!Files.exists(memoriesDir) || !Files.isDirectory(memoriesDir)) {
            return 0;
        }

        // 收集索引中所有明细文件的绝对路径
        Set<String> indexedPaths = new HashSet<>();
        for (MemoryEntry entry : index.getMemories()) {
            if (entry.getFile() != null) {
                indexedPaths.add(Paths.get(entry.getFile()).toAbsolutePath().toString());
            }
        }

        int archived = 0;
        try {
            Path archiveDir = getProjectDir(workspacePath).resolve(ARCHIVE_DIR);
            for (Path file : Files.list(memoriesDir).collect(Collectors.toList())) {
                if (!Files.isRegularFile(file)) continue;
                if (!indexedPaths.contains(file.toAbsolutePath().toString())) {
                    Files.createDirectories(archiveDir);
                    Files.move(file, archiveDir.resolve(file.getFileName()));
                    archived++;
                    logger.info("归档孤立明细文件: {}", file.getFileName());
                }
            }
        } catch (IOException e) {
            logger.error("清理孤立明细文件失败, workspacePath={}", workspacePath, e);
        }
        return archived;
    }

    // ==================== 查询 ====================

    /**
     * 列出项目的所有记忆（索引概要）。
     */
    public List<MemoryEntry> listMemories(String workspacePath) {
        return loadIndex(workspacePath).getMemories();
    }

    // ==================== 路径工具 ====================

    public String getIndexPath(String workspacePath) {
        return getIndexFilePath(workspacePath).toAbsolutePath().toString();
    }

    private Path getIndexFilePath(String workspacePath) {
        return getProjectDir(workspacePath).resolve(INDEX_FILE);
    }

    private Path getProjectDir(String workspacePath) {
        return Paths.get(baseDir, PathUtils.sanitizePath(workspacePath));
    }

    /**
     * 根据类型和标题生成文件名：type_title_slug.md
     */
    private String buildFileName(String type, String title) {
        String slug = title.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5]", "_")
                .replaceAll("_+", "_")
                .toLowerCase();
        if (slug.length() > 40) {
            slug = slug.substring(0, 40);
        }
        return type + "_" + slug + ".md";
    }
}
