package com.legacyrecon.pipeline;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 接入层文件扫描：收集相对路径 + 内容 SHA-256 校验和（02.1 SourceFile.checksum）。
 */
public final class FileScanner {

    private FileScanner() {
    }

    /** 相对项目根的路径。排除隐藏目录与常见构建产物。 */
    public static List<Path> listSourceFiles(Path root, String language) {
        try (Stream<Path> s = Files.walk(root)) {
            return s.filter(Files::isRegularFile)
                    .filter(p -> isSource(p, language))
                    .filter(p -> !isIgnored(p))
                    .map(p -> root.relativize(p))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (IOException e) {
            throw new RuntimeException("扫描源文件失败：" + root, e);
        }
    }

    private static boolean isSource(Path p, String language) {
        String n = p.getFileName().toString().toLowerCase();
        if ("java".equalsIgnoreCase(language)) {
            return n.endsWith(".java");
        }
        if ("cpp".equalsIgnoreCase(language) || "c".equalsIgnoreCase(language)) {
            return n.endsWith(".cpp") || n.endsWith(".cc") || n.endsWith(".h") || n.endsWith(".hpp");
        }
        return false;
    }

    private static boolean isIgnored(Path p) {
        for (Path part : p) {
            String s = part.toString();
            if (s.startsWith(".") || s.equals("target") || s.equals("build") || s.equals("node_modules")) {
                return true;
            }
        }
        return false;
    }

    public static String checksum(Path root, Path rel) {
        try {
            byte[] bytes = Files.readAllBytes(root.resolve(rel));
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(bytes);
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("计算校验和失败：" + rel, e);
        }
    }

    public static String read(Path root, Path rel) {
        try {
            return Files.readString(root.resolve(rel), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("读取源文件失败：" + rel, e);
        }
    }
}