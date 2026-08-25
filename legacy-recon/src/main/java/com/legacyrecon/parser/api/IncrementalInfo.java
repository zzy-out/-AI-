package com.legacyrecon.parser.api;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 02.4 增量解析上下文。持久状态 S_old（校验和集合）存于事实层；
 * 此处存放本次由接入层计算的差异：dirty + 反向传播后的受影响文件集。
 */
public class IncrementalInfo {
    /** 本次待重解析的受影响文件路径（added+changed，不含 deleted） */
    public Set<String> affectedFiles = new LinkedHashSet<>();
    /** 已删除文件路径（content=null 时由解析器内部依赖视图处理） */
    public Set<String> deletedFiles = new LinkedHashSet<>();
    /** 是否强制全量（手动 forceFull=true，R15） */
    public boolean forceFull;

    public List<String> affectedList() {
        return new ArrayList<>(affectedFiles);
    }
}