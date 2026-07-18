package com.formationtool.desktop.data;

import com.formationtool.desktop.model.DanceGroup;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;

/**
 * 数据管理器 - 负责JSON导入导出和工程管理
 */
public class DataManager {
    private static final Charset TEXT_CHARSET = StandardCharsets.UTF_8;
    private static final int MAX_ARCHIVE_ENTRIES = 256;
    private static final long MAX_PROJECT_JSON_BYTES = 2L * 1024 * 1024;
    private static final long MAX_STYLE_BYTES = 20L * 1024 * 1024;
    private static final long MAX_MEDIA_BYTES = 2L * 1024 * 1024 * 1024;
    private static final long MAX_ARCHIVE_BYTES = 3L * 1024 * 1024 * 1024;
    private static final int MAX_FORMATIONS = 10_000;
    private static final int MAX_DANCERS_PER_FORMATION = 2_000;
    private static final Set<String> AUDIO_EXTENSIONS = Set.of("mp3", "wav", "m4a", "aac", "ogg", "flac");
    private static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "m4v", "mov", "webm", "mkv");
    private static final String SAFE_ID_PATTERN = "[A-Za-z0-9_-]{1,128}";

    private final Gson gson;
    private final Path dataDir;
    private final Path projectsDir;  // 工程文件夹根目录

    public DataManager() {
        this.gson = new GsonBuilder()
            .setPrettyPrinting()
            .create();

        // 数据目录: 用户文档/FormationTool
        String userHome = System.getProperty("user.home");
        this.dataDir = Paths.get(userHome, "Documents", "FormationTool");
        this.projectsDir = dataDir.resolve("Projects");  // 工程文件夹

        // 确保目录存在
        try {
            Files.createDirectories(dataDir);
            Files.createDirectories(projectsDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * 获取工程目录
     */
    public Path getProjectDir(String projectId) {
        return projectsDir.resolve(projectId);
    }

    public Path getProjectDancerStylesDir(String projectId) {
        return getProjectDir(projectId).resolve("dancer_styles");
    }

    public File getProjectDancerStyleFile(String projectId, String dancerId) {
        return getProjectDancerStylesDir(projectId).resolve(dancerId + ".png").toFile();
    }

    public File saveDancerStyleImage(String projectId, String dancerId, BufferedImage image) {
        if (projectId == null || dancerId == null || image == null) return null;

        try {
            Path styleDir = getProjectDancerStylesDir(projectId);
            Files.createDirectories(styleDir);
            File targetFile = styleDir.resolve(dancerId + ".png").toFile();
            if (!ImageIO.write(image, "png", targetFile)) {
                return null;
            }
            return targetFile;
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public boolean deleteDancerStyleImage(String projectId, String dancerId) {
        if (projectId == null || dancerId == null) return false;
        try {
            File styleFile = getProjectDancerStyleFile(projectId, dancerId);
            return !styleFile.exists() || styleFile.delete();
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 保存工程数据到JSON文件
     *
     */
    public boolean saveProject(DanceGroup group) {
        if (group == null) return false;

        try {
            // 创建项目目录
            Path projectDir = getProjectDir(group.getId());
            Files.createDirectories(projectDir);

            // 更新修改时间
            group.setModifiedTime(System.currentTimeMillis());

            // 保存工程数据
            Path projectFile = projectDir.resolve("project.json");
            String json = gson.toJson(group);
            Files.writeString(projectFile, json, TEXT_CHARSET);

            System.out.println("[自动保存] 工程已保存: " + group.getName());
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从JSON文件加载工程
     */
    public DanceGroup loadProject(String projectId) {
        try {
            Path projectDir = getProjectDir(projectId);
            Path projectFile = projectDir.resolve("project.json");

            if (!Files.exists(projectFile)) return null;

            String json = readTextWithEncodingFallback(projectFile);
            return gson.fromJson(json, DanceGroup.class);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 复制音频文件到项目目录
     */
    public File copyAudioToProject(String projectId, File audioFile) {
        if (audioFile == null || !audioFile.exists()) return null;

        try {
            Path projectDir = getProjectDir(projectId);
            Files.createDirectories(projectDir);

            String extension = getFileExtension(audioFile.getName());
            String fileName = "audio." + (extension.isEmpty() ? "mp3" : extension);
            Path targetPath = projectDir.resolve(fileName);

            // 复制文件
            Files.copy(audioFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[数据管理] 复制音频文件到项目目录: " + targetPath);

            return targetPath.toFile();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 复制视频文件到项目目录
     */
    public File copyVideoToProject(String projectId, File videoFile) {
        if (videoFile == null || !videoFile.exists()) return null;

        try {
            Path projectDir = getProjectDir(projectId);
            Files.createDirectories(projectDir);

            String extension = getFileExtension(videoFile.getName());
            String fileName = "video." + (extension.isEmpty() ? "mp4" : extension);
            Path targetPath = projectDir.resolve(fileName);

            // 复制文件
            Files.copy(videoFile.toPath(), targetPath, StandardCopyOption.REPLACE_EXISTING);
            System.out.println("[数据管理] 复制视频文件到项目目录: " + targetPath);

            return targetPath.toFile();
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 获取项目音频文件
     */
    public File getProjectAudioFile(String projectId) {
        Path projectDir = getProjectDir(projectId);

        // 查找所有支持的音频格式
        String[] extensions = {
            "mp3", "wav", "m4a", "aac", "flac",
            "ogg", "wma", "ape", "opus", "alac",
            "aiff", "amr", "ac3"
        };
        for (String ext : extensions) {
            Path audioPath = projectDir.resolve("audio." + ext);
            if (Files.exists(audioPath)) {
                return audioPath.toFile();
            }
        }
        return null;
    }

    /**
     * 获取项目视频文件
     */
    public File getProjectVideoFile(String projectId) {
        Path projectDir = getProjectDir(projectId);

        // 查找所有支持的视频格式
        String[] extensions = {
            "mp4", "mov", "avi", "mkv", "flv",
            "wmv", "m4v", "3gp", "webm", "vob",
            "mpg", "mpeg", "ts", "mts", "m2ts"
        };
        for (String ext : extensions) {
            Path videoPath = projectDir.resolve("video." + ext);
            if (Files.exists(videoPath)) {
                return videoPath.toFile();
            }
        }
        return null;
    }

    /**
     * 删除项目中的音频文件
     */
    public boolean deleteAudioFromProject(String projectId) {
        if (projectId == null) return false;

        Path projectDir = getProjectDir(projectId);
        boolean deleted = false;

        // 遍历所有音频格式
        String[] extensions = {
            "mp3", "wav", "m4a", "aac", "flac",
            "ogg", "wma", "ape", "opus", "alac",
            "aiff", "amr", "ac3"
        };
        for (String ext : extensions) {
            Path audioPath = projectDir.resolve("audio." + ext);
            try {
                if (Files.exists(audioPath)) {
                    Files.delete(audioPath);
                    System.out.println("[数据管理] 检查音频文件是否存在: " + audioPath);
                    deleted = true;
                }
            } catch (IOException e) {
                System.err.println("[数据管理] 音频文件不存在: " + audioPath);
                e.printStackTrace();
            }
        }

        return deleted;
    }

    /**
     * 删除项目中的视频文件
     */
    public boolean deleteVideoFromProject(String projectId) {
        if (projectId == null) return false;

        Path projectDir = getProjectDir(projectId);
        boolean deleted = false;

        // 遍历所有视频格式
        String[] extensions = {
            "mp4", "mov", "avi", "mkv", "flv",
            "wmv", "m4v", "3gp", "webm", "vob",
            "mpg", "mpeg", "ts", "mts", "m2ts"
        };
        for (String ext : extensions) {
            Path videoPath = projectDir.resolve("video." + ext);
            try {
                if (Files.exists(videoPath)) {
                    Files.delete(videoPath);
                    System.out.println("[数据管理] 检查视频文件是否存在: " + videoPath);
                    deleted = true;
                }
            } catch (IOException e) {
                System.err.println("[数据管理] 视频文件不存在: " + videoPath);
                e.printStackTrace();
            }
        }

        return deleted;
    }

    /**
     * 导出为JSON文件
     */
    public boolean exportToJson(DanceGroup group, File outputFile) {
        if (group == null) return false;

        try {
            String json = gson.toJson(group);
            Files.writeString(outputFile.toPath(), json, TEXT_CHARSET);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从JSON文件导入
     */
    public DanceGroup importFromJson(File inputFile) {
        try {
            String json = readTextWithEncodingFallback(inputFile.toPath());
            return gson.fromJson(json, DanceGroup.class);
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    /**
     * 导出为CSV文件
     */
    public boolean exportToCsv(DanceGroup group, File outputFile) {
        if (group == null || group.getFormations().isEmpty()) return false;

        try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outputFile), TEXT_CHARSET))) {
            // CSV表头
            writer.println("队形索引, 时间(ms), 舞者ID, 舞者名称, X坐标, Y坐标, 颜色");

            // 遍历所有队形和舞者
            for (var formation : group.getFormations()) {
                for (var dancer : formation.getDancers()) {
                    writer.printf("%s,%d,%s,%s,%.2f,%.2f,#%06X%n",
                        formation.getName(),
                        formation.getTimestamp(),
                        dancer.getId(),
                        dancer.getName(),
                        dancer.getX(),
                        dancer.getY(),
                        dancer.getColor() & 0xFFFFFF
                    );
                }
            }

            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 获取所有工程列表
     */
    public List<ProjectInfo> listProjects() {
        List<ProjectInfo> projects = new ArrayList<>();

        try {
            if (!Files.exists(projectsDir)) {
                return projects;
            }

            Files.list(projectsDir)
                .filter(Files::isDirectory)
                .forEach(projectDir -> {
                    try {
                        Path projectFile = projectDir.resolve("project.json");
                        if (Files.exists(projectFile)) {
                            String json = readTextWithEncodingFallback(projectFile);
                            DanceGroup group = gson.fromJson(json, DanceGroup.class);

                            // 检查缩略图
                            Path thumbnailPath = projectDir.resolve("thumbnail.png");
                            String thumbnail = Files.exists(thumbnailPath) ? thumbnailPath.toString() : null;

                            projects.add(new ProjectInfo(
                                group.getId(),
                                group.getName(),
                                group.getDancerCount(),
                                group.getFormations().size(),
                                group.getModifiedTime(),
                                thumbnail
                            ));
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                });
        } catch (IOException e) {
            e.printStackTrace();
        }

        // 按修改时间降序排列
        projects.sort((a, b) -> Long.compare(b.modifiedTime, a.modifiedTime));

        return projects;
    }

    /**
     * 删除工程
     */
    public boolean deleteProject(String projectId) {
        try {
            Path projectDir = getProjectDir(projectId);
            if (!Files.exists(projectDir)) return false;

            // 递归删除目录
            deleteDirectory(projectDir);
            System.out.println("[工程管理] 已删除工程: " + projectId);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 递归删除指定目录
     */
    private void deleteDirectory(Path directory) throws IOException {
        if (Files.exists(directory)) {
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                paths.sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.delete(path);
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
        }
    }

    /**
     * 导出工程为ZIP包
     * @param group 工程数据
     * @param audioFile 音频文件
     * @param videoFile 视频文件
     * @param outputFile 输出ZIP文件
     * @return 是否成功
     */
    public boolean exportProjectAsZip(DanceGroup group, File audioFile, File videoFile, File outputFile) {
        if (group == null) return false;

        try (ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(outputFile))) {
            // 1. 写入JSON数据（project.json）
            String json = gson.toJson(group);
            ZipEntry jsonEntry = new ZipEntry("project.json");
            zipOut.putNextEntry(jsonEntry);
            zipOut.write(json.getBytes(TEXT_CHARSET));
            zipOut.closeEntry();

            // 2. 写入音频文件（如果有）
            if (audioFile != null && audioFile.exists()) {
                String extension = getFileExtension(audioFile.getName());
                String audioFileName = extension.isEmpty() ? "audio.mp3" : "audio." + extension;

                ZipEntry audioEntry = new ZipEntry(audioFileName);
                zipOut.putNextEntry(audioEntry);

                try (FileInputStream fis = new FileInputStream(audioFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        zipOut.write(buffer, 0, bytesRead);
                    }
                }
                zipOut.closeEntry();
            }

            // 3. 写入视频文件（如果有）
            if (videoFile != null && videoFile.exists()) {
                String extension = getFileExtension(videoFile.getName());
                String videoFileName = extension.isEmpty() ? "video.mp4" : "video." + extension;

                ZipEntry videoEntry = new ZipEntry(videoFileName);
                zipOut.putNextEntry(videoEntry);

                try (FileInputStream fis = new FileInputStream(videoFile)) {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        zipOut.write(buffer, 0, bytesRead);
                    }
                }
                zipOut.closeEntry();
            }

            // 4. Write dancer style images
            Set<String> exportedStyleDancerIds = new HashSet<>();
            for (var formation : group.getFormations()) {
                for (var dancer : formation.getDancers()) {
                    String dancerId = dancer.getId();
                    String stylePath = dancer.getCustomStylePath();
                    if (dancerId == null || dancerId.isBlank()) continue;
                    if (stylePath == null || stylePath.isBlank()) continue;
                    if (!exportedStyleDancerIds.add(dancerId)) continue;

                    File styleFile = new File(stylePath);
                    if (!styleFile.exists() || !styleFile.isFile()) continue;

                    ZipEntry styleEntry = new ZipEntry("dancer_styles/" + dancerId + ".png");
                    zipOut.putNextEntry(styleEntry);
                    try (FileInputStream fis = new FileInputStream(styleFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = fis.read(buffer)) != -1) {
                            zipOut.write(buffer, 0, bytesRead);
                        }
                    }
                    zipOut.closeEntry();
                }
            }

            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * 从ZIP包导入工程
     * @param inputFile ZIP文件
     *
     */
    public ProjectImportResult importProjectFromZip(File inputFile) {
        DanceGroup group = null;
        File audioFile = null;
        File videoFile = null;
        Map<String, File> importedStyleFiles = new HashMap<>();

        try (ZipInputStream zipIn = new ZipInputStream(new FileInputStream(inputFile))) {
            ZipEntry entry;
            int entryCount = 0;
            long totalBytes = 0L;
            boolean projectSeen = false;
            boolean audioSeen = false;
            boolean videoSeen = false;

            // 创建临时解压目录
            Path tempDir = Files.createTempDirectory("formation_import_");

            while ((entry = zipIn.getNextEntry()) != null) {
                String entryName = entry.getName();

                if (++entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new IOException("工程包条目过多");
                }
                if (entry.isDirectory()) {
                    if (!"dancer_styles/".equals(entryName)) {
                        throw new IOException("工程包包含不支持的目录: " + entryName);
                    }
                    zipIn.closeEntry();
                    continue;
                }

                if (entryName.equals("project.json")) {
                    if (projectSeen) throw new IOException("工程包包含重复的 project.json");
                    projectSeen = true;
                    // 读取项目JSON数据
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    totalBytes += copyZipEntryLimited(zipIn, baos, MAX_PROJECT_JSON_BYTES, totalBytes);
                    String json = new String(baos.toByteArray(), TEXT_CHARSET);
                    group = gson.fromJson(json, DanceGroup.class);
                    validateImportedGroup(group);

                } else if (isAllowedMediaEntry(entryName, "audio", AUDIO_EXTENSIONS)) {
                    if (audioSeen) throw new IOException("工程包包含多个音频文件");
                    audioSeen = true;
                    // 提取音频文件
                    String extension = getFileExtension(entryName);
                    audioFile = tempDir.resolve("audio_" + System.currentTimeMillis() + "." + extension).toFile();

                    try (FileOutputStream fos = new FileOutputStream(audioFile)) {
                        totalBytes += copyZipEntryLimited(zipIn, fos, MAX_MEDIA_BYTES, totalBytes);
                    }

                } else if (isAllowedMediaEntry(entryName, "video", VIDEO_EXTENSIONS)) {
                    if (videoSeen) throw new IOException("工程包包含多个视频文件");
                    videoSeen = true;
                    // 提取视频文件
                    String extension = getFileExtension(entryName);
                    videoFile = tempDir.resolve("video_" + System.currentTimeMillis() + "." + extension).toFile();

                    try (FileOutputStream fos = new FileOutputStream(videoFile)) {
                        totalBytes += copyZipEntryLimited(zipIn, fos, MAX_MEDIA_BYTES, totalBytes);
                    }
                } else if (isAllowedStyleEntry(entryName)) {
                    String dancerId = extractDancerIdFromStyleEntry(entryName);
                    if (dancerId != null) {
                        File styleFile = tempDir.resolve("style_" + dancerId + ".png").toFile();
                        try (FileOutputStream fos = new FileOutputStream(styleFile)) {
                            totalBytes += copyZipEntryLimited(zipIn, fos, MAX_STYLE_BYTES, totalBytes);
                        }
                        importedStyleFiles.put(dancerId, styleFile);
                    }
                } else {
                    throw new IOException("工程包包含不支持的条目: " + entryName);
                }

                if (totalBytes > MAX_ARCHIVE_BYTES) throw new IOException("工程包解压后过大");

                zipIn.closeEntry();
            }

            if (!projectSeen || group == null) throw new IOException("工程包缺少有效的 project.json");

            // 保存导入的工程数据
            if (group != null) {
                applyImportedDancerStyles(group, importedStyleFiles);
                saveProject(group);
            }

        } catch (Exception e) {
            System.err.println("[工程导入] 已拒绝无效工程包: " + e.getMessage());
            return null;
        }

        return new ProjectImportResult(group, audioFile, videoFile);
    }

    private String extractDancerIdFromStyleEntry(String entryName) {
        if (entryName == null || !entryName.startsWith("dancer_styles/")) return null;
        String fileName = entryName.substring("dancer_styles/".length()).trim();
        if (fileName.isEmpty() || fileName.endsWith("/")) return null;
        int dot = fileName.lastIndexOf('.');
        String dancerId = dot > 0 ? fileName.substring(0, dot) : fileName;
        return dancerId.matches(SAFE_ID_PATTERN) ? dancerId : null;
    }

    private boolean isAllowedMediaEntry(String name, String prefix, Set<String> extensions) {
        if (name == null || name.contains("/") || name.contains("\\")) return false;
        if (!name.startsWith(prefix + ".")) return false;
        return extensions.contains(getFileExtension(name));
    }

    private boolean isAllowedStyleEntry(String name) {
        if (name == null || !name.startsWith("dancer_styles/") || !name.endsWith(".png")) return false;
        String relative = name.substring("dancer_styles/".length());
        return !relative.contains("/") && !relative.contains("\\")
            && relative.substring(0, relative.length() - 4).matches(SAFE_ID_PATTERN);
    }

    private long copyZipEntryLimited(InputStream input, OutputStream output,
                                     long entryLimit, long currentTotal) throws IOException {
        byte[] buffer = new byte[8192];
        long copied = 0L;
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            copied += bytesRead;
            if (copied > entryLimit || currentTotal + copied > MAX_ARCHIVE_BYTES) {
                throw new IOException("工程包解压大小超过限制");
            }
            output.write(buffer, 0, bytesRead);
        }
        return copied;
    }

    private void validateImportedGroup(DanceGroup group) throws IOException {
        if (group == null || group.getId() == null || !group.getId().matches(SAFE_ID_PATTERN)) {
            throw new IOException("工程 ID 无效");
        }
        if (group.getName() == null || group.getName().length() > 200) {
            throw new IOException("工程名称无效");
        }
        if (!Float.isFinite(group.getStageWidth()) || !Float.isFinite(group.getStageDepth())
            || group.getStageWidth() <= 0 || group.getStageDepth() <= 0) {
            throw new IOException("舞台尺寸无效");
        }
        if (group.getFormations() == null || group.getFormations().size() > MAX_FORMATIONS) {
            throw new IOException("队形数量超过限制");
        }
        for (var formation : group.getFormations()) {
            if (formation == null || formation.getId() == null
                || !formation.getId().matches(SAFE_ID_PATTERN)
                || formation.getDancers() == null
                || formation.getDancers().size() > MAX_DANCERS_PER_FORMATION) {
                throw new IOException("队形数据无效");
            }
            for (var dancer : formation.getDancers()) {
                if (dancer == null || dancer.getId() == null || !dancer.getId().matches(SAFE_ID_PATTERN)) {
                    throw new IOException("舞者 ID 无效");
                }
            }
        }
    }

    private void applyImportedDancerStyles(DanceGroup group, Map<String, File> importedStyleFiles) {
        if (group == null || importedStyleFiles == null || importedStyleFiles.isEmpty()) return;

        try {
            Path styleDir = getProjectDancerStylesDir(group.getId());
            Files.createDirectories(styleDir);

            Map<String, String> stylePathByDancerId = new HashMap<>();
            for (Map.Entry<String, File> entry : importedStyleFiles.entrySet()) {
                String dancerId = entry.getKey();
                File source = entry.getValue();
                if (dancerId == null || dancerId.isBlank() || source == null || !source.exists()) continue;

                Path target = styleDir.resolve(dancerId + ".png");
                Files.copy(source.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
                stylePathByDancerId.put(dancerId, target.toFile().getAbsolutePath());
            }

            for (var formation : group.getFormations()) {
                for (var dancer : formation.getDancers()) {
                    String dancerId = dancer.getId();
                    if (dancerId == null || dancerId.isBlank()) continue;

                    String stylePath = stylePathByDancerId.get(dancerId);
                    if (stylePath != null) {
                        dancer.setCustomStylePath(stylePath);
                        continue;
                    }

                    if (dancer.getCustomStylePath() != null) {
                        File fallback = styleDir.resolve(dancerId + ".png").toFile();
                        dancer.setCustomStylePath(fallback.exists() ? fallback.getAbsolutePath() : null);
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String readTextWithEncodingFallback(Path filePath) throws IOException {
        byte[] raw = Files.readAllBytes(filePath);

        String utf8Text = decodeIfValid(raw, TEXT_CHARSET);
        if (utf8Text != null) {
            return trimUtf8Bom(utf8Text);
        }

        Charset systemCharset = Charset.defaultCharset();
        if (!TEXT_CHARSET.equals(systemCharset)) {
            String systemText = decodeIfValid(raw, systemCharset);
            if (systemText != null) {
                return trimUtf8Bom(systemText);
            }
        }

        Charset gb18030 = Charset.forName("GB18030");
        if (!TEXT_CHARSET.equals(gb18030) && !gb18030.equals(systemCharset)) {
            String gbText = decodeIfValid(raw, gb18030);
            if (gbText != null) {
                return trimUtf8Bom(gbText);
            }
        }

        return trimUtf8Bom(new String(raw, TEXT_CHARSET));
    }

    private String decodeIfValid(byte[] raw, Charset charset) {
        try {
            return charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw))
                .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private String trimUtf8Bom(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        return text.charAt(0) == '\uFEFF' ? text.substring(1) : text;
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        if (lastDot > 0 && lastDot < fileName.length() - 1) {
            return fileName.substring(lastDot + 1).toLowerCase();
        }
        return "";
    }

    /**
     * 工程导入结果
     */
    public static class ProjectImportResult {
        public final DanceGroup group;
        public final File audioFile;
        public final File videoFile;

        public ProjectImportResult(DanceGroup group, File audioFile, File videoFile) {
            this.group = group;
            this.audioFile = audioFile;
            this.videoFile = videoFile;
        }
    }

    /**
     * 工程信息
     */
    public static class ProjectInfo {
        public final String id;
        public final String name;
        public final int dancerCount;
        public final int formationCount;
        public final long modifiedTime;
        public final String thumbnailPath;

        public ProjectInfo(String id, String name, int dancerCount, int formationCount, long modifiedTime, String thumbnailPath) {
            this.id = id;
            this.name = name;
            this.dancerCount = dancerCount;
            this.formationCount = formationCount;
            this.modifiedTime = modifiedTime;
            this.thumbnailPath = thumbnailPath;
        }
    }
}
